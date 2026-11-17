package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Current-main integration regressions for structured worker ownership.
 * Intentional API distinction: lifecycle ownership != cooperative membership,
 * workersDone() is a snapshot and closedDone() is the persistent close fence.
 *
 * Adapted from the useful unique coverage in stale ActorGroup PR #574.
 */
@Timeout(15)
final class ActorGroupManagedWorkersReconciliationTest {
    private static volatile CountDownLatch WORKER_ENTERED;
    private static volatile CountDownLatch WORKER_EXIT;


    @Test
    void immediateAndReadySpawnsHaveManagedRefsWithoutImplicitMembership() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> immediate = group.spawnWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> (message, turn) -> { });
            assertEquals(1, group.workerCount());
            assertTrue(group.workers().contains(immediate));
            assertFalse(group.contains(immediate),
                    "structured lifecycle ownership must not imply cooperative membership");
            immediate.ready().get(5, TimeUnit.SECONDS);

            ActorRuntime.ActorRef<String> awaited = group.<String>spawnReadyWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> (message, turn) -> { }).get(5, TimeUnit.SECONDS);
            assertTrue(awaited.isAlive());
            assertTrue(awaited.ready().isDone());
            assertEquals(2, group.workerCount());
            assertEquals(0, group.memberCount());

            OresFuture<Void> closed = group.closeAsync();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> closed.get(5, TimeUnit.SECONDS),
                    "cancelled workers settle the group with a typed failure");
            assertTrue(immediate.done().isDone());
            assertTrue(awaited.done().isDone());
            assertEquals(0, group.workerCount());
            assertThrows(IllegalStateException.class, () -> group.spawnWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> (message, turn) -> { }));
        }
    }

    @Test
    void explicitManagedOwnershipDoesNotStopMembershipOnlyActors() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> owned = runtime.spawnShared(
                    () -> (message, ctx) -> { });
            ActorRuntime.ActorRef<String> cooperative = runtime.spawnShared(
                    () -> (message, ctx) -> { });
            assertSame(owned, group.manageWorker(owned));
            assertSame(owned, group.manageWorker(owned));
            assertFalse(group.contains(owned));
            group.join(cooperative, group.joinCapability());
            assertEquals(1, group.workerCount());
            assertEquals(1, group.memberCount());

            OresFuture<Void> closed = group.closeAsync();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> closed.get(5, TimeUnit.SECONDS));
            assertTrue(owned.done().isDone());
            assertFalse(owned.isAlive());
            assertTrue(cooperative.isAlive(),
                    "membership alone cannot grant lifecycle cancellation authority");
            cooperative.stop();
            cooperative.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void workerHandoffTransfersExactlyOneManagerAndSurvivesOldGroupClose() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup first = runtime.createActorGroup();
            ActorRuntime.ActorGroup second = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> worker = runtime.spawnShared(
                    () -> (message, turn) -> { });
            first.manageWorker(worker);
            assertThrows(IllegalStateException.class, () -> second.manageWorker(worker));
            assertTrue(first.releaseWorker(worker));
            assertFalse(first.releaseWorker(worker));
            assertSame(worker, second.manageWorker(worker));
            first.close();
            assertTrue(worker.isAlive(), "old manager must not stop an explicitly released worker");
            OresFuture<Void> closed = second.closeAsync();
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> closed.get(5, TimeUnit.SECONDS));
            assertTrue(worker.done().isDone());
            assertFalse(worker.isAlive());
        }
    }

    @Test
    void closeBarrierWaitsForActiveWorkerTurnAndCannotBeCanceled() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorGroup other = runtime.createActorGroup();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch exit = new CountDownLatch(1);
            // Shared actor factories must be stateless (main): coordinate via statics.
            WORKER_ENTERED = entered;
            WORKER_EXIT = exit;
            ActorRuntime.ActorRef<Integer> worker = runtime.spawnShared(
                    () -> (value, ctx) -> {
                        entered.countDown();
                        if (!exit.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("active turn did not resume");
                        }
                    });
            group.manageWorker(worker);
            worker.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            OresFuture<Void> closeFence;
            try {
                closeFence = group.closeAsync();
                assertSame(closeFence, group.closedDone());
                assertFalse(closeFence.isDone(),
                        "closing registration is not completion of an in-flight turn");
                assertFalse(closeFence.cancel(false),
                        "observers must not cancel manager-owned finalization");
                assertThrows(IllegalStateException.class, () -> other.manageWorker(worker));
            } finally {
                exit.countDown();
            }
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> closeFence.get(5, TimeUnit.SECONDS),
                    "manager shutdown should report child cancellation");
            assertTrue(worker.done().isDone());
            assertFalse(worker.isAlive());
            other.close();
        }
    }

    @Test
    void runtimeShutdownKeepsWorkerDoneSnapshotLiveWhileRefsAreHidden() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch exit = new CountDownLatch(1);
        // Shared actor factories must be stateless (main): coordinate via statics.
        WORKER_ENTERED = entered;
        WORKER_EXIT = exit;
        ActorRuntime.ActorGroup group = runtime.createActorGroup();
        ActorRuntime.ActorRef<Integer> worker = group.spawnWorker(
                ActorRuntime.ActorKind.SHARED,
                IsolatePolicy.developer(),
                ctx -> (value, turn) -> {
                    WORKER_ENTERED.countDown();
                    while (WORKER_EXIT.getCount() != 0) {
                        try {
                            WORKER_EXIT.await(50, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                            // Runtime carrier shutdown may interrupt the host
                            // thread. Keep the turn alive until the fixture
                            // explicitly releases it so the lifecycle barrier
                            // can be observed during sealed shutdown.
                        }
                    }
                });

        worker.send(1);
        assertTrue(entered.await(5, TimeUnit.SECONDS));

        Thread closer = Thread.ofVirtual().start(runtime::close);
        try {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!group.closed() && System.nanoTime() < until) Thread.yield();
            assertTrue(group.closed(), "runtime shutdown must seal group observation");
            assertEquals(0, group.workerCount());
            assertTrue(group.workers().isEmpty(),
                    "sealed runtime must not re-expose retained ActorRefs");

            OresFuture<java.util.List<Void>> doneSnapshot = group.workersDone();
            assertFalse(doneSnapshot.isDone(),
                    "runtime admission sealing must not turn an active worker barrier into an empty completed snapshot");

            exit.countDown();
            try {
                doneSnapshot.get(5, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException expectedCancellation) {
                // Runtime shutdown may settle the worker as cancelled; either
                // terminal outcome is valid, but it must not precede retirement.
            }
        } finally {
            exit.countDown();
            closer.join(TimeUnit.SECONDS.toMillis(5));
        }
        assertFalse(closer.isAlive(), "runtime close must finish after the worker retires");
    }

    @Test
    void closedEmptyGroupReturnsEmptyWorkerCompletionSnapshot() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            group.close();
            assertEquals(java.util.List.of(),
                    group.workersDone().get(5, TimeUnit.SECONDS),
                    "a closed group with zero workers must not report one Void slot");
            assertEquals(java.util.List.of(),
                    group.workersDone().get(5, TimeUnit.SECONDS),
                    "repeated post-close observations must preserve result cardinality");
        }
    }

    @Test
    void postCloseWorkerBarrierWaitsThroughCancellationAndObserverRevocation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch exit = new CountDownLatch(1);
            // Shared actor factories must be stateless (main): coordinate via statics.
            WORKER_ENTERED = entered;
            WORKER_EXIT = exit;
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<Integer> worker = group.spawnWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> (value, turn) -> {
                        WORKER_ENTERED.countDown();
                        while (WORKER_EXIT.getCount() != 0) {
                            try {
                                WORKER_EXIT.await(50, TimeUnit.MILLISECONDS);
                            } catch (InterruptedException ignored) {
                                // Deliberately retain the in-flight turn while
                                // the manager revokes new actor admissions.
                            }
                        }
                    });
            try {
                worker.send(1);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                OresFuture<Void> closeFence = group.closeAsync();
                assertTrue(group.closed());
                assertTrue(group.workers().isEmpty());
                assertEquals(0, group.workerCount());

                OresFuture<java.util.List<Void>> afterClose = group.workersDone();
                OresFuture<java.util.List<Void>> cancelledObserver = group.workersDone();
                assertFalse(afterClose.isDone(),
                        "worker retirement must remain pending after group-close admission");
                assertTrue(cancelledObserver.cancel(false),
                        "one borrowed observer may detach itself");
                assertFalse(closeFence.isDone(),
                        "cancelling an observer cannot cancel the owner close barrier");

                exit.countDown();
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> closeFence.get(5, TimeUnit.SECONDS),
                        "managed worker cancellation must remain visible");
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> afterClose.get(5, TimeUnit.SECONDS),
                        "post-close workersDone must observe the settled close failure");
                assertTrue(group.workersDone().isDone(),
                        "subsequent observers must see the same terminal barrier");
                assertFalse(worker.isAlive());
            } finally {
                exit.countDown();
            }
        }
    }

    @Test
    void failingInfrastructureCloseDoesNotCompleteActiveWorkerBarrierEarly() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch exit = new CountDownLatch(1);
            // Shared actor factories must be stateless (main): coordinate via statics.
            WORKER_ENTERED = entered;
            WORKER_EXIT = exit;
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<Integer> worker = group.spawnWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> (value, turn) -> {
                        WORKER_ENTERED.countDown();
                        while (WORKER_EXIT.getCount() != 0) {
                            try {
                                WORKER_EXIT.await(50, TimeUnit.MILLISECONDS);
                            } catch (InterruptedException ignored) {
                                // Keep shutdown's worker retirement observable.
                            }
                        }
                    });
            group.registerInfrastructureClose(() -> {
                throw new IllegalStateException("infrastructure cleanup failed");
            });
            try {
                worker.send(1);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                IllegalStateException failure = assertThrows(
                        IllegalStateException.class, group::close);
                assertEquals("infrastructure cleanup failed", failure.getMessage());
                assertTrue(group.closed());
                assertTrue(group.workers().isEmpty());
                OresFuture<java.util.List<Void>> observed = group.workersDone();
                assertFalse(observed.isDone(),
                        "ordinary hook failure must not fake worker retirement");
                assertFalse(group.closedDone().isDone());

                exit.countDown();
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> group.closedDone().get(5, TimeUnit.SECONDS));
                assertTrue(observed.isDone());
                assertFalse(worker.isAlive());
            } finally {
                exit.countDown();
            }
        }
    }

    @Test
    void workersDoneAndGroupCloseRaceCannotProduceAnEarlyEmptyBarrier() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch exit = new CountDownLatch(1);
            // Shared actor factories must be stateless (main): coordinate via statics.
            WORKER_ENTERED = entered;
            WORKER_EXIT = exit;
            CountDownLatch start = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<OresFuture<java.util.List<Void>>>
                    observed = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();

            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<Integer> worker = group.spawnWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> (value, turn) -> {
                        WORKER_ENTERED.countDown();
                        while (WORKER_EXIT.getCount() != 0) {
                            try {
                                WORKER_EXIT.await(50, TimeUnit.MILLISECONDS);
                            } catch (InterruptedException ignored) {
                                // Preserve the active turn across close admission.
                            }
                        }
                    });
            Thread observer = null;
            Thread closer = null;
            try {
                worker.send(1);
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                observer = Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        observed.set(group.workersDone());
                    } catch (Throwable issue) {
                        failure.compareAndSet(null, issue);
                    }
                });
                closer = Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        group.close();
                    } catch (Throwable issue) {
                        failure.compareAndSet(null, issue);
                    }
                });
                start.countDown();
                observer.join(5_000);
                closer.join(5_000);
                assertFalse(observer.isAlive());
                assertFalse(closer.isAlive());
                assertNull(failure.get(), String.valueOf(failure.get()));
                assertNotNull(observed.get());
                assertTrue(group.closed());
                assertFalse(observed.get().isDone(),
                        "an observer racing close must retain the active worker barrier");
                assertFalse(group.workersDone().isDone(),
                        "post-close observation must not report early completion");

                exit.countDown();
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> group.closedDone().get(5, TimeUnit.SECONDS));
                assertTrue(observed.get().isDone());
                assertTrue(group.workersDone().isDone());
            } finally {
                start.countDown();
                exit.countDown();
                if (observer != null) observer.join(5_000);
                if (closer != null) closer.join(5_000);
            }
        }
    }

    @Test
    void rejectedInitializationReleasesWorkerAndCannotAdoptForeignRuntime() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ActorRuntime foreignRuntime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> foreign = foreignRuntime.spawnShared(
                    () -> (message, turn) -> { });
            assertThrows(IllegalArgumentException.class, () -> group.manageWorker(foreign));

            OresFuture<ActorRuntime.ActorRef<String>> failed = group.spawnReadyWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> { throw new IllegalStateException("worker-init-failed"); });
            java.util.concurrent.ExecutionException exception = assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> failed.get(5, TimeUnit.SECONDS));
            assertEquals("worker-init-failed", exception.getCause().getMessage());

            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (group.workerCount() != 0 && System.nanoTime() < until) Thread.yield();
            assertEquals(0, group.workerCount(), "failed actor must not remain retained");
            assertSame(group.closedDone(), group.closeAsync());
            group.closedDone().get(5, TimeUnit.SECONDS);
            foreign.stop();
            foreign.done().get(5, TimeUnit.SECONDS);
        }
    }
}
