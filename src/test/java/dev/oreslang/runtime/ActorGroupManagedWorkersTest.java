package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorGroupManagedWorkersTest {

    @Test
    void spawnWorkerReturnsImmediateHandleWhileReadySpawnYieldsSameHandle() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> immediate = group.spawnWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> (message, turn) -> { });
            assertTrue(group.contains(immediate));
            assertEquals(1, group.workerCount());
            assertSame(immediate, group.workerRefs().getFirst());
            immediate.ready().get(5, TimeUnit.SECONDS);

            ActorRuntime.ActorRef<String> awaited = group.spawnWorkerReady(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> (message, turn) -> { }).get(5, TimeUnit.SECONDS);
            assertTrue(awaited.isAlive());
            assertTrue(group.contains(awaited));
            assertTrue(awaited.ready().isDone());
            assertEquals(2, group.workerCount());

            group.close();
            immediate.done().get(5, TimeUnit.SECONDS);
            awaited.done().get(5, TimeUnit.SECONDS);
            assertFalse(immediate.isAlive());
            assertFalse(awaited.isAlive());
            assertEquals(0, group.workerCount());
            assertThrows(IllegalStateException.class, () -> group.spawnWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> (message, turn) -> { }));
        }
    }

    @Test
    void manualAdoptionIsIdempotentAndJoinedNonWorkerSurvivesGroupClose() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> owned = runtime.spawnShared(
                    () -> (message, ctx) -> { });
            ActorRuntime.ActorRef<String> cooperative = runtime.spawnShared(
                    () -> (message, ctx) -> { });
            assertSame(owned, group.manage(owned));
            assertSame(owned, group.manage(owned));
            group.join(cooperative, group.joinCapability());
            assertEquals(1, group.workerCount());
            assertEquals(2, group.memberCount());

            group.close();
            owned.done().get(5, TimeUnit.SECONDS);
            assertFalse(owned.isAlive());
            assertTrue(cooperative.isAlive(),
                    "mere group membership must not transfer lifecycle ownership");
            cooperative.stop();
            cooperative.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void exactlyOneGroupOwnsLifecycleAndExplicitHandoffDoesNotStopTheWorker()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup first = runtime.createActorGroup();
            ActorRuntime.ActorGroup second = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> worker = runtime.spawnShared(
                    () -> (message, turn) -> { });
            first.manage(worker);
            assertThrows(IllegalStateException.class, () -> second.manage(worker));
            assertEquals(1, first.workerCount());
            assertEquals(0, second.workerCount());

            assertTrue(first.releaseWorker(worker));
            assertFalse(first.releaseWorker(worker));
            second.manage(worker);
            first.close();
            assertTrue(worker.isAlive(), "ownership handoff must not stop worker");
            second.close();
            worker.done().get(5, TimeUnit.SECONDS);
            assertFalse(worker.isAlive());
        }
    }

    @Test
    void groupDoneBarrierWaitsForSnapshotAndIsNotCancelledByObservers()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> left = group.spawnWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> (message, turn) -> { });
            ActorRuntime.ActorRef<String> right = group.spawnWorker(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> (message, turn) -> { });

            OresFuture<Void> allDone = group.workersDone();
            assertFalse(allDone.isDone());
            assertFalse(allDone.cancel(false), "observer cannot cancel worker drain");
            group.stopWorkers();
            allDone.get(5, TimeUnit.SECONDS);
            left.done().get(5, TimeUnit.SECONDS);
            right.done().get(5, TimeUnit.SECONDS);
            assertEquals(0, group.workerCount());
            assertTrue(group.workersDone().isDone(), "empty group settles immediately");
        }
    }

    @Test
    void managedWorkerCannotLeaveMembershipUntilManagerReleasesOwnership()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> ref = runtime.spawnShared(
                    () -> (value, context) -> {
                        try {
                            group.leaveCurrent();
                            failure.set(new AssertionError(
                                    "managed worker silently left its group"));
                        } catch (IllegalStateException expected) {
                            // Managed workers must remain members until
                            // the manager explicitly releases ownership.
                        } finally {
                            checked.countDown();
                        }
                    });
            group.manage(ref);
            ref.send(1);
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(group.contains(ref));
            assertTrue(group.releaseWorker(ref));
            group.close();
            assertTrue(ref.isAlive(), "released worker is not stopped by group");
            ref.stop();
            ref.done().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void pendingStopFencesOwnershipHandoffWhileWorkerTurnIsStillRunning()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch exit = new CountDownLatch(1);
            ActorRuntime.ActorRef<Integer> ref = runtime.spawnShared(
                    () -> (value, context) -> {
                        entered.countDown();
                        if (!exit.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("test worker was not released");
                        }
                    });
            group.manage(ref);
            ref.send(7);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                group.stopWorkers();
                assertThrows(IllegalStateException.class,
                        () -> group.releaseWorker(ref),
                        "stop admission must fence ownership transfer");
            } finally {
                exit.countDown();
            }
            ref.done().get(5, TimeUnit.SECONDS);
            assertEquals(0, group.workerCount());
        }
    }

    @Test
    void failedInitializationRejectsReadySpawnAndReleasesGroupReference() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            OresFuture<ActorRuntime.ActorRef<String>> spawned = group.spawnWorkerReady(
                    ActorRuntime.ActorKind.SHARED, IsolatePolicy.developer(),
                    ctx -> { throw new IllegalStateException("worker-init-failed"); });
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> spawned.get(5, TimeUnit.SECONDS));
            assertEquals("worker-init-failed", failure.getCause().getMessage());
            // done/ready publication and group-unregistration can interleave.
            // The group must not keep a live worker after finalization settles.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (group.workerCount() != 0 && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(0, group.workerCount());
        }
    }

    @Test
    void cannotAdoptForeignActorAndCloseFencesAllManagedWorkerAdmissions() throws Exception {
        try (ActorRuntime left = new ActorRuntime();
             ActorRuntime right = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = left.createActorGroup();
            ActorRuntime.ActorRef<Integer> foreign = right.spawnShared(
                    () -> (message, ctx) -> { });
            assertThrows(IllegalArgumentException.class, () -> group.manage(foreign));
            assertEquals(0, group.workerCount());

            ActorRuntime.ActorRef<Integer> own = left.spawnShared(
                    () -> (message, ctx) -> { });
            group.manage(own);
            group.close();
            own.done().get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> group.manage(
                    left.spawnShared(() -> (message, ctx) -> { })));
            foreign.stop();
            foreign.done().get(5, TimeUnit.SECONDS);
        }
    }
}
