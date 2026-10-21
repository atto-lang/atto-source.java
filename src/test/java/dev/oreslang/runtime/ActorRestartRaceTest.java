package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
final class ActorRestartRaceTest {

    private static ActorRuntime.SupervisorRestartPermit acquirePermit(
            ActorRuntime runtime,
            ActorRuntime.ActorRef<?> failed) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        ActorRuntime.SupervisorRestartPermit permit = null;
        while (permit == null && System.nanoTime() < deadline) {
            permit = runtime.tryAcquireSupervisorRestartPermit(
                            failed.id(), failed.generation())
                    .orElse(null);
            if (permit == null) Thread.sleep(1);
        }
        assertNotNull(permit, "restart permit did not become available");
        return permit;
    }

    @Test
    void queuedMailFromFailedGenerationIsNeverReplayedIntoReplacement()
            throws Exception {
        CountDownLatch failingTurnEntered = new CountDownLatch(1);
        CountDownLatch releaseFailure = new CountDownLatch(1);
        AtomicInteger factoryIncarnation = new AtomicInteger();
        AtomicInteger oldQueuedDelivered = new AtomicInteger();
        AtomicInteger replacementOldMailDelivered = new AtomicInteger();

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> failed = runtime.spawnShared(() -> {
                int incarnation = factoryIncarnation.incrementAndGet();
                return (message, context) -> {
                    if ("fail".equals(message) && incarnation == 1) {
                        failingTurnEntered.countDown();
                        assertTrue(releaseFailure.await(5, TimeUnit.SECONDS));
                        throw new IllegalStateException("queued-mail-failure");
                    }
                    if ("queued-old".equals(message)) {
                        if (incarnation == 1) oldQueuedDelivered.incrementAndGet();
                        else replacementOldMailDelivered.incrementAndGet();
                    }
                };
            });
            failed.getReady().get(3, TimeUnit.SECONDS);
            failed.send("fail");
            assertTrue(failingTurnEntered.await(3, TimeUnit.SECONDS));
            failed.send("queued-old");
            releaseFailure.countDown();

            assertThrows(
                    ExecutionException.class,
                    () -> failed.getDone().get(5, TimeUnit.SECONDS));
            ActorRuntime.SupervisorRestartPermit permit =
                    acquirePermit(runtime, failed);
            ActorRuntime.ActorRef<String> replacement =
                    runtime.restartAfterFailure(permit);
            replacement.getReady().get(3, TimeUnit.SECONDS);

            replacement.send("fresh");
            replacement.drain().get(3, TimeUnit.SECONDS);
            assertEquals(0, oldQueuedDelivered.get());
            assertEquals(0, replacementOldMailDelivered.get(),
                    "mail admitted to the failed generation must never replay");
            replacement.stop();
            replacement.getDone().get(3, TimeUnit.SECONDS);
        } finally {
            releaseFailure.countDown();
        }
    }

    @Test
    void lateOwnedFutureCompletionCannotResumeReplacementGeneration()
            throws Exception {
        OresFuture<Integer> pending = new OresFuture<>();
        AtomicInteger continuationRuns = new AtomicInteger();
        AtomicInteger factoryIncarnation = new AtomicInteger();

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> failed = runtime.spawnShared(() -> {
                int incarnation = factoryIncarnation.incrementAndGet();
                return (message, context) -> {
                    if (incarnation == 1) {
                        ActorRuntime.ContinuationTarget target =
                                runtime.captureCurrentContinuationTarget();
                        runtime.enqueueOnCompletion(
                                pending,
                                target,
                                (value, failure) -> continuationRuns.incrementAndGet());
                        throw new IllegalStateException("future-owner-failure");
                    }
                };
            });
            failed.send("arm");
            assertThrows(
                    ExecutionException.class,
                    () -> failed.getDone().get(5, TimeUnit.SECONDS));
            assertTrue(pending.isCancelled(),
                    "failed actor teardown must revoke its owned Future");

            ActorRuntime.SupervisorRestartPermit permit =
                    acquirePermit(runtime, failed);
            ActorRuntime.ActorRef<String> replacement =
                    runtime.restartAfterFailure(permit);
            replacement.getReady().get(3, TimeUnit.SECONDS);

            assertFalse(pending.completeFromRuntime(7),
                    "late producer completion must lose to actor teardown");
            replacement.send("fresh");
            replacement.drain().get(3, TimeUnit.SECONDS);
            assertEquals(0, continuationRuns.get(),
                    "generation-N continuation must not execute in N+1");

            replacement.stop();
            replacement.getDone().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentStaleLifecycleOperationsCannotAffectReplacement()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> failed = runtime.spawnShared(
                    () -> (message, context) -> {
                        throw new IllegalStateException("race-restart");
                    });
            failed.send("fail");
            assertThrows(
                    ExecutionException.class,
                    () -> failed.getDone().get(5, TimeUnit.SECONDS));
            ActorRuntime.ActorRef<String> replacement =
                    runtime.restartAfterFailure(acquirePermit(runtime, failed));
            replacement.getReady().get(3, TimeUnit.SECONDS);

            int workers = 12;
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(workers);
            List<java.util.concurrent.Future<?>> attempts = new ArrayList<>();
            try {
                for (int i = 0; i < workers; i++) {
                    final int op = i % 3;
                    attempts.add(pool.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        if (op == 0) {
                            assertThrows(
                                    ActorRuntime.ActorInvalidReferenceException.class,
                                    () -> failed.send("stale"));
                        } else if (op == 1) {
                            failed.stop();
                        } else {
                            assertFalse(failed.cancel());
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (java.util.concurrent.Future<?> attempt : attempts) {
                    attempt.get(5, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            assertTrue(replacement.isAlive());
            replacement.send("still-live");
            replacement.drain().get(3, TimeUnit.SECONDS);
            replacement.stop();
            replacement.getDone().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void concurrentPermitConsumptionCreatesExactlyOneReplacement()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> failed = runtime.spawnShared(
                    () -> (message, context) -> {
                        throw new IllegalStateException("one-shot");
                    });
            failed.send("fail");
            assertThrows(
                    ExecutionException.class,
                    () -> failed.getDone().get(5, TimeUnit.SECONDS));
            ActorRuntime.SupervisorRestartPermit permit =
                    acquirePermit(runtime, failed);

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            AtomicReference<ActorRuntime.ActorRef<String>> winner =
                    new AtomicReference<>();
            AtomicInteger rejected = new AtomicInteger();
            try {
                List<java.util.concurrent.Future<?>> attempts = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    attempts.add(pool.submit(() -> {
                        assertTrue(start.await(5, TimeUnit.SECONDS));
                        try {
                            ActorRuntime.ActorRef<String> replacement =
                                    runtime.restartAfterFailure(permit);
                            if (!winner.compareAndSet(null, replacement)) {
                                fail("more than one restart succeeded");
                            }
                        } catch (IllegalStateException expected) {
                            rejected.incrementAndGet();
                        }
                        return null;
                    }));
                }
                start.countDown();
                for (java.util.concurrent.Future<?> attempt : attempts) {
                    attempt.get(5, TimeUnit.SECONDS);
                }
            } finally {
                pool.shutdownNow();
            }

            ActorRuntime.ActorRef<String> replacement = winner.get();
            assertNotNull(replacement);
            assertEquals(1, rejected.get());
            assertEquals(failed.id(), replacement.id());
            assertTrue(replacement.generation() > failed.generation());
            replacement.stop();
            replacement.getDone().get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void closedLifecycleGroupRejectsRestartWithoutPublishingReplacement()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> failed = runtime.spawnShared(
                    () -> (message, context) -> {
                        throw new IllegalStateException("group-closed");
                    });
            group.manageWorker(failed);
            failed.send("fail");
            assertThrows(
                    ExecutionException.class,
                    () -> failed.getDone().get(5, TimeUnit.SECONDS));
            ActorRuntime.SupervisorRestartPermit permit =
                    acquirePermit(runtime, failed);

            group.close();
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.restartAfterFailure(permit));
            assertTrue(
                    failure.getMessage().contains("ActorGroup")
                            || failure.getMessage().contains("lifecycle"),
                    failure::getMessage);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.controlHandle(failed.id()),
                    "failed restart must not publish a replacement cell");
        }
    }

    @Test
    void deadStructuredParentRejectsChildRestart() throws Exception {
        AtomicReference<ActorRuntime.ActorRef<String>> childRef =
                new AtomicReference<>();
        CountDownLatch childCreated = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> parent = runtime.spawnShared(() ->
                    (message, context) -> {
                        if (!"spawn".equals(message)) return;
                        ActorRuntime.ActorRef<String> child = runtime.spawn(
                                ActorRuntime.ActorKind.SHARED,
                                IsolatePolicy.developer(),
                                childContext -> (childMessage, turn) -> {
                                    throw new IllegalStateException("child-failure");
                                });
                        childRef.set(child);
                        childCreated.countDown();
                        child.send("fail");
                    });

            parent.send("spawn");
            assertTrue(childCreated.await(3, TimeUnit.SECONDS));
            ActorRuntime.ActorRef<String> child = childRef.get();
            assertNotNull(child);
            assertThrows(
                    ExecutionException.class,
                    () -> child.getDone().get(5, TimeUnit.SECONDS));
            ActorRuntime.SupervisorRestartPermit permit =
                    acquirePermit(runtime, child);

            parent.stop();
            parent.getDone().get(5, TimeUnit.SECONDS);
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> runtime.restartAfterFailure(permit));
            assertTrue(failure.getMessage().contains("parent"), failure::getMessage);
            assertThrows(
                    IllegalArgumentException.class,
                    () -> runtime.controlHandle(child.id()));
        }
    }

    @Test
    void generationExhaustionIsPermanentAndDoesNotLeakActorCapacity()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 1, 2))) {
            Field field = ActorRuntime.class.getDeclaredField("nextActorGeneration");
            field.setAccessible(true);
            AtomicLong sequence = (AtomicLong) field.get(runtime);
            sequence.set(Long.MAX_VALUE);

            ActorRuntime.ActorRef<String> last = runtime.spawnShared(
                    () -> (message, context) -> { });
            assertEquals(Long.MAX_VALUE, last.generation());
            last.stop();
            last.getDone().get(3, TimeUnit.SECONDS);
            long releaseDeadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (runtime.reservedActorSlots() != 0
                    && System.nanoTime() < releaseDeadline) {
                Thread.sleep(1);
            }
            assertEquals(
                    0,
                    runtime.reservedActorSlots(),
                    "generation exhaustion check requires released actor capacity");

            for (int i = 0; i < 2; i++) {
                IllegalStateException exhausted = assertThrows(
                        IllegalStateException.class,
                        () -> runtime.spawnShared(() -> (message, context) -> { }));
                assertEquals(
                        "actor generation sequence exhausted",
                        exhausted.getMessage());
            }
        }
    }
}
