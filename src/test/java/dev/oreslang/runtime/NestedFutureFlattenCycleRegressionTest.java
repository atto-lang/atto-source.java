package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual nested-Future composition cycles, not only hand-written scheduler
 * await edges. All first-turn registration fences are FIFO executor barriers.
 */
final class NestedFutureFlattenCycleRegressionTest {
    private static final long DEADLOCK_GUARD_SECONDS = 5;

    @Test
    void flattenedFutureCannotResolveToItsOwnDerivedCompletion() throws Exception {
        OresFuture<OresFuture<Integer>> outer = new OresFuture<>();
        try (FencedScheduler scheduler = new FencedScheduler()) {
            OresFuture<Integer> derived = scheduler.start(flatten(outer));
            scheduler.fenceInitialTurn();
            assertEquals(1, outer.pendingRuntimeWaiterCount());

            assertTrue(outer.completeFromRuntime(derived));
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> derived.get(DEADLOCK_GUARD_SECONDS, TimeUnit.SECONDS));
            assertTrue(hasDiagnostic(failure, "cannot await its own completion Future"),
                    failure.toString());
            assertEquals(0, outer.pendingRuntimeWaiterCount());
            assertEquals(0, derived.pendingRuntimeWaiterCount());
            assertFalse(OresScheduler.hasAwaitDependency(derived));
            assertFalse(outer.isCancelled());
        }
    }

    @Test
    void twoFlattenedFuturesCannotFormIndirectCrossSchedulerCycle() throws Exception {
        OresFuture<OresFuture<Integer>> firstOuter = new OresFuture<>();
        OresFuture<OresFuture<Integer>> secondOuter = new OresFuture<>();

        try (FencedScheduler firstScheduler = new FencedScheduler();
             FencedScheduler secondScheduler = new FencedScheduler()) {
            OresFuture<Integer> first = firstScheduler.start(flatten(firstOuter));
            OresFuture<Integer> second = secondScheduler.start(flatten(secondOuter));
            firstScheduler.fenceInitialTurn();
            secondScheduler.fenceInitialTurn();
            assertEquals(1, firstOuter.pendingRuntimeWaiterCount());
            assertEquals(1, secondOuter.pendingRuntimeWaiterCount());

            // Both outer producers settle normally. The dependency cycle only
            // appears when the composed tasks observe each other's completion.
            assertTrue(firstOuter.completeFromRuntime(second));
            assertTrue(secondOuter.completeFromRuntime(first));

            ExecutionException firstFailure = assertThrows(ExecutionException.class,
                    () -> first.get(DEADLOCK_GUARD_SECONDS, TimeUnit.SECONDS));
            ExecutionException secondFailure = assertThrows(ExecutionException.class,
                    () -> second.get(DEADLOCK_GUARD_SECONDS, TimeUnit.SECONDS));
            assertTrue(
                    hasDiagnostic(firstFailure, "cyclic Future await dependency detected")
                            || hasDiagnostic(secondFailure, "cyclic Future await dependency detected"),
                    "indirect flattened cycle must be detected by a scheduler");
            assertEquals(0, firstOuter.pendingRuntimeWaiterCount());
            assertEquals(0, secondOuter.pendingRuntimeWaiterCount());
            assertEquals(0, first.pendingRuntimeWaiterCount());
            assertEquals(0, second.pendingRuntimeWaiterCount());
            assertFalse(OresScheduler.hasAwaitDependency(first));
            assertFalse(OresScheduler.hasAwaitDependency(second));
            assertFalse(firstOuter.isCancelled());
            assertFalse(secondOuter.isCancelled());
        }
    }

    private static OresScheduler.Task<Integer> flatten(
            OresFuture<OresFuture<Integer>> outer) {
        return new OresScheduler.Task<>() {
            private int phase;

            @Override
            public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                if (resume.failure() != null) {
                    throw new IllegalStateException("nested producer failed", resume.failure());
                }
                if (phase++ == 0) return OresScheduler.await(outer);
                if (phase == 2) return OresScheduler.await((OresFuture<?>) resume.value());
                return OresScheduler.done((Integer) resume.value());
            }
        };
    }

    private static boolean hasDiagnostic(Throwable thrown, String fragment) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (String.valueOf(cause.getMessage()).contains(fragment)) return true;
        }
        return false;
    }

    private static final class FencedScheduler implements AutoCloseable {
        private final ExecutorService carrier = Executors.newSingleThreadExecutor();
        private final OresScheduler scheduler =
                OresScheduler.runtimeOwned("nested-flatten-cycle-regression", 1, carrier);

        OresFuture<Integer> start(OresScheduler.Task<Integer> task) {
            return scheduler.start(task);
        }

        void fenceInitialTurn() throws Exception {
            carrier.submit(() -> { }).get(DEADLOCK_GUARD_SECONDS, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            try {
                scheduler.close();
            } finally {
                carrier.shutdownNow();
                assertTrue(carrier.awaitTermination(DEADLOCK_GUARD_SECONDS, TimeUnit.SECONDS));
            }
        }
    }
}
