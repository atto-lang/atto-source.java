package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real TaskRunner admission / afterCarrierTurn / onQuiescent JNI lifecycle.
 * Not the production actor scheduler; this test configures native enforcement
 * explicitly for a private platform-carrier scheduler. Disabled by default.
 */
@EnabledIfSystemProperty(named = "ores.native.actor.turn.experimental", matches = "true")
final class NativeActorTurnSchedulerJniTest {
    /**
     * Test-only inspection of the trusted TaskRunner identity. No production
     * API exposes native SourceTask handles to guest code.
     */
    private static NativeActorTurnKernel.SourceTask currentNativeSourceForTest()
            throws ReflectiveOperationException {
        Object domain = OresScheduler.currentTaskDomain();
        assertNotNull(domain, "must be inside the actual TaskRunner");
        var field = domain.getClass().getDeclaredField("nativeSource");
        field.setAccessible(true);
        return (NativeActorTurnKernel.SourceTask) field.get(domain);
    }

    @Test
    void pendingCleanupTicketBlocksFutureUntilTrustedTerminalRetry() throws Exception {
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(64)) {
            try (OresScheduler scheduler = OresScheduler.managedWithNativeTurnWitness(
                    1, Runnable::run, kernel)) {
                var gate = new OresFuture<Integer>();
                var started = new CountDownLatch(1);
                var source = new AtomicReference<NativeActorTurnKernel.SourceTask>();
                var steps = new AtomicInteger();
                OresFuture<Integer> result = scheduler.start(resume -> {
                    if (steps.getAndIncrement() == 0) {
                        source.set(currentNativeSourceForTest());
                        started.countDown();
                        return OresScheduler.<Integer>await(gate);
                    }
                    return OresScheduler.done(91);
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                // The pending ticket prevents native source settlement AFTER
                // guest turn completion and physical carrier release.
                var ticket = kernel.issueCleanup(source.get());
                gate.completeFromRuntime(1);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (scheduler.nativeRetirementFailures() == 0
                        && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(1, scheduler.nativeRetirementFailures());
                assertFalse(result.isDone(), "terminal publication outran native retirement");
                kernel.cancelCleanup(ticket);
                assertEquals(1, scheduler.retryNativeQuiescentTasks());
                assertEquals(91, result.get(5, TimeUnit.SECONDS));
                assertEquals(0, scheduler.retryNativeQuiescentTasks());
                assertEquals(2, steps.get(), "retirement retry must not resume guest code");
                kernel.stop();
            }
        }
    }

    @Test
    void actualSchedulerSuspendsResumesAndRetiresOnlyAfterPhysicalExit() throws Exception {
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(64)) {
            try (OresScheduler scheduler = OresScheduler.managedWithNativeTurnWitness(
                    1, Runnable::run, kernel)) {
                var gate = new OresFuture<Integer>();
                var steps = new AtomicInteger();
                var started = new CountDownLatch(1);
                OresFuture<Integer> result = scheduler.start(resume -> {
                    if (steps.getAndIncrement() == 0) {
                        started.countDown();
                        return OresScheduler.<Integer>await(gate);
                    }
                    assertFalse(resume.initial());
                    return OresScheduler.done(42);
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                gate.completeFromRuntime(42);
                assertEquals(42, result.get(5, TimeUnit.SECONDS));
                assertEquals(2, steps.get());
                // The terminal Future is published only after native SourceTask
                // lease release + retirement in TaskRunner.afterCarrierTurn().
                kernel.stop();
            }
        }
    }

    @Test
    void nativeStopDeniesActualScheduledResumeAndStillRetiresSource() throws Exception {
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(64)) {
            try (OresScheduler scheduler = OresScheduler.managedWithNativeTurnWitness(
                    1, Runnable::run, kernel)) {
                var gate = new OresFuture<Integer>();
                var started = new CountDownLatch(1);
                var steps = new AtomicInteger();
                OresFuture<Integer> result = scheduler.start(resume -> {
                    steps.incrementAndGet();
                    started.countDown();
                    return OresScheduler.<Integer>await(gate);
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                kernel.stop();
                gate.completeFromRuntime(1);
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> result.get(5, TimeUnit.SECONDS));
                assertInstanceOf(IllegalStateException.class, failure.getCause());
                assertEquals(1, steps.get(), "guest must not resume after STOP");
            }
        }
    }

    @Test
    void rejectedNativeClaimDoesNotPublishAnotherTaskAsDone() throws Exception {
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(64)) {
            try (OresScheduler scheduler = OresScheduler.managedWithNativeTurnWitness(
                    1, Runnable::run, kernel)) {
                var gate = new OresFuture<Integer>();
                var blocked = new CountDownLatch(1);
                var normalTurns = new AtomicInteger();
                OresFuture<Integer> first = scheduler.start(resume -> {
                    normalTurns.incrementAndGet();
                    blocked.countDown();
                    return OresScheduler.<Integer>await(gate);
                });
                assertTrue(blocked.await(5, TimeUnit.SECONDS));
                kernel.stop();
                // Native SourceTask registration fails synchronously during
                // scheduler admission, before the second Future is published.
                assertThrows(IllegalStateException.class, () -> scheduler.start(
                        resume -> OresScheduler.done(77)));
                gate.completeFromRuntime(1);
                assertThrows(ExecutionException.class, () -> first.get(5, TimeUnit.SECONDS));
                assertEquals(1, normalTurns.get());
            }
        }
    }
}
