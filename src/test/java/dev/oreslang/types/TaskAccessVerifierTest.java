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

    /**
     * Test-only compiler type fixture: every test claim resolves to int.
     * Production code has NO unchecked reservation method. Real source
     * lowering must obtain types from compiler-resolved, canonical places.
     */
    private static TaskAccessVerifier.Ticket admit(
            TaskAccessVerifier ledger, List<TaskAccessVerifier.Claim> claims) {
        List<TaskAccessVerifier.TypedClaim> typed = claims.stream()
                .map(claim -> new TaskAccessVerifier.TypedClaim(claim, Types.Primitive.INT))
                .toList();
        return ledger.admitTyped(typed, (name, trait) -> false,
                place -> Types.Primitive.INT);
    }

    @Test void coexistingReadersAndExclusiveWriters() {
        var ledger = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);
        var a = admit(ledger, List.of(read(place)));
        var b = admit(ledger, List.of(read(place)));
        assertEquals(2, ledger.activeChildren());
        assertFalse(ledger.parentMayAccess(write(place)));
        assertTrue(ledger.parentMayAccess(read(place)));
        assertThrows(IllegalArgumentException.class, () -> admit(ledger, List.of(write(place))));
        ledger.quiescent(a);
        ledger.quiescent(b);
        var writer = admit(ledger, List.of(write(place)));
        assertFalse(ledger.parentMayAccess(read(place)));
        ledger.quiescent(writer);
        ledger.finishScope();
    }

    @Test void verifiedSoaColumnsCanBeWrittenInParallel() {
        var ledger = new TaskAccessVerifier();
        var world = TaskAccessVerifier.Place.whole(WORLD);
        var x = admit(ledger, List.of(write(world.field("positions", true))));
        var y = admit(ledger, List.of(write(world.field("velocities", true))));
        assertThrows(IllegalArgumentException.class,
                () -> admit(ledger, List.of(write(world))));
        assertThrows(IllegalArgumentException.class,
                () -> admit(ledger, List.of(read(world.field("positions", true)))));
        ledger.quiescent(x);
        ledger.quiescent(y);
        ledger.finishScope();
    }

    @Test void unknownFieldLayoutDoesNotClaimDisjointness() {
        var ledger = new TaskAccessVerifier();
        var world = TaskAccessVerifier.Place.whole(WORLD);
        admit(ledger, List.of(write(world.field("positions", false))));
        assertThrows(IllegalArgumentException.class,
                () -> admit(ledger, List.of(write(world.field("velocities", true)))));
    }

    @Test void verifiedRangesAreHalfOpenAndChecked() {
        var ledger = new TaskAccessVerifier();
        var column = TaskAccessVerifier.Place.whole(WORLD).field("positions", true);
        admit(ledger, List.of(write(column.range(0, 64, true))));
        admit(ledger, List.of(write(column.range(64, 128, true))));
        assertThrows(IllegalArgumentException.class,
                () -> admit(ledger, List.of(write(column.range(63, 65, true)))));
        assertThrows(IllegalArgumentException.class,
                () -> admit(ledger, List.of(write(column.range(128, 130, false)))));
        assertThrows(IllegalArgumentException.class,
                () -> column.range(-1, 50, true));
        assertThrows(IllegalArgumentException.class,
                () -> column.range(64, 64, true));
    }

    @Test void parentCannotMoveOrMutateLiveBorrowedStorage() {
        var ledger = new TaskAccessVerifier();
        var root = TaskAccessVerifier.Place.whole(WORLD);
        var ticket = admit(ledger, List.of(read(root)));
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
        assertThrows(IllegalStateException.class, () -> admit(ledger, List.of()));
    }

    @Test void admissionFailureDoesNotReservePartialClaims() {
        var ledger = new TaskAccessVerifier();
        var root = TaskAccessVerifier.Place.whole(WORLD);
        admit(ledger, List.of(write(root.field("positions", true))));
        assertThrows(IllegalArgumentException.class, () -> admit(ledger, List.of(
                write(root.field("velocities", true)),
                read(root.field("positions", true)))));
        assertEquals(1, ledger.activeChildren());
        var second = admit(ledger, List.of(write(root.field("velocities", true))));
        assertNotNull(second);
        assertEquals(2, ledger.activeChildren());
    }

    @Test void siblingClaimsInsideSameTaskAlsoChecked() {
        var ledger = new TaskAccessVerifier();
        var root = TaskAccessVerifier.Place.whole(WORLD);
        assertThrows(IllegalArgumentException.class, () -> admit(ledger, List.of(
                read(root), write(root.field("positions", true)))));
        assertEquals(0, ledger.activeChildren());
        assertNotNull(admit(ledger, List.of(
                read(root.field("positions", true)), read(root.field("positions", true)))));
    }

    @Test void unknownAllocationRootsAreConservativelyAliased() {
        var ledger = new TaskAccessVerifier();
        var uncertain = TaskAccessVerifier.Place.whole(UNKNOWN);
        var known = TaskAccessVerifier.Place.whole(WORLD);
        admit(ledger, List.of(write(uncertain)));
        assertThrows(IllegalArgumentException.class, () -> admit(ledger, List.of(write(known))));
    }

    @Test void twoUniquelyOwnedAllocationsDoNotAlias() {
        var ledger = new TaskAccessVerifier();
        admit(ledger, List.of(write(TaskAccessVerifier.Place.whole(WORLD))));
        var distinct = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 33, 1, true));
        assertNotNull(admit(ledger, List.of(write(distinct))));
    }

    @Test void crossDomainClaimsCannotSilentlyBecomeShared() {
        var ledger = new TaskAccessVerifier();
        admit(ledger, List.of(write(TaskAccessVerifier.Place.whole(WORLD))));
        var otherDomain = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-33:epoch-1", 33, 1, true));
        assertThrows(IllegalArgumentException.class,
                () -> admit(ledger, List.of(write(otherDomain))));
    }

    @Test void staleGenerationAndBarrierCannotRerouteLiveLoans() {
        var ledger = new TaskAccessVerifier();
        var original = TaskAccessVerifier.Place.whole(WORLD);
        var ticket = admit(ledger, List.of(read(original)));
        assertThrows(IllegalStateException.class,
                () -> ledger.structuralBarrier(WORLD, 2));
        assertThrows(IllegalStateException.class, () -> admit(ledger, List.of(
                read(original), read(TaskAccessVerifier.Place.whole(
                        new TaskAccessVerifier.Root("PROCESS", 31, 2, true))))));
        assertEquals(1, ledger.activeChildren());
        ledger.quiescent(ticket);
        ledger.structuralBarrier(WORLD, 2);
        assertThrows(IllegalStateException.class,
                () -> admit(ledger, List.of(read(original))));
        assertNotNull(admit(ledger, List.of(read(TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 31, 2, true))))));
    }

    @Test void staleAndForeignTicketsCannotReleaseLoans() {
        var one = new TaskAccessVerifier();
        var two = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);
        var ticket = admit(one, List.of(write(place)));
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

    @Test void unprovenIntermediateFieldCannotCreateDisjointNestedLoans() {
        var root = TaskAccessVerifier.Place.whole(WORLD);
        var uncertainParent = root.field("opaque_union", false);
        var left = uncertainParent.field("x", true);
        var right = uncertainParent.field("y", true);
        assertTrue(TaskAccessVerifier.mayAlias(left, right));
        var ledger = new TaskAccessVerifier();
        admit(ledger, List.of(write(left)));
        assertThrows(IllegalArgumentException.class,
                () -> admit(ledger, List.of(write(right))));
    }

    @Test void unprovenIntermediateRangeCannotCreateDisjointNestedLoans() {
        var root = TaskAccessVerifier.Place.whole(WORLD);
        var uncertainRange = root.range(0, 1024, false);
        assertTrue(TaskAccessVerifier.mayAlias(
                uncertainRange.field("position", true),
                uncertainRange.field("velocity", true)));
    }

    @Test void typedTaskAdmissionUsesSendForWriteAndSyncForRead() {
        var ledger = new TaskAccessVerifier();
        var unique = TaskAccessVerifier.Place.whole(WORLD);
        var channel = new Types.Named("Channel", List.of(Types.Primitive.INT));
        var syncRead = new TaskAccessVerifier.TypedClaim(read(unique), channel);
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admitTyped(List.of(syncRead), (name, trait) -> false, place -> channel));
        assertEquals(0, ledger.activeChildren());
        var sendWrite = new TaskAccessVerifier.TypedClaim(write(unique), channel);
        var ticket = ledger.admitTyped(List.of(sendWrite), (name, trait) -> false, place -> channel);
        assertEquals(1, ledger.activeChildren());
        ledger.quiescent(ticket);
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admitTyped(List.of(new TaskAccessVerifier.TypedClaim(
                        read(unique), new Types.Generic("T"))), (name, trait) -> true,
                        place -> new Types.Generic("T")));
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
                (name, trait) -> false,
                place -> place.equals(pos) ? Types.Primitive.INT
                        : new Types.Named("OpaqueHostHandle", List.of())));
        assertEquals(0, ledger.activeChildren());
    }

    @Test void mismatchedTypeAnnotationCannotForgeSendForUnsafePlace() {
        var ledger = new TaskAccessVerifier();
        var unsafePlace = TaskAccessVerifier.Place.whole(WORLD);
        var actual = new Types.Named("OpaqueHostHandle", List.of());
        var forged = new TaskAccessVerifier.TypedClaim(
                write(unsafePlace), Types.Primitive.INT);
        assertThrows(IllegalArgumentException.class, () ->
                ledger.admitTyped(List.of(forged), (name, trait) -> true,
                        place -> actual));
        assertEquals(0, ledger.activeChildren());
        assertEquals(0, ledger.activeClaims());
        // This also rejects a missing trusted MIR referent, not only a mismatch.
        assertThrows(IllegalArgumentException.class, () ->
                ledger.admitTyped(List.of(forged), (name, trait) -> true,
                        place -> null));
        assertEquals(0, ledger.activeChildren());
    }

    @Test void typeResolverFailureDoesNotReserveEarlierClaims() {
        var ledger = new TaskAccessVerifier();
        var root = TaskAccessVerifier.Place.whole(WORLD);
        var x = root.field("x", true);
        var y = root.field("y", true);
        var requests = List.of(
                new TaskAccessVerifier.TypedClaim(write(x), Types.Primitive.INT),
                new TaskAccessVerifier.TypedClaim(write(y), Types.Primitive.INT));
        assertThrows(IllegalStateException.class, () ->
                ledger.admitTyped(requests, (name, trait) -> false,
                        place -> {
                            if (place.equals(y)) throw new IllegalStateException("MIR lookup failed");
                            return Types.Primitive.INT;
                        }));
        assertEquals(0, ledger.activeChildren());
        assertEquals(0, ledger.activeClaims());
    }

    @Test void distinctReadOnlyDomainsCannotShareOneTaskScope() {
        var verifier = new TaskAccessVerifier();
        var process = TaskAccessVerifier.Place.whole(WORLD);
        var actor = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-50:epoch-1", 50, 1, true));
        var first = admit(verifier, List.of(read(process)));
        assertThrows(IllegalArgumentException.class,
                () -> admit(verifier, List.of(read(actor))));
        verifier.quiescent(first);
        assertThrows(IllegalArgumentException.class,
                () -> admit(verifier, List.of(read(actor))));
        assertFalse(verifier.parentMayAccess(read(actor)));
        assertEquals(0, verifier.activeChildren());
    }

    @Test void directReadReadConflictQueryCannotBypassDomainBoundary() {
        var process = TaskAccessVerifier.Place.whole(WORLD);
        var privateActor = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-50:epoch-1", 50, 1, true));
        assertTrue(TaskAccessVerifier.conflicts(read(process), read(privateActor)));
        assertTrue(TaskAccessVerifier.conflicts(read(privateActor), read(process)));
        assertFalse(TaskAccessVerifier.conflicts(read(process), read(process)));
    }

    @Test void aSingleTaskCannotMixReadOnlyTrustDomains() {
        var verifier = new TaskAccessVerifier();
        var process = TaskAccessVerifier.Place.whole(WORLD);
        var actor = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-50:epoch-1", 50, 1, true));
        assertThrows(IllegalArgumentException.class,
                () -> admit(verifier, List.of(read(process), read(actor))));
        assertEquals(0, verifier.activeChildren());
        // Failed admission must not bind a partially selected domain.
        assertNotNull(admit(verifier, List.of(read(actor))));
        assertFalse(verifier.parentMayAccess(read(process)));
    }

    @Test void privateActorsWithTheSameDomainCategoryCannotShareClaims() {
        var actorA = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-A:epoch-1", 1, 1, true));
        var actorB = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-B:epoch-1", 2, 1, true));
        var ledger = new TaskAccessVerifier();
        var first = admit(ledger, List.of(read(actorA)));
        assertTrue(TaskAccessVerifier.mayAlias(actorA, actorB));
        assertTrue(TaskAccessVerifier.conflicts(read(actorA), read(actorB)),
                "READ/READ does not authorize crossing actor heaps");
        assertThrows(IllegalArgumentException.class,
                () -> admit(ledger, List.of(read(actorB))));
        assertFalse(ledger.parentMayAccess(read(actorB)));
        ledger.quiescent(first);
        assertThrows(IllegalArgumentException.class,
                () -> admit(ledger, List.of(read(actorB))));
    }

    @Test void privateActorIdentityIncludesEpochAndCannotBeReused() {
        var actorAOld = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-A:epoch-1", 1, 1, true));
        var actorANew = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-A:epoch-2", 1, 1, true));
        var ledger = new TaskAccessVerifier();
        var ticket = admit(ledger, List.of(read(actorAOld)));
        ledger.quiescent(ticket);
        assertThrows(IllegalArgumentException.class,
                () -> admit(ledger, List.of(read(actorANew))));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.structuralBarrier(actorANew.root(), 2));
    }

    @Test void validatedClaimsWithinOnePrivateActorCanStillCoexist() {
        var a = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-A:epoch-1", 1, 1, true));
        var b = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-A:epoch-1", 2, 1, true));
        var ledger = new TaskAccessVerifier();
        assertNotNull(admit(ledger, List.of(read(a))));
        assertNotNull(admit(ledger, List.of(read(b))));
        assertEquals(2, ledger.activeChildren());
    }

    @Test void aCategoryOnlyActorRootIsNotValidAllocationProvenance() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskAccessVerifier.Root("ACTOR_PRIVATE", 51, 1, true));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskAccessVerifier.Root("UNTRUSTED", 51, 1, true));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskAccessVerifier.Root("ACTOR_PRIVATE", "ACTOR_PRIVATE", 51, 1, true));
        assertThrows(IllegalArgumentException.class,
                () -> new TaskAccessVerifier.Root("ACTOR_PRIVATE", "", 51, 1, true));
    }

    @Test void staleParentQueriesDoNotReturnAuthorityAfterLayoutGenerationAdvance() {
        var verifier = new TaskAccessVerifier();
        var before = TaskAccessVerifier.Place.whole(WORLD);
        assertFalse(verifier.parentMayAccess(read(before)), "unbound scope is not a permit");
        var ticket = admit(verifier, List.of(read(before)));
        verifier.quiescent(ticket);
        verifier.structuralBarrier(WORLD, 2);
        assertFalse(verifier.parentMayAccess(read(before)));
        var after = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 31, 2, true));
        assertTrue(verifier.parentMayAccess(write(after)));
        assertFalse(verifier.parentMayAccess(read(TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 987, 1, true)))));
    }

    @Test void structuralBarrierRejectsAllPotentiallyAliasingRoots() {
        var verifier = new TaskAccessVerifier();
        var owner = TaskAccessVerifier.Place.whole(WORLD);
        var ticket = admit(verifier, List.of(read(owner)));
        verifier.quiescent(ticket);
        // A different numeric ID does not establish nonaliasing without
        // independently verified unique-allocation provenance.
        var uncertain = TaskAccessVerifier.Place.whole(UNKNOWN);
        var unknownTicket = admit(verifier, List.of(read(uncertain)));
        assertThrows(IllegalStateException.class,
                () -> verifier.structuralBarrier(WORLD, 2));
        verifier.quiescent(unknownTicket);
        verifier.structuralBarrier(WORLD, 2);
        assertFalse(verifier.parentMayAccess(read(owner)));
    }

    @Test void closingScopeRevokesAllParentAuthorityAndRetiresProvenance() {
        var verifier = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);
        var ticket = admit(verifier, List.of(read(place)));
        verifier.quiescent(ticket);
        assertTrue(verifier.parentMayAccess(write(place)));
        verifier.finishScope();
        assertFalse(verifier.parentMayAccess(read(place)));
        assertFalse(verifier.parentMayAccess(write(place)));
        assertEquals(0, verifier.activeChildren());
        assertThrows(IllegalStateException.class,
                () -> admit(verifier, List.of(read(place))));
        assertThrows(IllegalStateException.class,
                () -> verifier.structuralBarrier(WORLD, 2));
    }

    @Test void admissionCapacityErrorsAreAtomic() {
        var verifier = new TaskAccessVerifier();
        var requested = java.util.Collections.nCopies(
                1025, read(TaskAccessVerifier.Place.whole(WORLD)));
        assertThrows(IllegalStateException.class, () -> admit(verifier, requested));
        assertEquals(0, verifier.activeChildren());
        var a = admit(verifier, List.of(read(TaskAccessVerifier.Place.whole(WORLD))));
        assertNotNull(a);
        verifier.quiescent(a);
    }

    @Test void quadraticAdmissionWorkBudgetIsAtomicAndReclaimable() {
        var ledger = new TaskAccessVerifier();
        var root = TaskAccessVerifier.Place.whole(WORLD);
        var bulkReaders = java.util.Collections.nCopies(1024, read(root));
        var first = admit(ledger, bulkReaders);
        var second = admit(ledger, bulkReaders);
        assertEquals(2, ledger.activeChildren());
        assertEquals(2048, ledger.activeClaims());
        // The third admission would exceed two million conservative
        // pairwise checks; reject it before reserving any claim or ticket.
        assertThrows(IllegalStateException.class, () -> admit(ledger, bulkReaders));
        assertEquals(2, ledger.activeChildren());
        assertEquals(2048, ledger.activeClaims());
        ledger.requestCancel(first);
        assertEquals(2048, ledger.activeClaims(),
                "cancellation must not release any borrow");
        ledger.quiescent(first);
        assertEquals(1024, ledger.activeClaims());
        var third = admit(ledger, bulkReaders);
        assertEquals(2048, ledger.activeClaims());
        ledger.quiescent(second);
        ledger.quiescent(third);
        assertEquals(0, ledger.activeClaims());
        ledger.finishScope();
    }

    @Test void multipleSmallClaimsPreserveLiveBudgetAfterFailure() {
        var ledger = new TaskAccessVerifier();
        var root = TaskAccessVerifier.Place.whole(WORLD);
        var first = admit(ledger, List.of(read(root), read(root)));
        assertEquals(2, ledger.activeClaims());
        assertThrows(IllegalArgumentException.class,
                () -> admit(ledger, List.of(read(root), write(root))));
        assertEquals(2, ledger.activeClaims());
        ledger.quiescent(first);
        assertEquals(0, ledger.activeClaims());
        assertThrows(IllegalArgumentException.class, () -> ledger.quiescent(first));
        assertEquals(0, ledger.activeClaims());
    }

    @Test void changingFieldProofFlagsCannotRetypeSamePhysicalPlace() {
        var ledger = new TaskAccessVerifier();
        var root = TaskAccessVerifier.Place.whole(WORLD);
        var optimistic = root.field("position", true);
        var conservative = root.field("position", false);
        var initial = ledger.admitTyped(List.of(
                        new TaskAccessVerifier.TypedClaim(read(optimistic), Types.Primitive.INT)),
                (type, trait) -> false, place -> Types.Primitive.INT);
        assertThrows(IllegalStateException.class, () -> ledger.admitTyped(List.of(
                        new TaskAccessVerifier.TypedClaim(read(conservative), Types.Primitive.BOOL)),
                (type, trait) -> false, place -> Types.Primitive.BOOL));
        assertEquals(1, ledger.activeChildren());
        ledger.quiescent(initial);
        assertThrows(IllegalStateException.class, () -> ledger.admitTyped(List.of(
                        new TaskAccessVerifier.TypedClaim(read(conservative), Types.Primitive.BOOL)),
                (type, trait) -> false, place -> Types.Primitive.BOOL));
        assertEquals(0, ledger.activeClaims());
        var compatible = ledger.admitTyped(List.of(
                        new TaskAccessVerifier.TypedClaim(read(conservative), Types.Primitive.INT)),
                (type, trait) -> false, place -> Types.Primitive.INT);
        ledger.quiescent(compatible);
    }

    @Test void allocationCannotUpgradeUniquenessAfterAnotherClaim() {
        var ledger = new TaskAccessVerifier();
        var uncertain = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 801, 1, false));
        var forgedUnique = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 801, 1, true));
        var first = admit(ledger, List.of(read(uncertain)));
        assertThrows(IllegalStateException.class, () ->
                admit(ledger, List.of(read(forgedUnique))));
        assertEquals(1, ledger.activeChildren());
        assertThrows(IllegalStateException.class, () ->
                ledger.structuralBarrier(forgedUnique.root(), 2));
        ledger.quiescent(first);
        assertThrows(IllegalStateException.class, () ->
                admit(ledger, List.of(read(forgedUnique))));
        ledger.structuralBarrier(uncertain.root(), 2);
    }

    @Test void conflictingUniquenessWithinOneBatchIsAtomic() {
        var ledger = new TaskAccessVerifier();
        var unique = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 802, 1, true));
        var uncertain = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 802, 1, false));
        assertThrows(IllegalStateException.class,
                () -> admit(ledger, List.of(read(unique), read(uncertain))));
        assertEquals(0, ledger.activeChildren());
        assertEquals(0, ledger.activeClaims());
        assertNotNull(admit(ledger, List.of(read(uncertain))));
    }

    @Test void rangeLayoutFlagsCannotLaunderPlaceTypeEvidence() {
        var ledger = new TaskAccessVerifier();
        var root = TaskAccessVerifier.Place.whole(WORLD);
        var certified = root.range(0, 64, true);
        var uncertified = root.range(0, 64, false);
        var ticket = ledger.admitTyped(List.of(
                new TaskAccessVerifier.TypedClaim(read(certified), Types.Primitive.INT)),
                (name, trait) -> false, place -> Types.Primitive.INT);
        assertThrows(IllegalStateException.class, () -> ledger.admitTyped(List.of(
                new TaskAccessVerifier.TypedClaim(read(uncertified), Types.Primitive.BOOL)),
                (name, trait) -> false, place -> Types.Primitive.BOOL));
        ledger.quiescent(ticket);
        assertEquals(0, ledger.activeClaims());
        assertThrows(IllegalStateException.class, () -> ledger.admitTyped(List.of(
                new TaskAccessVerifier.TypedClaim(read(uncertified), Types.Primitive.BOOL)),
                (name, trait) -> false, place -> Types.Primitive.BOOL));
    }

    @Test void typedOversizePreflightAvoidsExecutingUntrustedResolvers() {
        var ledger = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);
        var oversized = java.util.Collections.nCopies(1025,
                new TaskAccessVerifier.TypedClaim(read(place), Types.Primitive.INT));
        var resolvers = new java.util.concurrent.atomic.AtomicInteger();
        var traitCallbacks = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IllegalStateException.class, () ->
                ledger.admitTyped(oversized, (type, trait) -> {
                    traitCallbacks.incrementAndGet();
                    return true;
                }, p -> {
                    resolvers.incrementAndGet();
                    return Types.Primitive.INT;
                }));
        assertEquals(0, resolvers.get());
        assertEquals(0, traitCallbacks.get());
        assertEquals(0, ledger.activeChildren());
    }

    @Test void closedScopeCannotRunPlaceResolverCallbacks() {
        var ledger = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);
        ledger.finishScope();
        var resolvers = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IllegalStateException.class, () ->
                ledger.admitTyped(List.of(
                        new TaskAccessVerifier.TypedClaim(read(place), Types.Primitive.INT)),
                        (type, trait) -> false,
                        p -> {
                            resolvers.incrementAndGet();
                            return Types.Primitive.INT;
                        }));
        assertEquals(0, resolvers.get());
        assertEquals(0, ledger.activeClaims());
    }

    @Test void resolverCannotRetireOrCancelAnotherChildDuringAdmission() {
        var ledger = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);
        var live = admit(ledger, List.of(read(place)));
        assertThrows(IllegalStateException.class, () ->
                ledger.admitTyped(List.of(
                        new TaskAccessVerifier.TypedClaim(read(place), Types.Primitive.INT)),
                        (name, trait) -> false,
                        p -> {
                            ledger.quiescent(live);
                            return Types.Primitive.INT;
                        }));
        assertEquals(1, ledger.activeChildren());
        assertEquals(1, ledger.activeClaims());
        assertThrows(IllegalStateException.class, () ->
                ledger.admitTyped(List.of(
                        new TaskAccessVerifier.TypedClaim(read(place), Types.Primitive.INT)),
                        (name, trait) -> false,
                        p -> {
                            ledger.requestCancel(live);
                            return Types.Primitive.INT;
                        }));
        assertFalse(ledger.cancellationRequested(live));
        ledger.quiescent(live);
        ledger.finishScope();
    }

    @Test void resolverCannotSealScopeDuringAdmission() {
        var ledger = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);
        assertThrows(IllegalStateException.class, () ->
                ledger.admitTyped(List.of(
                        new TaskAccessVerifier.TypedClaim(read(place), Types.Primitive.INT)),
                        (name, trait) -> false,
                        p -> {
                            ledger.finishScope();
                            return Types.Primitive.INT;
                        }));
        assertEquals(0, ledger.activeClaims());
        var ticket = admit(ledger, List.of(read(place)));
        ledger.quiescent(ticket);
        ledger.finishScope();
    }

    @Test void nestedAdmissionByNominalProofIsRejectedAndGuardResets() {
        var ledger = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);
        var nominal = new Types.Named("DefinitelyNotBuiltIn", List.of());
        var claim = new TaskAccessVerifier.TypedClaim(read(place), nominal);
        assertThrows(IllegalStateException.class, () ->
                ledger.admitTyped(List.of(claim),
                        (name, trait) -> {
                            ledger.admitTyped(List.of(
                                    new TaskAccessVerifier.TypedClaim(
                                            read(place), Types.Primitive.INT)),
                                    (nestedName, nestedTrait) -> false,
                                    p -> Types.Primitive.INT);
                            return true;
                        },
                        p -> nominal));
        assertEquals(0, ledger.activeClaims());
        var accepted = admit(ledger, List.of(read(place)));
        ledger.quiescent(accepted);
        ledger.finishScope();
    }

    @Test void unprovenAllocationCannotSplitFieldsIntoDisjointWriters() {
        var root = new TaskAccessVerifier.Root("PROCESS", 901, 1, false);
        var columns = TaskAccessVerifier.Place.whole(root);
        var left = columns.field("position", true);
        var right = columns.field("velocity", true);
        assertTrue(TaskAccessVerifier.mayAlias(left, right),
                "layout flags cannot establish disjointness for an unproven allocation");
        var ledger = new TaskAccessVerifier();
        var first = admit(ledger, List.of(write(left)));
        assertThrows(IllegalArgumentException.class, () ->
                admit(ledger, List.of(write(right))));
        assertEquals(1, ledger.activeChildren());
        ledger.quiescent(first);
    }

    @Test void unprovenAllocationCannotSplitRangesIntoDisjointWriters() {
        var root = new TaskAccessVerifier.Root("PROCESS", 902, 1, false);
        var column = TaskAccessVerifier.Place.whole(root);
        var firstHalf = column.range(0, 64, true);
        var nextHalf = column.range(64, 128, true);
        assertTrue(TaskAccessVerifier.mayAlias(firstHalf, nextHalf));
        var ledger = new TaskAccessVerifier();
        var first = admit(ledger, List.of(write(firstHalf)));
        assertThrows(IllegalArgumentException.class, () ->
                admit(ledger, List.of(write(nextHalf))));
        ledger.quiescent(first);
    }

    @Test void provenAllocationStillAllowsIndependentFieldAndRangeWriters() {
        var root = new TaskAccessVerifier.Root("PROCESS", 903, 1, true);
        var whole = TaskAccessVerifier.Place.whole(root);
        var ledger = new TaskAccessVerifier();
        var first = admit(ledger, List.of(write(whole.field("position", true))));
        var second = admit(ledger, List.of(write(whole.field("velocity", true))));
        var x = admit(ledger, List.of(write(whole.field("segments", true).range(0, 64, true))));
        var y = admit(ledger, List.of(write(whole.field("segments", true).range(64, 128, true))));
        assertEquals(4, ledger.activeChildren());
        ledger.quiescent(first);
        ledger.quiescent(second);
        ledger.quiescent(x);
        ledger.quiescent(y);
        ledger.finishScope();
    }

    @Test void racingWritersNeverAcquireTwoLiveLoans() throws Exception {
        var verifier = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var ready = new java.util.concurrent.CountDownLatch(8);
        var start = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var admittedOrRejected = new java.util.concurrent.CountDownLatch(8);
        var winners = new java.util.concurrent.atomic.AtomicInteger();
        var losers = new java.util.concurrent.atomic.AtomicInteger();
        try {
            java.util.List<java.util.concurrent.Future<?>> outcomes = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                outcomes.add(pool.submit(() -> {
                    ready.countDown();
                    try {
                        if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new AssertionError("start gate timed out");
                        }
                        TaskAccessVerifier.Ticket ticket;
                        try {
                            ticket = admit(verifier, List.of(write(place)));
                            winners.incrementAndGet();
                        } catch (IllegalArgumentException rejected) {
                            losers.incrementAndGet();
                            admittedOrRejected.countDown();
                            return;
                        }
                        admittedOrRejected.countDown();
                        if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new AssertionError("release gate timed out");
                        }
                        verifier.quiescent(ticket);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                }));
            }
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            // Every contender has attempted admission; the winner is held
            // behind release so no rejected worker can acquire its loan.
            assertTrue(admittedOrRejected.await(10, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, winners.get());
            assertEquals(7, losers.get());
            release.countDown();
            for (var future : outcomes) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(0, verifier.activeChildren());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test void deterministicRangeAliasMatrixIsSymmetricAndConservative() {
        var random = new java.util.Random(0x71A5D00DL);
        var column = TaskAccessVerifier.Place.whole(WORLD).field("positions", true);
        var unproven = TaskAccessVerifier.Place.whole(WORLD).field("unproven", false);
        for (int i = 0; i < 2000; i++) {
            long a = random.nextInt(512);
            long b = random.nextInt(512);
            long ae = a + random.nextInt(64) + 1;
            long be = b + random.nextInt(64) + 1;
            boolean aProven = random.nextBoolean();
            boolean bProven = random.nextBoolean();
            var left = column.range(a, ae, aProven);
            var right = column.range(b, be, bProven);
            boolean provenDisjoint = aProven && bProven && (ae <= b || be <= a);
            assertEquals(!provenDisjoint, TaskAccessVerifier.mayAlias(left, right));
            assertEquals(TaskAccessVerifier.mayAlias(left, right),
                    TaskAccessVerifier.mayAlias(right, left));
            assertTrue(TaskAccessVerifier.mayAlias(
                    unproven.range(a, ae, true),
                    unproven.range(b, be, true)),
                    "unproven ancestor cannot be used to prove child disjointness");
        }
    }

    @Test void oversizedProvenanceAndProjectionPathsFailBeforeAdmission() {
        String oversized = "x".repeat(257);
        assertThrows(IllegalArgumentException.class, () ->
                new TaskAccessVerifier.Root(oversized, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () ->
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", oversized, 1, 1, true));
        assertThrows(IllegalArgumentException.class, () ->
                new TaskAccessVerifier.Field(oversized, true));
        var place = TaskAccessVerifier.Place.whole(WORLD);
        for (int i = 0; i < 64; i++) {
            place = place.field("inline", true);
        }
        var maxDepth = place;
        assertEquals(64, maxDepth.projections().size());
        assertThrows(IllegalArgumentException.class, () ->
                maxDepth.field("tooDeep", true));
        assertThrows(IllegalArgumentException.class, () ->
                maxDepth.range(0, 1, true));
        assertThrows(IllegalArgumentException.class, () ->
                new TaskAccessVerifier.Place(WORLD,
                        java.util.Collections.nCopies(65,
                                new TaskAccessVerifier.Field("x", true))));
    }

    @Test void ownerScopedProvenanceCannotBeRetypedAcrossTrustDomains() {
        var process = TaskAccessVerifier.Place.whole(WORLD);
        var actor = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-A:epoch-1", 31, 1, true));
        var untrusted = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("UNTRUSTED", "tenant-7:epoch-1", 31, 1, true));
        assertTrue(TaskAccessVerifier.mayAlias(process, actor));
        assertTrue(TaskAccessVerifier.mayAlias(actor, untrusted));
        assertTrue(TaskAccessVerifier.conflicts(read(actor), read(untrusted)));
        var ledger = new TaskAccessVerifier();
        var a = admit(ledger, List.of(read(actor)));
        assertThrows(IllegalArgumentException.class, () ->
                admit(ledger, List.of(read(untrusted))));
        ledger.quiescent(a);
        assertThrows(IllegalArgumentException.class, () ->
                admit(ledger, List.of(read(process))));
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
                admit(new TaskAccessVerifier(), java.util.Arrays.asList(
                        (TaskAccessVerifier.Claim) null)));
    }
}
