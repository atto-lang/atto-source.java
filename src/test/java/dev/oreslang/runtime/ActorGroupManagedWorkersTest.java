package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ExecutionException;
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
