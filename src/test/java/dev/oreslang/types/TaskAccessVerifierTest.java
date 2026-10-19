package dev.oreslang.types;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Admission-layer tests; these do not prove guest Task execution. */
final class TaskAccessVerifierTest {
    private static final TaskAccessVerifier.Root WORLD =
            new TaskAccessVerifier.Root("PROCESS", 31, 1, true);
    private static final TaskAccessVerifier.Root UNKNOWN =
            new TaskAccessVerifier.Root("PROCESS", 32, 1, false);

    private static TaskAccessVerifier.Claim read(TaskAccessVerifier.Place place) {
        return new TaskAccessVerifier.Claim(place, TaskAccessVerifier.Mode.READ);
    }
    private static TaskAccessVerifier.Claim write(TaskAccessVerifier.Place place) {
        return new TaskAccessVerifier.Claim(place, TaskAccessVerifier.Mode.WRITE);
    }

    @Test void coexistingReadersAndExclusiveWriters() {
        var ledger = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);
        var a = ledger.admit(List.of(read(place)));
        var b = ledger.admit(List.of(read(place)));
        assertEquals(2, ledger.activeChildren());
        assertFalse(ledger.parentMayAccess(write(place)));
        assertTrue(ledger.parentMayAccess(read(place)));
        assertThrows(IllegalArgumentException.class, () -> ledger.admit(List.of(write(place))));
        ledger.quiescent(a);
        ledger.quiescent(b);
        var writer = ledger.admit(List.of(write(place)));
        assertFalse(ledger.parentMayAccess(read(place)));
        ledger.quiescent(writer);
        ledger.finishScope();
    }

    @Test void verifiedSoaColumnsCanBeWrittenInParallel() {
        var ledger = new TaskAccessVerifier();
        var world = TaskAccessVerifier.Place.whole(WORLD);
        var x = ledger.admit(List.of(write(world.field("positions", true))));
        var y = ledger.admit(List.of(write(world.field("velocities", true))));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admit(List.of(write(world))));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admit(List.of(read(world.field("positions", true)))));
        ledger.quiescent(x);
        ledger.quiescent(y);
        ledger.finishScope();
    }

    @Test void unknownFieldLayoutDoesNotClaimDisjointness() {
        var ledger = new TaskAccessVerifier();
        var world = TaskAccessVerifier.Place.whole(WORLD);
        ledger.admit(List.of(write(world.field("positions", false))));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admit(List.of(write(world.field("velocities", true)))));
    }

    @Test void verifiedRangesAreHalfOpenAndChecked() {
        var ledger = new TaskAccessVerifier();
        var column = TaskAccessVerifier.Place.whole(WORLD).field("positions", true);
        ledger.admit(List.of(write(column.range(0, 64, true))));
        ledger.admit(List.of(write(column.range(64, 128, true))));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admit(List.of(write(column.range(63, 65, true)))));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admit(List.of(write(column.range(128, 130, false)))));
        assertThrows(IllegalArgumentException.class,
                () -> column.range(-1, 50, true));
        assertThrows(IllegalArgumentException.class,
                () -> column.range(64, 64, true));
    }

    @Test void parentCannotMoveOrMutateLiveBorrowedStorage() {
        var ledger = new TaskAccessVerifier();
        var root = TaskAccessVerifier.Place.whole(WORLD);
        var ticket = ledger.admit(List.of(read(root)));
        assertFalse(ledger.parentMayAccess(write(root)));
        assertTrue(ledger.parentMayAccess(read(root)));
        assertThrows(IllegalStateException.class, ledger::finishScope);
        ledger.requestCancel(ticket);
        assertTrue(ledger.cancellationRequested(ticket));
        assertFalse(ledger.parentMayAccess(write(root)), "cancel is not physical quiescence");
        assertThrows(IllegalStateException.class, ledger::finishScope);
        ledger.quiescent(ticket);
        assertTrue(ledger.parentMayAccess(write(root)));
        ledger.finishScope();
        assertThrows(IllegalStateException.class, () -> ledger.admit(List.of()));
    }

    @Test void admissionFailureDoesNotReservePartialClaims() {
        var ledger = new TaskAccessVerifier();
        var root = TaskAccessVerifier.Place.whole(WORLD);
        ledger.admit(List.of(write(root.field("positions", true))));
        assertThrows(IllegalArgumentException.class, () -> ledger.admit(List.of(
                write(root.field("velocities", true)),
                read(root.field("positions", true)))));
        assertEquals(1, ledger.activeChildren());
        var second = ledger.admit(List.of(write(root.field("velocities", true))));
        assertNotNull(second);
        assertEquals(2, ledger.activeChildren());
    }

    @Test void siblingClaimsInsideSameTaskAlsoChecked() {
        var ledger = new TaskAccessVerifier();
        var root = TaskAccessVerifier.Place.whole(WORLD);
        assertThrows(IllegalArgumentException.class, () -> ledger.admit(List.of(
                read(root), write(root.field("positions", true)))));
        assertEquals(0, ledger.activeChildren());
        assertNotNull(ledger.admit(List.of(
                read(root.field("positions", true)), read(root.field("positions", true)))));
    }

    @Test void unknownAllocationRootsAreConservativelyAliased() {
        var ledger = new TaskAccessVerifier();
        var uncertain = TaskAccessVerifier.Place.whole(UNKNOWN);
        var known = TaskAccessVerifier.Place.whole(WORLD);
        ledger.admit(List.of(write(uncertain)));
        assertThrows(IllegalArgumentException.class, () -> ledger.admit(List.of(write(known))));
    }

    @Test void twoUniquelyOwnedAllocationsDoNotAlias() {
        var ledger = new TaskAccessVerifier();
        ledger.admit(List.of(write(TaskAccessVerifier.Place.whole(WORLD))));
        var distinct = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 33, 1, true));
        assertNotNull(ledger.admit(List.of(write(distinct))));
    }

    @Test void crossDomainClaimsCannotSilentlyBecomeShared() {
        var ledger = new TaskAccessVerifier();
        ledger.admit(List.of(write(TaskAccessVerifier.Place.whole(WORLD))));
        var otherDomain = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", 33, 1, true));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admit(List.of(write(otherDomain))));
    }

    @Test void staleGenerationAndBarrierCannotRerouteLiveLoans() {
        var ledger = new TaskAccessVerifier();
        var original = TaskAccessVerifier.Place.whole(WORLD);
        var ticket = ledger.admit(List.of(read(original)));
        assertThrows(IllegalStateException.class,
                () -> ledger.structuralBarrier(WORLD, 2));
        assertThrows(IllegalStateException.class, () -> ledger.admit(List.of(
                read(original), read(TaskAccessVerifier.Place.whole(
                        new TaskAccessVerifier.Root("PROCESS", 31, 2, true))))));
        assertEquals(1, ledger.activeChildren());
        ledger.quiescent(ticket);
        ledger.structuralBarrier(WORLD, 2);
        assertThrows(IllegalStateException.class,
                () -> ledger.admit(List.of(read(original))));
        assertNotNull(ledger.admit(List.of(read(TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 31, 2, true))))));
    }

    @Test void staleAndForeignTicketsCannotReleaseLoans() {
        var one = new TaskAccessVerifier();
        var two = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);
        var ticket = one.admit(List.of(write(place)));
        assertThrows(IllegalArgumentException.class, () -> two.quiescent(ticket));
        assertThrows(IllegalArgumentException.class, () -> two.requestCancel(ticket));
        assertFalse(one.parentMayAccess(read(place)));
        one.quiescent(ticket);
        assertThrows(IllegalArgumentException.class, () -> one.quiescent(ticket));
        assertThrows(IllegalArgumentException.class, () -> one.requestCancel(ticket));
    }

    @Test void projectionPrefixIsNeverDisjoint() {
        var root = TaskAccessVerifier.Place.whole(WORLD);
        var a = root.field("positions", true);
        var b = a.range(0, 64, true);
        assertTrue(TaskAccessVerifier.mayAlias(a, b));
        assertTrue(TaskAccessVerifier.mayAlias(b, root));
    }

    @Test void typedTaskAdmissionUsesSendForWriteAndSyncForRead() {
        var ledger = new TaskAccessVerifier();
        var unique = TaskAccessVerifier.Place.whole(WORLD);
        var channel = new Types.Named("Channel", List.of(Types.Primitive.INT));
        var syncRead = new TaskAccessVerifier.TypedClaim(read(unique), channel);
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admitTyped(List.of(syncRead), (name, trait) -> false));
        assertEquals(0, ledger.activeChildren());
        var sendWrite = new TaskAccessVerifier.TypedClaim(write(unique), channel);
        var ticket = ledger.admitTyped(List.of(sendWrite), (name, trait) -> false);
        assertEquals(1, ledger.activeChildren());
        ledger.quiescent(ticket);
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admitTyped(List.of(new TaskAccessVerifier.TypedClaim(
                        read(unique), new Types.Generic("T"))), (name, trait) -> true));
        assertEquals(0, ledger.activeChildren());
    }

    @Test void typedAdmissionRejectsEveryClaimBeforeAnyReservation() {
        var ledger = new TaskAccessVerifier();
        var pos = TaskAccessVerifier.Place.whole(WORLD).field("position", true);
        var vel = TaskAccessVerifier.Place.whole(WORLD).field("velocity", true);
        assertThrows(IllegalArgumentException.class, () -> ledger.admitTyped(List.of(
                new TaskAccessVerifier.TypedClaim(write(pos), Types.Primitive.INT),
                new TaskAccessVerifier.TypedClaim(read(vel),
                        new Types.Named("OpaqueHostHandle", List.of()))),
                (name, trait) -> false));
        assertEquals(0, ledger.activeChildren());
    }

    @Test void illegalInputsFailClosed() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskAccessVerifier.Root("", 1, 1, true));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskAccessVerifier.Root("PROCESS", 0, 1, true));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskAccessVerifier.Root("PROCESS", 1, 0, true));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskAccessVerifier.Field("", true));
        assertThrows(NullPointerException.class, () ->
                new TaskAccessVerifier().admit(java.util.Arrays.asList(
                        (TaskAccessVerifier.Claim) null)));
    }
}
