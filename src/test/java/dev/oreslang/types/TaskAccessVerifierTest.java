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

    private static final ThreadSafetyTraits.NominalProof CORE =
            new ThreadSafetyTraits.NominalProof() {
                @Override
                public boolean has(
                        Types.Named type,
                        ThreadSafetyTraits.Trait trait) {
                    return false;
                }

                @Override
                public boolean trustedIntrinsic(Types.Named type) {
                    return java.util.Set.of(
                            "Option", "Result", "Channel",
                            "IteratorResult", "OptionUnwrapError")
                            .contains(type.name());
                }
            };

    private static TaskAccessVerifier.Admission admit(
            TaskAccessVerifier verifier,
            List<TaskAccessVerifier.Claim> claims) {
        return verifier.admitScopedTyped(
                claims.stream()
                        .map(claim -> new TaskAccessVerifier.TypedClaim(
                                claim, Types.Primitive.INT))
                        .toList(),
                CORE,
                place -> Types.Primitive.INT);
    }

    private static void release(TaskAccessVerifier.Admission admission) {
        admission.completion().releaseAfterPhysicalQuiescence();
    }

    private static TaskAccessVerifier.Claim read(TaskAccessVerifier.Place place) {
        return new TaskAccessVerifier.Claim(place, TaskAccessVerifier.Mode.READ);
    }
    private static TaskAccessVerifier.Claim write(TaskAccessVerifier.Place place) {
        return new TaskAccessVerifier.Claim(place, TaskAccessVerifier.Mode.WRITE);
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
        release(a);
        release(b);
        var writer = admit(ledger, List.of(write(place)));
        assertFalse(ledger.parentMayAccess(read(place)));
        release(writer);
        ledger.seal();
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
        release(x);
        release(y);
        ledger.seal();
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
        admit(ledger, List.of(elementWriter));
        assertThrows(IllegalArgumentException.class,
                () -> admit(ledger, List.of(byteWriter)));
        assertThrows(NullPointerException.class, () ->
                new TaskAccessVerifier.Range(0, 10, true, null));

        // Equal-unit nonoverlapping element ranges still parallelize.
        var other = write(root.range(32, 64, true));
        assertFalse(TaskAccessVerifier.conflicts(elementWriter, other));
        assertNotNull(admit(ledger, List.of(other)));
    }

    @Test void parentCannotMoveOrMutateLiveBorrowedStorage() {
        var ledger = new TaskAccessVerifier();
        var root = TaskAccessVerifier.Place.whole(WORLD);
        var ticket = admit(ledger, List.of(read(root)));
        assertFalse(ledger.parentMayAccess(write(root)));
        assertTrue(ledger.parentMayAccess(read(root)));
        assertThrows(IllegalStateException.class, ledger::finishScope);
        ledger.requestCancel(ticket.ticket());
        assertTrue(ledger.cancellationRequested(ticket.ticket()));
        assertFalse(ledger.parentMayAccess(write(root)), "cancel is not physical quiescence");
        assertThrows(IllegalStateException.class, ledger::finishScope);
        release(ticket);
        assertTrue(ledger.parentMayAccess(write(root)));
        ledger.seal();
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

    @Test void allocationIdentityCannotChangeItsUniquenessProof() {
        var ledger = new TaskAccessVerifier();
        var proven = TaskAccessVerifier.Place.whole(WORLD);
        var admission = admit(ledger, List.of(read(proven)));
        release(admission);

        var downgraded = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root(
                        "PROCESS", "PROCESS", 31, 1, false));
        assertThrows(IllegalStateException.class, () ->
                ledger.admitScopedTyped(
                        List.of(new TaskAccessVerifier.TypedClaim(
                                read(downgraded), Types.Primitive.INT)),
                        CORE,
                        ignored -> Types.Primitive.INT));
        assertEquals(0, ledger.activeChildren());
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
        release(ticket);
        ledger.structuralBarrier(WORLD, 2);
        assertThrows(IllegalStateException.class,
                () -> admit(ledger, List.of(read(original))));
        assertNotNull(admit(ledger, List.of(read(TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 31, 2, true))))));
    }

    @Test void physicalCompletionIsOneShotAndForeignCancellationCannotReleaseLoans() {
        var one = new TaskAccessVerifier();
        var two = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);
        var admission = admit(one, List.of(write(place)));

        assertThrows(IllegalArgumentException.class,
                () -> two.requestCancel(admission.ticket()));
        assertFalse(one.parentMayAccess(read(place)));

        release(admission);
        assertThrows(IllegalStateException.class, () -> release(admission));
        assertThrows(IllegalArgumentException.class,
                () -> one.requestCancel(admission.ticket()));
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

    @Test void placeTypeProofCannotReenterOrMutateItsOwnLedger() {
        var ledger = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);
        var typed = new TaskAccessVerifier.TypedClaim(
                write(place), Types.Primitive.INT);

        assertThrows(IllegalStateException.class, () ->
                ledger.admitScopedTyped(
                        List.of(typed),
                        CORE,
                        ignored -> {
                            ledger.seal();
                            return Types.Primitive.INT;
                        }));
        assertEquals(0, ledger.activeChildren());
        assertEquals(0, ledger.activeClaims());

        // The failed callback did not commit the seal or any provenance.
        var admission = ledger.admitScopedTyped(
                List.of(typed), CORE, ignored -> Types.Primitive.INT);
        release(admission);
    }

    @Test void typedTaskAdmissionUsesSendForWriteAndSyncForRead() {
        var ledger = new TaskAccessVerifier();
        var unique = TaskAccessVerifier.Place.whole(WORLD);
        var channel = new Types.Named("Channel", List.of(Types.Primitive.INT));
        var syncRead = new TaskAccessVerifier.TypedClaim(read(unique), channel);
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admitScopedTyped(
                        List.of(syncRead), CORE, place -> channel));
        assertEquals(0, ledger.activeChildren());
        var sendWrite = new TaskAccessVerifier.TypedClaim(write(unique), channel);
        var ticket = ledger.admitScopedTyped(
                List.of(sendWrite), CORE, place -> channel);
        assertEquals(1, ledger.activeChildren());
        release(ticket);
        assertThrows(IllegalArgumentException.class,
                () -> ledger.admitScopedTyped(
                        List.of(new TaskAccessVerifier.TypedClaim(
                                read(unique), new Types.Generic("T"))),
                        CORE,
                        place -> new Types.Generic("T")));
        assertEquals(0, ledger.activeChildren());
    }

    @Test void scopedTypeCapabilitiesFailClosedIndependentlyOfRawSend() {
        var ledger = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);

        var readonly = new Types.Record(
                java.util.Map.of("value", Types.Primitive.INT), true);
        assertThrows(IllegalArgumentException.class, () ->
                ledger.admitScopedTyped(
                        List.of(new TaskAccessVerifier.TypedClaim(
                                write(place), readonly)),
                        CORE,
                        ignored -> readonly));

        assertThrows(IllegalArgumentException.class, () ->
                new TaskAccessVerifier.TypedClaim(
                        read(place),
                        new Types.Named("Option", List.of(
                                new Types.Borrow(Types.Primitive.INT, false)))));

        assertThrows(IllegalArgumentException.class, () ->
                new TaskAccessVerifier.TypedClaim(
                        read(place),
                        new Types.Dyn(new Types.Named(
                                "Worker", List.of()))));

        assertEquals(0, ledger.activeChildren());
    }

    @Test void typedAdmissionRejectsEveryClaimBeforeAnyReservation() {
        var ledger = new TaskAccessVerifier();
        var pos = TaskAccessVerifier.Place.whole(WORLD).field("position", true);
        var vel = TaskAccessVerifier.Place.whole(WORLD).field("velocity", true);
        assertThrows(IllegalArgumentException.class, () -> ledger.admitScopedTyped(List.of(
                new TaskAccessVerifier.TypedClaim(write(pos), Types.Primitive.INT),
                new TaskAccessVerifier.TypedClaim(read(vel),
                        new Types.Named("OpaqueHostHandle", List.of()))),
                (name, trait) -> false,
                place -> place.equals(pos)
                        ? Types.Primitive.INT
                        : new Types.Named("OpaqueHostHandle", List.of())));
        assertEquals(0, ledger.activeChildren());
    }

    @Test void distinctReadOnlyDomainsCannotShareOneTaskScope() {
        var verifier = new TaskAccessVerifier();
        var process = TaskAccessVerifier.Place.whole(WORLD);
        var actor = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("ACTOR_PRIVATE", "actor-50:epoch-1", 50, 1, true));
        var first = admit(verifier, List.of(read(process)));
        assertThrows(IllegalArgumentException.class,
                () -> admit(verifier, List.of(read(actor))));
        release(first);
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
        release(first);
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
        release(ticket);
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
        release(ticket);
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
        release(ticket);
        // A different numeric ID does not establish nonaliasing without
        // independently verified unique-allocation provenance.
        var uncertain = TaskAccessVerifier.Place.whole(UNKNOWN);
        var unknownTicket = admit(verifier, List.of(read(uncertain)));
        assertThrows(IllegalStateException.class,
                () -> verifier.structuralBarrier(WORLD, 2));
        release(unknownTicket);
        verifier.structuralBarrier(WORLD, 2);
        assertFalse(verifier.parentMayAccess(read(owner)));
    }

    @Test void closingScopeRevokesAllParentAuthorityAndRetiresProvenance() {
        var verifier = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);
        var ticket = admit(verifier, List.of(read(place)));
        release(ticket);
        assertTrue(verifier.parentMayAccess(write(place)));
        verifier.seal();
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
        release(a);
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
                        TaskAccessVerifier.Admission ticket;
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
                        release(ticket);
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
        release(a);
        assertThrows(IllegalArgumentException.class, () ->
                admit(ledger, List.of(read(process))));
    }

    @Test void sealedScopeStillWaitsForPhysicalCompletion() {
        var verifier = new TaskAccessVerifier();
        var place = TaskAccessVerifier.Place.whole(WORLD);
        var admission = admit(verifier, List.of(write(place)));
        verifier.seal();

        assertThrows(IllegalStateException.class, verifier::finishScope);
        assertFalse(verifier.parentMayAccess(read(place)),
                "sealed scope never grants fresh parent authority");

        release(admission);
        verifier.finishScope();
        assertEquals(0, verifier.activeChildren());
    }

    @Test void arbitraryTrustDomainAndUnsealedFinishFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                new TaskAccessVerifier.Root("GUEST", "tenant", 1, 1, true));
        var verifier = new TaskAccessVerifier();
        assertThrows(IllegalStateException.class, verifier::finishScope);
        verifier.seal();
        verifier.finishScope();
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
