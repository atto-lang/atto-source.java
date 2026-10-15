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
    void throwingTrustedJavaCleanupDoesNotStrandOtherTaskCancellation() throws Exception {
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(128)) {
            try (OresScheduler scheduler = OresScheduler.managedWithNativeTurnWitness(
                    1, Runnable::run, kernel)) {
                var started = new CountDownLatch(2);
                var failingCleanup = new AtomicInteger();
                var firstGate = new OresFuture<Integer>();
                var secondGate = new OresFuture<Integer>();
                OresFuture<Integer> first = scheduler.start(resume -> {
                    started.countDown();
                    return OresScheduler.<Integer>await(firstGate);
                }, () -> {
                    failingCleanup.incrementAndGet();
                    throw new IllegalStateException("trusted cleanup rejected");
                });
                OresFuture<Integer> second = scheduler.start(resume -> {
                    started.countDown();
                    return OresScheduler.<Integer>await(secondGate);
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var failure = assertThrows(IllegalStateException.class,
                        scheduler::close);
                assertTrue(failure.getMessage().contains("trusted cleanup")
                                || failure.getSuppressed().length > 0,
                        "source callback failure must remain visible");
                assertTrue(first.isDone(), "failed source cancellation not delivered");
                assertTrue(second.isDone(), "healthy source cancellation stranded");
                assertEquals(1, failingCleanup.get(),
                        "trusted callback must not be replayed on shutdown");
                assertEquals(1, scheduler.nativeQuiescenceFailures());
                kernel.stop();
            }
        }
    }

    @Test
    void nativeRetirementFailureCannotPreventOtherTaskShutdown() throws Exception {
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(128)) {
            try (OresScheduler scheduler = OresScheduler.managedWithNativeTurnWitness(
                    1, Runnable::run, kernel)) {
                var started = new CountDownLatch(2);
                var firstSource = new AtomicReference<NativeActorTurnKernel.SourceTask>();
                var gate1 = new OresFuture<Integer>();
                var gate2 = new OresFuture<Integer>();
                OresFuture<Integer> first = scheduler.start(resume -> {
                    firstSource.set(currentNativeSourceForTest());
                    started.countDown();
                    return OresScheduler.<Integer>await(gate1);
                });
                OresFuture<Integer> second = scheduler.start(resume -> {
                    started.countDown();
                    return OresScheduler.<Integer>await(gate2);
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                var ticket = kernel.issueCleanup(firstSource.get());

                // First native source cannot settle with an open ticket.
                // Both task Future cancellations must still be delivered.
                assertThrows(NativeActorTurnKernel.NativeStatusFailure.class,
                        scheduler::close);
                assertTrue(first.isDone(), "first task cancellation not delivered");
                assertTrue(second.isDone(), "another task was stranded by first failure");
                assertTrue(scheduler.nativeRetirementFailures() >= 1);

                kernel.cancelCleanup(ticket);
                assertEquals(1, scheduler.retryNativeQuiescentTasks());
                assertEquals(0, scheduler.retryNativeQuiescentTasks());
                kernel.stop(); // Kernel.close will enforce no live SourceTasks.
            }
        }
    }

    private static int liveTaskCountForTest(OresScheduler scheduler)
            throws ReflectiveOperationException {
        var field = OresScheduler.class.getDeclaredField("tasks");
        field.setAccessible(true);
        return ((java.util.Set<?>) field.get(scheduler)).size();
    }

    @Test
    void failingTrustedQuiescenceCallbackIsObservableButNeverReplayed() throws Exception {
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(64)) {
            try (OresScheduler scheduler = OresScheduler.managedWithNativeTurnWitness(
                    1, Runnable::run, kernel)) {
                var calls = new AtomicInteger();
                OresFuture<Integer> result = scheduler.start(
                        resume -> OresScheduler.done(99),
                        () -> {
                            calls.incrementAndGet();
                            throw new IllegalStateException("trusted cleanup failed");
                        });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (scheduler.nativeQuiescenceFailures() == 0
                        && System.nanoTime() < deadline) Thread.sleep(5);
                assertEquals(1, scheduler.nativeQuiescenceFailures());
                assertFalse(result.isDone(),
                        "successful guest step is not DONE after cleanup failure");
                var retry = assertThrows(IllegalStateException.class,
                        scheduler::retryNativeQuiescentTasks);
                assertTrue(retry.getMessage().contains("manual recovery"));
                assertEquals(1, calls.get(),
                        "retry must not invoke a non-idempotent trusted callback twice");
                assertEquals(1, liveTaskCountForTest(scheduler),
                        "unresolved callback failure must remain visible to VM supervisor");
                var shutdown = assertThrows(IllegalStateException.class,
                        scheduler::close);
                assertTrue(shutdown.getMessage().contains("trusted native quiescence"),
                        "close must report the failed cleanup callback");
                assertEquals(1, calls.get(), "shutdown must not replay failed cleanup");
                kernel.stop();
            }
        }
    }

    @Test
    void nativeActorWitnessRejectsMultiplePhysicalSchedulerLanes() {
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(16)) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresScheduler.managedWithNativeTurnWitness(
                            2, Runnable::run, kernel));
            kernel.stop();
        }
    }

    @Test
    void nativeRetryRefusesToReportIdleWhileTrustedQuiescenceIsRunning() throws Exception {
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(64)) {
            try (OresScheduler scheduler = OresScheduler.managedWithNativeTurnWitness(
                    1, Runnable::run, kernel)) {
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                var calls = new AtomicInteger();
                OresFuture<Integer> result = scheduler.start(
                        resume -> OresScheduler.done(321),
                        () -> {
                            calls.incrementAndGet();
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS)) {
                                    throw new IllegalStateException(
                                            "trusted quiescence release not signaled");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                        });
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertFalse(result.isDone());
                    var incomplete = assertThrows(IllegalStateException.class,
                            scheduler::retryNativeQuiescentTasks);
                    assertTrue(incomplete.getMessage().contains(
                            "trusted quiescence is still running"));
                    assertFalse(result.isDone(),
                            "retry must not publish DONE while callback is in flight");
                    assertEquals(1, calls.get(), "retry must not reenter callback");
                } finally {
                    release.countDown();
                }
                assertEquals(321, result.get(5, TimeUnit.SECONDS));
                assertEquals(0, scheduler.retryNativeQuiescentTasks());
                assertEquals(1, calls.get());
                kernel.stop();
            }
        }
    }

    @Test
    void nativeTerminalPublicationWaitsForTrustedQuiescenceCallback() throws Exception {
        try (NativeActorTurnKernel kernel = NativeActorTurnKernel.open(64)) {
            try (OresScheduler scheduler = OresScheduler.managedWithNativeTurnWitness(
                    1, Runnable::run, kernel)) {
                var entered = new CountDownLatch(1);
                var release = new CountDownLatch(1);
                OresFuture<Integer> result = scheduler.start(
                        resume -> OresScheduler.done(123),
                        () -> {
                            entered.countDown();
                            try {
                                if (!release.await(5, TimeUnit.SECONDS)) {
                                    throw new AssertionError("quiescence test callback timed out");
                                }
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new AssertionError(interrupted);
                            }
                        });
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertFalse(result.isDone(),
                            "terminal Future published before trusted quiescence finished");
                    assertEquals(1, liveTaskCountForTest(scheduler),
                            "native task left live set before trusted cleanup callback");
                } finally {
                    release.countDown();
                }
                assertEquals(123, result.get(5, TimeUnit.SECONDS));
                assertEquals(0, liveTaskCountForTest(scheduler),
                        "native task stayed live after terminal callback");
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
