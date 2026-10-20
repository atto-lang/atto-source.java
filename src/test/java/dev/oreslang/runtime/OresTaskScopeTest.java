package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresTaskScopeTest {

    @Test
    void cancellationDoesNotReleaseScopeUntilRunningCarrierUnwinds() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean physicallyExited = new AtomicBoolean();

            OresFuture<Integer> child = scope.fork(resume -> {
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } finally {
                    physicallyExited.set(true);
                }
                return OresScheduler.done(42);
            });

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> drained = scope.cancelAndQuiesce();

            assertTrue(child.isCancelled(),
                    "logical cancellation should become visible immediately");
            assertFalse(drained.isDone(),
                    "scope must not reclaim while the cancelled carrier still executes");
            assertFalse(physicallyExited.get());

            release.countDown();
            assertNull(drained.get(5, TimeUnit.SECONDS));
            assertTrue(physicallyExited.get());
            assertEquals(0, scope.activeChildren());
        }
    }

    @Test
    void normalQuiescenceIsAwaitableOnOneCarrierWithoutDeadlock() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            AtomicInteger parentPc = new AtomicInteger();
            AtomicBoolean childRan = new AtomicBoolean();

            OresFuture<Integer> parent = scheduler.start(resume -> {
                if (parentPc.getAndIncrement() == 0) {
                    scope.fork(childResume -> {
                        childRan.set(true);
                        return OresScheduler.done(41);
                    });
                    return OresScheduler.await(scope.quiesce());
                }

                assertNull(resume.failure());
                assertNull(resume.value());
                assertTrue(childRan.get());
                return OresScheduler.done(42);
            });

            assertEquals(42, parent.get(5, TimeUnit.SECONDS));
            assertEquals(2, parentPc.get());
            assertEquals(0, scope.activeChildren());
        }
    }

    @Test
    void normalQuiesceDoesNotCancelExistingChild() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            CountDownLatch release = new CountDownLatch(1);

            OresFuture<Integer> child = scope.fork(resume -> {
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(7);
            });

            OresFuture<Void> drained = scope.quiesce();
            assertFalse(child.isCancelled());
            assertFalse(drained.isDone());

            release.countDown();
            assertEquals(7, child.get(5, TimeUnit.SECONDS));
            assertNull(drained.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationCanEscalateAPreviouslyNormalQuiesce() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            OresFuture<Integer> child = scope.fork(resume -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(1);
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            OresFuture<Void> first = scope.quiesce();
            OresFuture<Void> second = scope.cancelAndQuiesce();
            assertSame(first, second);
            assertTrue(child.isCancelled());
            assertFalse(first.isDone());

            release.countDown();
            assertNull(first.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void sealedScopeRejectsNewChildrenAndBarrierIsIdempotent() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);

            OresFuture<Void> first = scope.quiesce();
            OresFuture<Void> second = scope.quiesce();

            assertSame(first, second);
            assertNull(first.get(5, TimeUnit.SECONDS));
            assertFalse(scope.isOpen());
            assertThrows(IllegalStateException.class,
                    () -> scope.fork(resume -> OresScheduler.done(1)));
        }
    }

    @Test
    void parentCancellationAutomaticallyCancelsAndDrainsOwnedScopes() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            CountDownLatch childEntered = new CountDownLatch(1);
            CountDownLatch releaseChild = new CountDownLatch(1);
            CountDownLatch parentQuiescent = new CountDownLatch(1);
            OresFuture<Integer> never = new OresFuture<>();
            AtomicReference<OresFuture<Integer>> childRef = new AtomicReference<>();
            AtomicInteger parentPc = new AtomicInteger();

            OresFuture<Integer> parent = scheduler.start(resume -> {
                if (parentPc.getAndIncrement() != 0) {
                    throw new AssertionError("cancelled parent must never resume");
                }

                OresTaskScope scope = OresTaskScope.openCurrent();
                childRef.set(scope.fork(childResume -> {
                    childEntered.countDown();
                    assertTrue(releaseChild.await(5, TimeUnit.SECONDS));
                    return OresScheduler.done(7);
                }));
                return OresScheduler.await(never);
            }, parentQuiescent::countDown);

            assertTrue(childEntered.await(5, TimeUnit.SECONDS));
            assertTrue(parent.cancel(false));
            assertTrue(parent.isCancelled());

            OresFuture<Integer> child = childRef.get();
            assertNotNull(child);
            assertTrue(child.isCancelled(),
                    "parent cancellation must propagate into its structured children");
            assertFalse(parentQuiescent.await(100, TimeUnit.MILLISECONDS),
                    "parent memory must remain live while a cancelled child carrier is still running");

            releaseChild.countDown();
            assertTrue(parentQuiescent.await(5, TimeUnit.SECONDS),
                    "parent may retire only after the structured child is physically quiescent");
            assertEquals(1, parentPc.get());
        }
    }

    @Test
    void openCurrentScopeRequiresAnExecutingTask() {
        assertThrows(IllegalStateException.class, OresTaskScope::openCurrent);
    }


    @Test
    void terminalAccountingBypassesBlockingOrdinarySubscriber() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            CountDownLatch childEntered = new CountDownLatch(1);
            CountDownLatch releaseChild = new CountDownLatch(1);
            CountDownLatch subscriberEntered = new CountDownLatch(1);
            CountDownLatch releaseSubscriber = new CountDownLatch(1);

            OresFuture<Integer> child = scope.fork(resume -> {
                childEntered.countDown();
                assertTrue(releaseChild.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(42);
            });
            assertTrue(childEntered.await(5, TimeUnit.SECONDS));

            child.whenCompleteRuntime((value, failure) -> {
                subscriberEntered.countDown();
                try {
                    releaseSubscriber.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });

            OresFuture<Void> drained = scope.quiesce();
            releaseChild.countDown();

            assertTrue(subscriberEntered.await(5, TimeUnit.SECONDS));
            assertTrue(drained.isDone(),
                    "scope terminal accounting must run before ordinary Future subscribers");
            assertNull(drained.get(5, TimeUnit.SECONDS));

            releaseSubscriber.countDown();
            assertEquals(42, child.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void structuredCancellationRevokesAllChildrenBeforeSubscriberPublication() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(3)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            CountDownLatch entered = new CountDownLatch(3);
            CountDownLatch releaseTasks = new CountDownLatch(1);
            CountDownLatch subscriberEntered = new CountDownLatch(3);
            CountDownLatch releaseSubscribers = new CountDownLatch(1);
            java.util.List<OresFuture<Integer>> children = new java.util.ArrayList<>();

            for (int i = 0; i < 3; i++) {
                OresFuture<Integer> child = scope.fork(resume -> {
                    entered.countDown();
                    assertTrue(releaseTasks.await(5, TimeUnit.SECONDS));
                    return OresScheduler.done(1);
                });
                child.whenCompleteRuntime((value, failure) -> {
                    subscriberEntered.countDown();
                    try {
                        releaseSubscribers.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                });
                children.add(child);
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            AtomicReference<OresFuture<Void>> drainedRef = new AtomicReference<>();
            Thread canceller = Thread.ofPlatform().start(
                    () -> drainedRef.set(scope.cancelAndQuiesce()));

            assertTrue(subscriberEntered.await(5, TimeUnit.SECONDS),
                    "all ordinary subscribers should publish independently after revocation");
            for (OresFuture<Integer> child : children) {
                assertTrue(child.isCancelled(),
                        "all child cancellation claims must win before subscriber publication");
                assertNotNull(child.runtimeTerminalStateOrNull(),
                        "all child revocation hooks must finish before the first subscriber can block");
            }

            // Publication now runs off the caller/parent carrier. A blocking
            // ordinary subscriber must not keep cancelAndQuiesce() itself stuck
            // after every child authority has already been revoked.
            canceller.join(5000);
            assertFalse(canceller.isAlive(),
                    "scope cancellation caller remained pinned behind an ordinary subscriber");

            OresFuture<Void> drained = drainedRef.get();
            assertNotNull(drained);
            assertFalse(drained.isDone(),
                    "physical child carriers are still executing after logical cancellation");

            releaseSubscribers.countDown();
            releaseTasks.countDown();
            assertNull(drained.get(5, TimeUnit.SECONDS));
            assertEquals(0, scope.activeChildren());
        }
    }


    @Test
    void parentCancellationTransitivelyDrainsNestedGrandchildScope() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(3)) {
            CountDownLatch grandchildEntered = new CountDownLatch(1);
            CountDownLatch releaseGrandchild = new CountDownLatch(1);
            CountDownLatch parentQuiescent = new CountDownLatch(1);
            OresFuture<Integer> neverParent = new OresFuture<>();
            OresFuture<Integer> neverChild = new OresFuture<>();
            AtomicReference<OresFuture<Integer>> childRef = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> grandchildRef = new AtomicReference<>();

            OresFuture<Integer> parent = scheduler.start(resume -> {
                OresTaskScope outer = OresTaskScope.openCurrent();
                childRef.set(outer.fork(childResume -> {
                    OresTaskScope inner = OresTaskScope.openCurrent();
                    grandchildRef.set(inner.fork(grandchildResume -> {
                        grandchildEntered.countDown();
                        assertTrue(releaseGrandchild.await(5, TimeUnit.SECONDS));
                        return OresScheduler.done(99);
                    }));
                    return OresScheduler.await(neverChild);
                }));
                return OresScheduler.await(neverParent);
            }, parentQuiescent::countDown);

            assertTrue(grandchildEntered.await(5, TimeUnit.SECONDS));
            assertTrue(parent.cancel(false));

            OresFuture<Integer> child = childRef.get();
            OresFuture<Integer> grandchild = grandchildRef.get();
            assertNotNull(child);
            assertNotNull(grandchild);
            assertTrue(child.isCancelled(),
                    "outer structured child must be cancelled with its parent");
            assertTrue(grandchild.isCancelled(),
                    "nested structured grandchild must be cancelled transitively");
            assertFalse(parentQuiescent.await(100, TimeUnit.MILLISECONDS),
                    "parent must remain physically live while a nested grandchild carrier executes");

            releaseGrandchild.countDown();

            assertTrue(parentQuiescent.await(5, TimeUnit.SECONDS),
                    "parent may retire only after all nested structured scopes physically drain");
            assertTrue(parent.isCancelled());
        }
    }


    @Test
    void ownerFinalizationFailureFailsDrainInsteadOfReportingSuccess() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            RuntimeException ownerFailure =
                    new RuntimeException("owner deregistration failed");
            OresTaskScope scope = new OresTaskScope(
                    scheduler,
                    ignored -> { },
                    ignored -> { throw ownerFailure; });

            OresFuture<Void> drained = scope.quiesce();

            java.util.concurrent.ExecutionException observed =
                    assertThrows(
                            java.util.concurrent.ExecutionException.class,
                            () -> drained.get(5, TimeUnit.SECONDS));
            assertTrue(
                    observed.getCause() instanceof StructuredTaskScopePoisonException,
                    "owner finalization failure must become structured scope poison");
            assertSame(ownerFailure, observed.getCause().getCause());
            assertTrue(scope.isPoisoned());
            assertFalse(TrapFailurePolicy.isTrappable(
                    observed.getCause(), RuntimeException.class));
            assertTrue(drained.isCompletedExceptionally());
            assertFalse(drained.isCancelled());
        }
    }


    @Test
    void scopedReadClaimRemainsLiveUntilCancelledCarrierPhysicallyQuiesces()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            OresTaskScope.ScopedReadClaim claim =
                    new OresTaskScope.ScopedReadClaim("view", 7L, 3L, "$");

            OresFuture<Integer> child = scope.fork(resume -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(1);
            }, java.util.List.of(claim));

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, scope.activeScopedReadClaims());
            assertEquals(1, scope.activeScopedReadRegions());
            assertTrue(scope.hasScopedReadLease(7L, 3L));

            OresFuture<Void> drained = scope.cancelAndQuiesce();
            assertTrue(child.isCancelled());
            assertEquals(1, scope.activeScopedReadClaims(),
                    "logical cancellation must not release region authority");
            assertTrue(scope.hasScopedReadLease(7L, 3L));
            assertFalse(drained.isDone());

            release.countDown();
            assertNull(drained.get(5, TimeUnit.SECONDS));
            assertEquals(0, scope.activeScopedReadClaims());
            assertEquals(0, scope.activeScopedReadRegions());
            assertFalse(scope.hasScopedReadLease(7L, 3L));
        }
    }

    @Test
    void conflictingScopedReadGenerationIsRejectedWithoutAuthorityMutation()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);

            OresFuture<Integer> first = scope.fork(resume -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(1);
            }, java.util.List.of(
                    new OresTaskScope.ScopedReadClaim("first", 11L, 4L, "$")));

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            IllegalStateException stale = assertThrows(
                    IllegalStateException.class,
                    () -> scope.fork(
                            resume -> OresScheduler.done(2),
                            java.util.List.of(
                                    new OresTaskScope.ScopedReadClaim(
                                            "stale", 11L, 5L, "$"))));
            assertTrue(stale.getMessage().contains("generation"));
            assertEquals(1, scope.activeChildren());
            assertEquals(1, scope.activeScopedReadClaims());
            assertEquals(1, scope.activeScopedReadRegions());
            assertTrue(scope.hasScopedReadLease(11L, 4L));
            assertFalse(scope.hasScopedReadLease(11L, 5L));

            OresFuture<Void> drained = scope.quiesce();
            release.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
            assertNull(drained.get(5, TimeUnit.SECONDS));
            assertEquals(0, scope.activeScopedReadClaims());
        }
    }

    @Test
    void siblingScopedReadersShareOneRegionGenerationAndRetireIndependently()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);

            OresFuture<Integer> first = scope.fork(resume -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(1);
            }, java.util.List.of(
                    new OresTaskScope.ScopedReadClaim("left", 23L, 0L, "$")));
            OresFuture<Integer> second = scope.fork(resume -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(2);
            }, java.util.List.of(
                    new OresTaskScope.ScopedReadClaim("right", 23L, 0L, "$")));

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(2, scope.activeScopedReadClaims());
            assertEquals(1, scope.activeScopedReadRegions());
            assertTrue(scope.hasScopedReadLease(23L, 0L));

            OresFuture<Void> drained = scope.quiesce();
            release.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
            assertEquals(2, second.get(5, TimeUnit.SECONDS));
            assertNull(drained.get(5, TimeUnit.SECONDS));
            assertEquals(0, scope.activeScopedReadClaims());
            assertEquals(0, scope.activeScopedReadRegions());
        }
    }

    @Test
    void failedSchedulerAdmissionRollsBackScopedReadReservation() {
        OresScheduler scheduler = new OresScheduler(1);
        OresTaskScope scope = new OresTaskScope(scheduler);
        scheduler.close();

        OresTaskScope.ScopedReadClaim claim =
                new OresTaskScope.ScopedReadClaim("view", 31L, 2L, "$");
        assertThrows(
                java.util.concurrent.RejectedExecutionException.class,
                () -> scope.fork(
                        resume -> OresScheduler.done(1),
                        java.util.List.of(claim)));
        assertEquals(0, scope.activeChildren());
        assertEquals(0, scope.activeScopedReadClaims());
        assertEquals(0, scope.activeScopedReadRegions());
        assertFalse(claim.issued(),
                "failed scheduler admission must roll back one-shot issuance");
        assertFalse(scope.hasScopedReadLease(31L, 2L));
    }

    @Test
    void duplicateBindingAndProjectedClaimsFailBeforeReservation() {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);

            assertThrows(
                    IllegalArgumentException.class,
                    () -> scope.fork(
                            resume -> OresScheduler.done(1),
                            java.util.List.of(
                                    new OresTaskScope.ScopedReadClaim(
                                            "view", 41L, 0L, "$"),
                                    new OresTaskScope.ScopedReadClaim(
                                            "view", 42L, 0L, "$"))));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new OresTaskScope.ScopedReadClaim(
                            "projection", 41L, 0L, "$.field"));

            assertEquals(0, scope.activeChildren());
            assertEquals(0, scope.activeScopedReadClaims());
            assertEquals(0, scope.activeScopedReadRegions());
        }
    }


    @Test
    void retiredSiblingClaimCannotRideAnotherReadersRegionLease()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch releaseLeft = new CountDownLatch(1);
            CountDownLatch releaseRight = new CountDownLatch(1);

            OresTaskScope.ScopedReadClaim leftClaim =
                    new OresTaskScope.ScopedReadClaim("view", 53L, 7L, "$");
            OresTaskScope.ScopedReadClaim rightClaim =
                    new OresTaskScope.ScopedReadClaim("view", 53L, 7L, "$");

            // The two claims intentionally carry identical metadata, but
            // lifetime authority is claim-object identity.
            assertNotSame(leftClaim, rightClaim);
            assertEquals(leftClaim.bindingName(), rightClaim.bindingName());
            assertEquals(leftClaim.regionId(), rightClaim.regionId());
            assertEquals(leftClaim.generation(), rightClaim.generation());
            assertEquals(leftClaim.accessPath(), rightClaim.accessPath());

            OresFuture<Integer> left = scope.fork(resume -> {
                entered.countDown();
                assertTrue(releaseLeft.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(1);
            }, java.util.List.of(leftClaim));
            OresFuture<Integer> right = scope.fork(resume -> {
                entered.countDown();
                assertTrue(releaseRight.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(2);
            }, java.util.List.of(rightClaim));

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(scope.hasScopedReadLease(leftClaim));
            assertTrue(scope.hasScopedReadLease(rightClaim));
            assertTrue(scope.hasScopedReadLease(53L, 7L));

            OresFuture<Void> drained = scope.quiesce();
            releaseLeft.countDown();
            assertEquals(1, left.get(5, TimeUnit.SECONDS));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (scope.hasScopedReadLease(leftClaim)
                    && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }

            assertFalse(scope.hasScopedReadLease(leftClaim),
                    "retired child claim must become invalid by identity");
            assertTrue(scope.hasScopedReadLease(rightClaim),
                    "sibling's exact claim remains live");
            assertTrue(scope.hasScopedReadLease(53L, 7L),
                    "aggregate region remains live because sibling still reads");
            assertFalse(drained.isDone());

            releaseRight.countDown();
            assertEquals(2, right.get(5, TimeUnit.SECONDS));
            assertNull(drained.get(5, TimeUnit.SECONDS));
            assertFalse(scope.hasScopedReadLease(rightClaim));
            assertFalse(scope.hasScopedReadLease(53L, 7L));
        }
    }

    @Test
    void oneClaimIdentityCannotBeOwnedByTwoLiveChildren() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            OresTaskScope.ScopedReadClaim claim =
                    new OresTaskScope.ScopedReadClaim("view", 61L, 0L, "$");

            OresFuture<Integer> first = scope.fork(resume -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(1);
            }, java.util.List.of(claim));

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            IllegalStateException duplicate = assertThrows(
                    IllegalStateException.class,
                    () -> scope.fork(
                            resume -> OresScheduler.done(2),
                            java.util.List.of(claim)));
            assertTrue(duplicate.getMessage().contains("already issued"));
            assertEquals(1, scope.activeChildren());
            assertEquals(1, scope.activeScopedReadClaims());
            assertTrue(scope.hasScopedReadLease(claim));

            OresFuture<Void> drained = scope.quiesce();
            release.countDown();
            assertEquals(1, first.get(5, TimeUnit.SECONDS));
            assertNull(drained.get(5, TimeUnit.SECONDS));
            assertFalse(scope.hasScopedReadLease(claim));
        }
    }


    @Test
    void retiredClaimIdentityCannotBeReissuedInStillOpenScope()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            OresTaskScope.ScopedReadClaim claim =
                    new OresTaskScope.ScopedReadClaim("view", 67L, 0L, "$");

            OresFuture<Integer> first = scope.fork(
                    resume -> OresScheduler.done(1),
                    java.util.List.of(claim));
            assertEquals(1, first.get(5, TimeUnit.SECONDS));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (scope.hasScopedReadLease(claim)
                    && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }

            assertTrue(scope.isOpen(),
                    "child retirement alone must not seal the lexical scope");
            assertFalse(scope.hasScopedReadLease(claim));
            assertTrue(claim.issued(),
                    "retired successful claim identity stays consumed");

            IllegalStateException reused = assertThrows(
                    IllegalStateException.class,
                    () -> scope.fork(
                            resume -> OresScheduler.done(2),
                            java.util.List.of(claim)));
            assertTrue(reused.getMessage().contains("already issued"));
            assertEquals(0, scope.activeChildren());
            assertEquals(0, scope.activeScopedReadClaims());
            assertTrue(claim.issued());

            assertNull(scope.quiesce().get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void mixedFreshAndPreviouslyIssuedClaimsRejectWithoutPartialReservation()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            OresTaskScope.ScopedReadClaim used =
                    new OresTaskScope.ScopedReadClaim("used", 71L, 0L, "$");

            OresFuture<Integer> first = scope.fork(
                    resume -> OresScheduler.done(1),
                    java.util.List.of(used));
            assertEquals(1, first.get(5, TimeUnit.SECONDS));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (scope.hasScopedReadLease(used)
                    && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }
            assertFalse(scope.hasScopedReadLease(used));

            OresTaskScope.ScopedReadClaim fresh =
                    new OresTaskScope.ScopedReadClaim("fresh", 72L, 0L, "$");
            assertThrows(
                    IllegalStateException.class,
                    () -> scope.fork(
                            resume -> OresScheduler.done(2),
                            java.util.List.of(fresh, used)));

            assertFalse(scope.hasScopedReadLease(fresh));
            assertFalse(scope.hasScopedReadLease(72L, 0L));
            assertEquals(0, scope.activeScopedReadClaims());
            assertFalse(fresh.issued(),
                    "fresh claim must remain unissued after atomic rejection");
            assertTrue(used.issued(),
                    "previously successful claim remains consumed");

            assertNull(scope.quiesce().get(5, TimeUnit.SECONDS));
        }
    }


    @Test
    void sameClaimIdentityCannotRaceIntoTwoDifferentScopes()
            throws Exception {
        try (OresScheduler leftScheduler = new OresScheduler(1);
             OresScheduler rightScheduler = new OresScheduler(1)) {
            OresTaskScope leftScope = new OresTaskScope(leftScheduler);
            OresTaskScope rightScope = new OresTaskScope(rightScheduler);
            OresTaskScope.ScopedReadClaim claim =
                    new OresTaskScope.ScopedReadClaim("view", 79L, 1L, "$");

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger admitted = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            Runnable leftAttempt = () -> {
                ready.countDown();
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    leftScope.fork(
                            resume -> OresScheduler.done(1),
                            java.util.List.of(claim));
                    admitted.incrementAndGet();
                } catch (IllegalStateException expected) {
                    rejected.incrementAndGet();
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            };
            Runnable rightAttempt = () -> {
                ready.countDown();
                try {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    rightScope.fork(
                            resume -> OresScheduler.done(2),
                            java.util.List.of(claim));
                    admitted.incrementAndGet();
                } catch (IllegalStateException expected) {
                    rejected.incrementAndGet();
                } catch (Throwable failure) {
                    unexpected.compareAndSet(null, failure);
                }
            };

            Thread left = Thread.ofVirtual().start(leftAttempt);
            Thread right = Thread.ofVirtual().start(rightAttempt);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            left.join();
            right.join();

            assertNull(unexpected.get());
            assertEquals(1, admitted.get(),
                    "exactly one scope may issue one claim identity");
            assertEquals(1, rejected.get());
            assertTrue(claim.issued());

            assertNull(leftScope.quiesce().get(5, TimeUnit.SECONDS));
            assertNull(rightScope.quiesce().get(5, TimeUnit.SECONDS));
        }
    }


    @Test
    void scopedReadRetirementCorruptionPoisonsDrainAndPinsChild()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            OresTaskScope.ScopedReadClaim claim =
                    new OresTaskScope.ScopedReadClaim("view", 83L, 0L, "$");

            OresFuture<Integer> child = scope.fork(resume -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(1);
            }, java.util.List.of(claim));

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(scope.hasScopedReadLease(claim));
            corruptScopedReadRegionForTest(scope, 83L);

            OresFuture<Void> drained = scope.quiesce();
            release.countDown();
            assertEquals(1, child.get(5, TimeUnit.SECONDS));

            java.util.concurrent.ExecutionException poisoned = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> drained.get(5, TimeUnit.SECONDS));
            assertInstanceOf(
                    StructuredTaskScopePoisonException.class,
                    poisoned.getCause());
            assertTrue(poisoned.getCause().getMessage().contains("accounting"));
            assertNotNull(poisoned.getCause().getCause(),
                    "poison must preserve the original accounting failure");
            assertTrue(scope.isPoisoned());
            assertEquals(1, scope.activeChildren(),
                    "corrupt retirement must pin the child in the join set");
            assertEquals(1, scope.activeScopedReadClaims(),
                    "corrupt retirement must not claim authority was released");
            assertFalse(scope.hasScopedReadLease(claim),
                    "corrupted region table cannot validate the ticket");
        }
    }


    @Test
    void poisonedDrainCancelsSiblingsButWaitsForTheirPhysicalQuiescence()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            AtomicBoolean ownerDeregistered = new AtomicBoolean();
            OresTaskScope scope = new OresTaskScope(
                    scheduler,
                    ignored -> { },
                    ignored -> ownerDeregistered.set(true));
            CountDownLatch entered = new CountDownLatch(2);
            CountDownLatch releaseCorrupt = new CountDownLatch(1);
            CountDownLatch releaseSibling = new CountDownLatch(1);

            OresTaskScope.ScopedReadClaim corruptClaim =
                    new OresTaskScope.ScopedReadClaim("corrupt", 101L, 0L, "$");
            OresTaskScope.ScopedReadClaim siblingClaim =
                    new OresTaskScope.ScopedReadClaim("sibling", 102L, 0L, "$");

            OresFuture<Integer> corrupt = scope.fork(resume -> {
                entered.countDown();
                assertTrue(releaseCorrupt.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(1);
            }, java.util.List.of(corruptClaim));
            OresFuture<Integer> sibling = scope.fork(resume -> {
                entered.countDown();
                assertTrue(releaseSibling.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(2);
            }, java.util.List.of(siblingClaim));

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> drained = scope.quiesce();

            corruptScopedReadRegionForTest(scope, 101L);
            releaseCorrupt.countDown();
            assertEquals(1, corrupt.get(5, TimeUnit.SECONDS));

            long cancellationDeadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!sibling.isCancelled()
                    && System.nanoTime() < cancellationDeadline) {
                Thread.sleep(1L);
            }

            assertTrue(scope.isPoisoned());
            assertTrue(sibling.isCancelled(),
                    "poison must revoke sibling Task authority");
            assertFalse(drained.isDone(),
                    "poison failure must remain hidden while a sibling carrier is running");
            assertFalse(ownerDeregistered.get(),
                    "owner scope must remain registered until physical quiescence");

            releaseSibling.countDown();

            java.util.concurrent.ExecutionException poisoned = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> drained.get(5, TimeUnit.SECONDS));
            assertInstanceOf(
                    StructuredTaskScopePoisonException.class,
                    poisoned.getCause());
            assertTrue(poisoned.getCause().getMessage().contains("accounting"));
            assertNotNull(poisoned.getCause().getCause(),
                    "poison must preserve the original accounting failure");
            assertTrue(ownerDeregistered.get(),
                    "poisoned scope may deregister only after every child carrier exits");
            assertEquals(1, scope.activeChildren(),
                    "only the corrupt child remains pinned for forensic state");
            assertEquals(1, scope.activeScopedReadClaims(),
                    "healthy sibling authority must retire after physical quiescence");
            assertFalse(scope.hasScopedReadLease(siblingClaim),
                    "poison must not keep a healthy sibling's stale ticket valid");
            assertTrue(siblingClaim.issued(),
                    "successful sibling claim identity stays one-shot consumed");
        }
    }


    @Test
    void failedAdmissionRollbackCorruptionPoisonsAndPinsAuthority()
            throws Exception {
        OresScheduler scheduler = new OresScheduler(1);
        OresTaskScope scope = new OresTaskScope(scheduler);
        OresTaskScope.ScopedReadClaim claim =
                new OresTaskScope.ScopedReadClaim("view", 109L, 0L, "$");

        // Make post-reservation retirement accounting inconsistent without
        // changing the region table itself. reserve() will bring -1 -> 0,
        // then the closed scheduler rejects admission and rollback must detect
        // the count underflow before mutating lease identity/region state.
        setActiveScopedReadClaimsForTest(scope, -1);
        scheduler.close();

        StructuredTaskScopePoisonException poison = assertThrows(
                StructuredTaskScopePoisonException.class,
                () -> scope.fork(
                        resume -> OresScheduler.done(1),
                        java.util.List.of(claim)));

        assertTrue(scope.isPoisoned());
        assertEquals(1, scope.activeChildren(),
                "unproved rollback must pin the forensic child");
        assertTrue(claim.issued(),
                "unproved rollback must not recycle one-shot authority");
        assertTrue(scope.hasScopedReadLease(claim),
                "unproved rollback must keep exact authority conservatively live");
        assertNotNull(poison.getCause());
        assertTrue(poison.getCause().getMessage().contains("underflow"));
        assertEquals(1, poison.getCause().getSuppressed().length);
        assertInstanceOf(
                java.util.concurrent.RejectedExecutionException.class,
                poison.getCause().getSuppressed()[0]);

        java.util.concurrent.ExecutionException drained = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> scope.cancelAndQuiesce().get(5, TimeUnit.SECONDS));
        assertSame(poison, drained.getCause(),
                "fork and drain must expose the same structured poison identity");
    }

    private static void setActiveScopedReadClaimsForTest(
            OresTaskScope scope, int value) throws Exception {
        java.lang.reflect.Field countField =
                OresTaskScope.class.getDeclaredField("activeScopedReadClaims");
        countField.setAccessible(true);
        countField.setInt(scope, value);
    }


    @SuppressWarnings("unchecked")
    private static void corruptScopedReadRegionForTest(
            OresTaskScope scope, long regionId) throws Exception {
        // Keep corruption capability out of production runtime API. The test
        // reaches the private bookkeeping only through reflection.
        java.lang.reflect.Field lockField =
                OresTaskScope.class.getDeclaredField("lock");
        java.lang.reflect.Field regionsField =
                OresTaskScope.class.getDeclaredField("scopedReadRegions");
        lockField.setAccessible(true);
        regionsField.setAccessible(true);

        Object lock = lockField.get(scope);
        synchronized (lock) {
            java.util.Map<Long, ?> regions =
                    (java.util.Map<Long, ?>) regionsField.get(scope);
            regions.remove(regionId);
        }
    }

}
