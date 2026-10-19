package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real SHARED ActorRuntime freeze + resumable Task scheduler integration.
 * A host-only Task frame is not compiler-lowered guest task {} execution.
 */
@Timeout(30)
final class ActorHotUpgradeTaskBridgeV1Test {
    private static final long WAIT_SECONDS = 5L;

    private static ActorRuntime.ActorRef<Integer> idleShared(ActorRuntime runtime)
            throws Exception {
        var actor = runtime.<Integer>spawnShared(
                () -> (message, context) -> context.self().stop());
        actor.getReady().get(WAIT_SECONDS, TimeUnit.SECONDS);
        return actor;
    }

    private static void awaitDrain(ActorRuntime.HotUpgradeIngressFreeze freeze)
            throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (!freeze.schedulerDrained() && System.nanoTime() < until) {
            Thread.sleep(2);
        }
        assertTrue(freeze.schedulerDrained(),
                "generation must drain only after physical Task quiescence");
    }

    @Test
    void suspendedTaskRetainsOneGenerationPinAcrossResumeAndUpgradeFreeze()
            throws Exception {
        try (var runtime = new ActorRuntime();
             var scheduler = new OresScheduler(1)) {
            var actor = idleShared(runtime);
            var awaited = new OresFuture<Integer>();
            var suspended = new CountDownLatch(1);
            var turns = new AtomicInteger();
            OresFuture<Integer> result = ActorHotUpgradeTaskBridgeV1.startPinned(
                    runtime, actor, scheduler, resume -> {
                        turns.incrementAndGet();
                        if (resume.initial()) {
                            suspended.countDown();
                            return OresScheduler.await(awaited);
                        }
                        assertNull(resume.failure());
                        return OresScheduler.done((Integer) resume.value() + 3);
                    });
            assertTrue(suspended.await(WAIT_SECONDS, TimeUnit.SECONDS));

            try (var freeze = runtime.freezeUserIngressForHotUpgrade(actor)) {
                assertEquals(1, freeze.outstandingExternalPins());
                assertFalse(freeze.schedulerDrained());
                // Freeze and new Task admission share ActorRuntime's lifecycle
                // lock; an old suspended Task can resume but new Tasks cannot.
                assertThrows(ActorRuntime.ActorSealedException.class,
                        () -> ActorHotUpgradeTaskBridgeV1.startPinned(
                                runtime, actor, scheduler,
                                resume -> OresScheduler.done(0)));
                assertEquals(1, freeze.outstandingExternalPins());

                awaited.completeFromRuntime(40);
                assertEquals(43, result.get(WAIT_SECONDS, TimeUnit.SECONDS));
                awaitDrain(freeze);
                assertEquals(0, freeze.outstandingExternalPins());
                assertEquals(2, turns.get(),
                        "resuming suspends must reuse the same Task lease");
            }
            actor.send(1);
            assertTrue(actor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    void settledCancellationCannotReleasePinUnderExecutingCarrier()
            throws Exception {
        try (var runtime = new ActorRuntime();
             var scheduler = new OresScheduler(1)) {
            var actor = idleShared(runtime);
            var started = new CountDownLatch(1);
            var releaseCarrier = new AtomicBoolean();
            var task = ActorHotUpgradeTaskBridgeV1.startPinned(
                    runtime, actor, scheduler, resume -> {
                        started.countDown();
                        // Deliberate host-test-only carrier stall to prove the
                        // distinction between Future settlement and physical exit.
                        while (!releaseCarrier.get()) Thread.onSpinWait();
                        return OresScheduler.done(1);
                    });
            try {
                assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS));
                assertTrue(task.cancel(true));
                assertTrue(task.isDone());
                try (var freeze = runtime.freezeUserIngressForHotUpgrade(actor)) {
                    assertEquals(1, freeze.outstandingExternalPins(),
                            "cancelled result does not own physical retirement");
                    assertFalse(freeze.schedulerDrained());
                    releaseCarrier.set(true);
                    awaitDrain(freeze);
                    assertEquals(0, freeze.outstandingExternalPins());
                }
            } finally {
                releaseCarrier.set(true);
            }
            actor.send(1);
            assertTrue(actor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectedSchedulerAdmissionReleasesReservedActorGenerationPin()
            throws Exception {
        try (var runtime = new ActorRuntime();
             var scheduler = new OresScheduler(1)) {
            var actor = idleShared(runtime);
            scheduler.close();
            assertThrows(RejectedExecutionException.class,
                    () -> ActorHotUpgradeTaskBridgeV1.startPinned(
                            runtime, actor, scheduler,
                            resume -> OresScheduler.done(1)));
            try (var freeze = runtime.freezeUserIngressForHotUpgrade(actor)) {
                assertEquals(0, freeze.outstandingExternalPins(),
                        "pre-admission rejection must not strand old code");
                awaitDrain(freeze);
            }
            actor.send(1);
            assertTrue(actor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    void executingSchedulerTurnCannotLaunchUnscopedActorTask() throws Exception {
        try (var runtime = new ActorRuntime();
             var scheduler = new OresScheduler(1)) {
            var actor = idleShared(runtime);
            var nested = scheduler.start(resume -> {
                assertThrows(SecurityException.class,
                        () -> ActorHotUpgradeTaskBridgeV1.startPinned(
                                runtime, actor, scheduler,
                                child -> OresScheduler.done(77)));
                return OresScheduler.done(1);
            });
            assertEquals(1, nested.get(WAIT_SECONDS, TimeUnit.SECONDS));
            try (var freeze = runtime.freezeUserIngressForHotUpgrade(actor)) {
                assertEquals(0, freeze.outstandingExternalPins(),
                        "guest Task turn must not acquire unscoped code pins");
                awaitDrain(freeze);
            }
            actor.send(1);
            assertTrue(actor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    void wrongRuntimeCannotPinForeignActorOrLaunchTask()
            throws Exception {
        try (var source = new ActorRuntime();
             var foreign = new ActorRuntime();
             var scheduler = new OresScheduler(1)) {
            var actor = idleShared(source);
            var executed = new AtomicBoolean();
            assertThrows(ActorRuntime.ActorInvalidReferenceException.class,
                    () -> ActorHotUpgradeTaskBridgeV1.startPinned(
                            foreign, actor, scheduler, resume -> {
                                executed.set(true);
                                return OresScheduler.done(1);
                            }));
            assertFalse(executed.get());
            try (var freeze = source.freezeUserIngressForHotUpgrade(actor)) {
                awaitDrain(freeze);
                assertEquals(0, freeze.outstandingExternalPins());
            }
            actor.send(1);
            assertTrue(actor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    void separateChildTaskGetsIndependentGenerationPin() throws Exception {
        try (var runtime = new ActorRuntime();
             var scheduler = new OresScheduler(1)) {
            var actor = idleShared(runtime);
            var first = new OresFuture<Void>();
            var second = new OresFuture<Void>();
            var started = new CountDownLatch(2);
            var firstTask = ActorHotUpgradeTaskBridgeV1.startPinned(
                    runtime, actor, scheduler, resume -> {
                        if (resume.initial()) {
                            started.countDown();
                            return OresScheduler.await(first);
                        }
                        return OresScheduler.done(1);
                    });
            var childTask = ActorHotUpgradeTaskBridgeV1.startPinned(
                    runtime, actor, scheduler, resume -> {
                        if (resume.initial()) {
                            started.countDown();
                            return OresScheduler.await(second);
                        }
                        return OresScheduler.done(2);
                    });
            assertTrue(started.await(WAIT_SECONDS, TimeUnit.SECONDS));
            try (var freeze = runtime.freezeUserIngressForHotUpgrade(actor)) {
                assertEquals(2, freeze.outstandingExternalPins());
                first.completeFromRuntime(null);
                assertEquals(1, firstTask.get(WAIT_SECONDS, TimeUnit.SECONDS));
                assertEquals(1, freeze.outstandingExternalPins());
                assertFalse(freeze.schedulerDrained());
                second.completeFromRuntime(null);
                assertEquals(2, childTask.get(WAIT_SECONDS, TimeUnit.SECONDS));
                awaitDrain(freeze);
            }
            actor.send(1);
            assertTrue(actor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
        }
    }
}
