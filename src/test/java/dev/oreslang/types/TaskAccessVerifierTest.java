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

    @Test void byteAndElementRangesDoNotManufactureDisjointness() {
        var root = TaskAccessVerifier.Place.whole(WORLD).field("position", true);
        var elementWriter = write(root.range(0, 32, true));
        var byteWriter = write(root.byteRange(100, 200, true));
        // The numbers do not overlap, but one is an index and the other is a
        // byte offset. Only a trusted element-width/layout conversion could
        // prove physical separation. Different units must alias by default.
        assertTrue(TaskAccessVerifier.mayAlias(
                elementWriter.place(), byteWriter.place()));
        assertTrue(TaskAccessVerifier.conflicts(elementWriter, byteWriter));
        var ledger = new TaskAccessVerifier();
        ledger.admit(List.of(elementWriter));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admit(List.of(byteWriter)));
        assertThrows(NullPointerException.class, () ->
                new TaskAccessVerifier.Range(0, 10, true, null));

        // Equal-unit nonoverlapping element ranges still parallelize.
        var other = write(root.range(32, 64, true));
        assertFalse(TaskAccessVerifier.conflicts(elementWriter, other));
        assertNotNull(ledger.admit(List.of(other)));
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
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-33:epoch-1", 33, 1, true));
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

    @Test void unprovenIntermediateFieldCannotCreateDisjointNestedLoans() {
        var root = TaskAccessVerifier.Place.whole(WORLD);
        var uncertainParent = root.field("opaque_union", false);
        var left = uncertainParent.field("x", true);
        var right = uncertainParent.field("y", true);
        assertTrue(TaskAccessVerifier.mayAlias(left, right));
        var ledger = new TaskAccessVerifier();
        ledger.admit(List.of(write(left)));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admit(List.of(write(right))));
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

    @Test void distinctReadOnlyDomainsCannotShareOneTaskScope() {
        var verifier = new TaskAccessVerifier();
        var process = TaskAccessVerifier.Place.whole(WORLD);
        var actor = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-50:epoch-1", 50, 1, true));
        var first = verifier.admit(List.of(read(process)));
        assertThrows(IllegalArgumentException.class,
                () -> verifier.admit(List.of(read(actor))));
        verifier.quiescent(first);
        assertThrows(IllegalArgumentException.class,
                () -> verifier.admit(List.of(read(actor))));
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
                () -> verifier.admit(List.of(read(process), read(actor))));
        assertEquals(0, verifier.activeChildren());
        // Failed admission must not bind a partially selected domain.
        assertNotNull(verifier.admit(List.of(read(actor))));
        assertFalse(verifier.parentMayAccess(read(process)));
    }

    @Test void privateActorsWithTheSameDomainCategoryCannotShareClaims() {
        var actorA = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-A:epoch-1", 1, 1, true));
        var actorB = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-B:epoch-1", 2, 1, true));
        var ledger = new TaskAccessVerifier();
        var first = ledger.admit(List.of(read(actorA)));
        assertTrue(TaskAccessVerifier.mayAlias(actorA, actorB));
        assertTrue(TaskAccessVerifier.conflicts(read(actorA), read(actorB)),
                "READ/READ does not authorize crossing actor heaps");
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admit(List.of(read(actorB))));
        assertFalse(ledger.parentMayAccess(read(actorB)));
        ledger.quiescent(first);
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admit(List.of(read(actorB))));
    }

    @Test void privateActorIdentityIncludesEpochAndCannotBeReused() {
        var actorAOld = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-A:epoch-1", 1, 1, true));
        var actorANew = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-A:epoch-2", 1, 1, true));
        var ledger = new TaskAccessVerifier();
        var ticket = ledger.admit(List.of(read(actorAOld)));
        ledger.quiescent(ticket);
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admit(List.of(read(actorANew))));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.structuralBarrier(actorANew.root(), 2));
    }

    @Test void validatedClaimsWithinOnePrivateActorCanStillCoexist() {
        var a = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-A:epoch-1", 1, 1, true));
        var b = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-A:epoch-1", 2, 1, true));
        var ledger = new TaskAccessVerifier();
        assertNotNull(ledger.admit(List.of(read(a))));
        assertNotNull(ledger.admit(List.of(read(b))));
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
        var ticket = verifier.admit(List.of(read(before)));
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
        var ticket = verifier.admit(List.of(read(owner)));
        verifier.quiescent(ticket);
        // A different numeric ID does not establish nonaliasing without
        // independently verified unique-allocation provenance.
        var uncertain = TaskAccessVerifier.Place.whole(UNKNOWN);
        var unknownTicket = verifier.admit(List.of(read(uncertain)));
        assertThrows(IllegalStateException.class,
                () -> verifier.structuralBarrier(WORLD, 2));
        verifier.quiescent(unknownTicket);
        verifier.structuralBarrier(WORLD, 2);
        assertFalse(verifier.parentMayAccess(read(owner)));
    }

    @Test void closingScopeRevokesAllParentAuthorityAndRetiresProvenance() {
        var verifier = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);
        var ticket = verifier.admit(List.of(read(place)));
        verifier.quiescent(ticket);
        assertTrue(verifier.parentMayAccess(write(place)));
        verifier.finishScope();
        assertFalse(verifier.parentMayAccess(read(place)));
        assertFalse(verifier.parentMayAccess(write(place)));
        assertEquals(0, verifier.activeChildren());
        assertThrows(IllegalStateException.class,
                () -> verifier.admit(List.of(read(place))));
        assertThrows(IllegalStateException.class,
                () -> verifier.structuralBarrier(WORLD, 2));
    }

    @Test void admissionCapacityErrorsAreAtomic() {
        var verifier = new TaskAccessVerifier();
        var requested = java.util.Collections.nCopies(
                1025, read(TaskAccessVerifier.Place.whole(WORLD)));
        assertThrows(IllegalStateException.class, () -> verifier.admit(requested));
        assertEquals(0, verifier.activeChildren());
        var a = verifier.admit(List.of(read(TaskAccessVerifier.Place.whole(WORLD))));
        assertNotNull(a);
        verifier.quiescent(a);
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
                            ticket = verifier.admit(List.of(write(place)));
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
        var a = ledger.admit(List.of(read(actor)));
        assertThrows(IllegalArgumentException.class, () ->
                ledger.admit(List.of(read(untrusted))));
        ledger.quiescent(a);
        assertThrows(IllegalArgumentException.class, () ->
                ledger.admit(List.of(read(process))));
    }

    @Test
    void sameCoordinatesCannotForgeExactTicketIdentity() throws Exception {
        var verifier = new TaskAccessVerifier();
        var exact = verifier.admit(List.of(
                read(TaskAccessVerifier.Place.whole(WORLD))));

        var epochField = TaskAccessVerifier.Ticket.class
                .getDeclaredField("scopeEpoch");
        var idField = TaskAccessVerifier.Ticket.class
                .getDeclaredField("id");
        epochField.setAccessible(true);
        idField.setAccessible(true);

        Object exactEpoch = epochField.get(exact);
        long exactId = idField.getLong(exact);
        var constructor = TaskAccessVerifier.Ticket.class
                .getDeclaredConstructor(Object.class, long.class);
        constructor.setAccessible(true);
        var forged = constructor.newInstance(exactEpoch, exactId);

        assertNotSame(exact, forged);
        assertThrows(IllegalArgumentException.class,
                () -> verifier.requestCancel(forged),
                "same verifier epoch/id coordinates must not forge ticket identity");
        assertEquals(1, verifier.activeChildren(),
                "forged ticket must not mutate the live exact loan");

        verifier.quiescent(exact);
        assertEquals(0, verifier.activeChildren());
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
