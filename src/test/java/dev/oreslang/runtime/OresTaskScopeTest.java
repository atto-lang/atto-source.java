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
            assertSame(ownerFailure, observed.getCause());
            assertTrue(drained.isCompletedExceptionally());
            assertFalse(drained.isCancelled());
        }
    }

    @Test
    void compilerRetirementWaitsForPhysicalQuiescence() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicBoolean retired = new AtomicBoolean();

            OresFuture<Integer> child = scope.forkCompilerAdmitted(
                    resume -> {
                        entered.countDown();
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                        return OresScheduler.done(42);
                    },
                    () -> retired.set(true));

            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> drained = scope.quiesce();
            assertFalse(retired.get(),
                    "ownership must remain retained while the carrier executes");
            assertFalse(drained.isDone());

            release.countDown();
            assertEquals(42, child.get(5, TimeUnit.SECONDS));
            assertNull(drained.get(5, TimeUnit.SECONDS));
            assertTrue(retired.get(),
                    "physical Task completion must retire the ownership lease");
            assertEquals(0, scope.activeChildren());
        }
    }

    @Test
    void retirementFailurePoisonsScopeWithoutCatchableDrainWitness()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(scheduler);
            RuntimeException retirementFailure =
                    new RuntimeException("retirement failed");

            OresFuture<Integer> child = scope.forkCompilerAdmitted(
                    resume -> OresScheduler.done(7),
                    () -> { throw retirementFailure; });
            OresFuture<Void> drained = scope.quiesce();

            assertEquals(7, child.get(5, TimeUnit.SECONDS));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (scope.compilerRetirementFailure() == null
                    && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }

            assertSame(retirementFailure, scope.compilerRetirementFailure());
            assertFalse(scope.isOpen(),
                    "failed retirement must seal the scope against new children");
            assertEquals(1, scope.activeChildren(),
                    "failed child remains pinned as a fail-closed reclamation witness");
            assertFalse(drained.isDone(),
                    "failed transfer release must not become a catchable scope-exit witness");
            assertThrows(
                    java.util.concurrent.TimeoutException.class,
                    () -> drained.get(100, TimeUnit.MILLISECONDS));
            assertThrows(
                    IllegalStateException.class,
                    () -> scope.fork(resume -> OresScheduler.done(1)));
        }
    }

    @Test
    void neverStartedRetirementFailurePinsSyntheticJoinSlot() {
        OresScheduler scheduler = new OresScheduler(1);
        scheduler.close();
        OresTaskScope scope = new OresTaskScope(scheduler);
        RuntimeException retirementFailure =
                new RuntimeException("never-started retirement failed");

        java.util.concurrent.RejectedExecutionException admission =
                assertThrows(
                        java.util.concurrent.RejectedExecutionException.class,
                        () -> scope.forkCompilerAdmitted(
                                resume -> OresScheduler.done(1),
                                () -> { throw retirementFailure; }));

        assertEquals(1, admission.getSuppressed().length);
        assertSame(retirementFailure, admission.getSuppressed()[0]);
        assertSame(retirementFailure, scope.compilerRetirementFailure());
        assertFalse(scope.isOpen());
        assertEquals(1, scope.activeChildren(),
                "failed never-started rollback must retain its join slot");
        OresFuture<Void> drained = scope.quiesce();
        assertFalse(drained.isDone());
        assertThrows(
                java.util.concurrent.TimeoutException.class,
                () -> drained.get(100, TimeUnit.MILLISECONDS));
    }

}
