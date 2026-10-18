package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scheduler-level contract used by nested Future flattening: two independent
 * suspension stages, never a synchronous producer-thread continuation.
 *
 * This exercises OresScheduler's two-await composition path; the language-level
 * Future.flatten member has separate integration tests.
 */
final class NestedFutureSchedulerContractTest {

    @Test
    void outerCompletionDoesNotPrematurelyResolvePendingInnerStage() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<OresFuture<Integer>> outer = new OresFuture<>();
            OresFuture<Integer> inner = new OresFuture<>();
            AtomicInteger turns = new AtomicInteger();
            OresFuture<Integer> flattened = twoStage(scheduler, outer, turns);

            awaitWaiters(outer, 1);
            outer.completeFromRuntime(inner);
            awaitWaiters(inner, 1);
            assertFalse(flattened.isDone(), "completing outer stage is not completing both stages");

            inner.completeFromRuntime(42);
            assertEquals(42, flattened.get(5, TimeUnit.SECONDS));
            assertEquals(3, turns.get(), "initial, outer and inner suspension turns");
            assertEquals(0, inner.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void innerStageFailureIsNotAValueOfTheFlattenedFuture() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<OresFuture<Integer>> outer = new OresFuture<>();
            OresFuture<Integer> inner = new OresFuture<>();
            OresFuture<Integer> flattened = twoStage(scheduler, outer, new AtomicInteger());

            awaitWaiters(outer, 1);
            outer.completeFromRuntime(inner);
            awaitWaiters(inner, 1);

            IllegalStateException innerError = new IllegalStateException("inner stage failed");
            inner.failFromRuntime(innerError);
            ExecutionException failed = assertThrows(ExecutionException.class,
                    () -> flattened.get(5, TimeUnit.SECONDS));
            assertSame(innerError, failed.getCause());
            assertEquals(0, inner.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void cancellationDuringPendingInnerStageDetachesWithoutCancellingItsProducer() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<OresFuture<Integer>> outer = new OresFuture<>();
            OresFuture<Integer> inner = new OresFuture<>();
            AtomicInteger turns = new AtomicInteger();
            OresFuture<Integer> flattened = twoStage(scheduler, outer, turns);

            awaitWaiters(outer, 1);
            outer.completeFromRuntime(inner);
            awaitWaiters(inner, 1);
            assertEquals(2, turns.get());

            assertTrue(flattened.cancel(false));
            awaitWaiters(inner, 0);
            assertTrue(flattened.isCancelled());
            assertFalse(inner.isDone(), "an independently owned producer is not auto-cancelled");

            inner.completeFromRuntime(17);
            assertEquals(2, turns.get(), "cancelled continuation must not re-enter guest execution");
        }
    }

    @Test
    void cancellationBeforeOuterCompletionNeverAdoptsInnerStage() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<OresFuture<Integer>> outer = new OresFuture<>();
            OresFuture<Integer> inner = new OresFuture<>();
            AtomicInteger turns = new AtomicInteger();
            OresFuture<Integer> flattened = twoStage(scheduler, outer, turns);

            awaitWaiters(outer, 1);
            assertTrue(flattened.cancel(false));
            awaitWaiters(outer, 0);
            outer.completeFromRuntime(inner);

            assertEquals(1, turns.get());
            assertEquals(0, inner.pendingRuntimeWaiterCount());
            assertFalse(inner.isCancelled());
        }
    }

    @Test
    void cancellationAndOuterCompletionRaceDoesNotRetainAwaiters() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2)) {
            for (int trial = 0; trial < 64; trial++) {
                OresFuture<OresFuture<Integer>> outer = new OresFuture<>();
                OresFuture<Integer> inner = new OresFuture<>();
                AtomicInteger turns = new AtomicInteger();
                OresFuture<Integer> joined = twoStage(scheduler, outer, turns);
                awaitWaiters(outer, 1);

                Thread producer = Thread.ofPlatform().start(() -> outer.completeFromRuntime(inner));
                joined.cancel(false);
                producer.join();

                awaitWaiters(outer, 0);
                awaitWaiters(inner, 0);
                assertTrue(joined.isCancelled());
                assertFalse(inner.isCancelled());
                inner.completeFromRuntime(trial);
                assertTrue(joined.isCancelled());
                assertTrue(turns.get() <= 2);
            }
        }
    }

    @Test
    void cancelledOuterStageRetainsCancellationIdentity() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<OresFuture<Integer>> outer = new OresFuture<>();
            OresFuture<Integer> joined = twoStage(scheduler, outer, new AtomicInteger());
            awaitWaiters(outer, 1);
            assertTrue(outer.cancel(false));
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> joined.get(5, TimeUnit.SECONDS));
            assertTrue(joined.isCancelled());
            assertEquals(0, outer.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void cancelledInnerStageRetainsCancellationIdentity() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<OresFuture<Integer>> outer = new OresFuture<>();
            OresFuture<Integer> inner = new OresFuture<>();
            OresFuture<Integer> joined = twoStage(scheduler, outer, new AtomicInteger());
            awaitWaiters(outer, 1);
            outer.completeFromRuntime(inner);
            awaitWaiters(inner, 1);
            assertTrue(inner.cancel(false));
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> joined.get(5, TimeUnit.SECONDS));
            assertTrue(joined.isCancelled());
            assertEquals(0, inner.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void failedOuterFutureWithCancellationExceptionMustStayFailed() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<OresFuture<Integer>> outer = new OresFuture<>();
            OresFuture<Integer> composed = twoStage(scheduler, outer, new AtomicInteger());
            awaitWaiters(outer, 1);
            java.util.concurrent.CancellationException failure =
                    new java.util.concurrent.CancellationException("failure payload, not cancellation");
            assertTrue(outer.failFromRuntime(failure));
            ExecutionException observed = assertThrows(ExecutionException.class,
                    () -> composed.get(5, TimeUnit.SECONDS));
            assertSame(failure, observed.getCause());
            assertFalse(composed.isCancelled());
        }
    }

    @Test
    void failedInnerFutureWithCancellationExceptionMustStayFailed() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<OresFuture<Integer>> outer = new OresFuture<>();
            OresFuture<Integer> inner = new OresFuture<>();
            OresFuture<Integer> composed = twoStage(scheduler, outer, new AtomicInteger());
            awaitWaiters(outer, 1);
            outer.completeFromRuntime(inner);
            awaitWaiters(inner, 1);
            java.util.concurrent.CancellationException failure =
                    new java.util.concurrent.CancellationException("inner failure, not cancellation");
            assertTrue(inner.failFromRuntime(failure));
            ExecutionException observed = assertThrows(ExecutionException.class,
                    () -> composed.get(5, TimeUnit.SECONDS));
            assertSame(failure, observed.getCause());
            assertFalse(composed.isCancelled());
        }
    }

    @Test
    void trustedCancelledStepDiffersFromThrownCancellationException() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> cancelled = scheduler.start(
                    resume -> OresScheduler.cancelled());
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> cancelled.get(5, TimeUnit.SECONDS));
            assertTrue(cancelled.isCancelled());

            java.util.concurrent.CancellationException original =
                    new java.util.concurrent.CancellationException("ordinary task failure");
            OresFuture<Integer> failed = scheduler.start(resume -> {
                throw original;
            });
            ExecutionException observed = assertThrows(ExecutionException.class,
                    () -> failed.get(5, TimeUnit.SECONDS));
            assertSame(original, observed.getCause());
            assertFalse(failed.isCancelled());
        }
    }

    @Test
    void trustedCancellationStepRetiresOnceAfterRunningTurnExits() throws Exception {
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch retired = new java.util.concurrent.CountDownLatch(1);
        AtomicInteger retirements = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> result = scheduler.start(resume -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return OresScheduler.cancelled();
            }, () -> {
                retirements.incrementAndGet();
                retired.countDown();
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(result.isDone(),
                        "cancelled step cannot publish before guest code exits");
                assertEquals(0, retirements.get(),
                        "resources remain owned while the guest turn is executing");
            } finally {
                release.countDown();
            }
            assertThrows(java.util.concurrent.CancellationException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertTrue(result.isCancelled());
            assertTrue(retired.await(5, TimeUnit.SECONDS));
            assertEquals(1, retirements.get(),
                    "explicit trusted cancellation must retire task resources exactly once");
        }
    }

    private static OresFuture<Integer> twoStage(
            OresScheduler scheduler,
            OresFuture<OresFuture<Integer>> outer,
            AtomicInteger turns) {
        java.util.concurrent.atomic.AtomicReference<OresFuture<?>> adoptedInner =
                new java.util.concurrent.atomic.AtomicReference<>();
        return scheduler.start(resume -> {
            int turn = turns.getAndIncrement();
            if (turn == 0) {
                assertTrue(resume.initial());
                return OresScheduler.await(outer);
            }
            if (resume.failure() != null
                    && (turn == 1 ? outer.isCancelled()
                    : adoptedInner.get() != null && adoptedInner.get().isCancelled())) {
                return OresScheduler.cancelled();
            }
            if (resume.failure() != null) {
                if (resume.failure() instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException(resume.failure());
            }
            if (turn == 1) {
                OresFuture<?> inner = (OresFuture<?>) resume.value();
                adoptedInner.set(inner);
                return OresScheduler.await(inner);
            }
            if (turn == 2) {
                return OresScheduler.done((Integer) resume.value());
            }
            throw new AssertionError("two-stage continuation resumed too many times");
        });
    }

    private static void awaitWaiters(OresFuture<?> future, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (future.pendingRuntimeWaiterCount() != count
                && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        assertEquals(count, future.pendingRuntimeWaiterCount(),
                "bounded await registration/detachment check");
    }
}
