package dev.oreslang.runtime;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresFuture;
import dev.oreslang.runtime.OresScheduler;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * STOP is a separate lifetime boundary from abrupt Future.cancel(false).
 * It must seal ordinary mail while preserving the originating suspended
 * source task's scheduler-owned cleanup and one-shot completion CONTROL turn.
 */
@Timeout(20)
final class ActorStructuredCleanupStopTest {
    private static void awaitWaiter(OresFuture<?> producer) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (producer.pendingRuntimeWaiterCount() != 1
                && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(1, producer.pendingRuntimeWaiterCount());
    }

    @Test
    void externalStopRunsAwaitingCleanupBeforeDoneAndSealsOrdinaryMailbox() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            OresFuture<Integer> producer = new OresFuture<>();
            OresFuture<Void> cleanupGate = new OresFuture<>();
            AtomicInteger stages = new AtomicInteger();
            AtomicInteger cleanupRuns = new AtomicInteger();
            CountDownLatch cleanupEntered = new CountDownLatch(1);

            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                OresFuture<Void> task = context.runtime().startActorTask(resume -> {
                    int pc = stages.getAndIncrement();
                    if (pc == 0) {
                        assertTrue(resume.initial());
                        return OresScheduler.await(producer);
                    }
                    if (pc == 1) {
                        assertInstanceOf(java.util.concurrent.CancellationException.class,
                                resume.failure());
                        assertSame(runtime, context.runtime());
                        cleanupRuns.incrementAndGet();
                        cleanupEntered.countDown();
                        return OresScheduler.await(cleanupGate);
                    }
                    assertEquals(2, pc, "cleanup continuation executed twice");
                    assertNull(resume.failure());
                    return OresScheduler.done(null);
                });
                context.runtime().ownCurrentActorFuture(task);
                context.runtime().suspendCurrentUserMailboxUntil(task);
            });

            ref.send(1);
            awaitWaiter(producer);
            ref.stop();
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS),
                    "STOP must deliver cancellation to the owning source task");
            assertFalse(ref.awaitTermination(100, TimeUnit.MILLISECONDS),
                    "get_done/termination cannot settle before cleanup finishes");
            assertThrows(RuntimeException.class, () -> ref.send(2),
                    "ordinary user mail must be sealed during cleanup");
            assertEquals(1, cleanupRuns.get());
            assertFalse(producer.isDone(),
                    "shared producer belongs to itself, not the canceled task");
            assertTrue(producer.completeFromRuntime(7),
                    "producer may complete late without reviving the old task");
            assertEquals(1, cleanupRuns.get());
            cleanupGate.completeFromRuntime(null);
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.failure().isEmpty(), () -> "graceful cleanup: " + ref.failure());
            assertEquals(3, stages.get());
            assertEquals(1, cleanupRuns.get());
        }
    }

    @Test
    void stopBeforeSourceTaskActuallySuspendsDoesNotPretendCleanupCompleted()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 2, 64, 16);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            CountDownLatch enteredReceive = new CountDownLatch(1);
            CountDownLatch releaseReceive = new CountDownLatch(1);
            OresFuture<Integer> gate = new OresFuture<>();
            AtomicInteger taskRuns = new AtomicInteger();
            var ref = runtime.<Integer>spawnShared(() -> (message, context) -> {
                OresFuture<Void> task = context.runtime().startActorTask(resume -> {
                    taskRuns.incrementAndGet();
                    return OresScheduler.await(gate);
                });
                context.runtime().ownCurrentActorFuture(task);
                context.runtime().suspendCurrentUserMailboxUntil(task);
                enteredReceive.countDown();
                assertTrue(releaseReceive.await(5, TimeUnit.SECONDS));
            });
            ref.send(1);
            assertTrue(enteredReceive.await(5, TimeUnit.SECONDS));
            // The actor's single carrier is still executing receive, so
            // scheduler task has been queued but has never entered guest code.
            java.util.concurrent.atomic.AtomicReference<Throwable> stopFailure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            Thread stopper = Thread.ofPlatform().start(() -> {
                try {
                    ref.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (ref.failure().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(ref.failure().isPresent(),
                    "unstarted queued source task must be classified incomplete");
            releaseReceive.countDown();
            stopper.join(5_000);
            assertFalse(stopper.isAlive());
            assertNull(stopFailure.get());
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(ref.failure().isPresent(),
                    "failure to claim a suspended frame must be explicit");
            assertTrue(ref.failure().orElseThrow().toString().contains("cleanup incomplete"),
                    String.valueOf(ref.failure()));
            assertEquals(0, taskRuns.get());
        }
    }
}
