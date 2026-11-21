package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class OresTaskScopeAuthorityPoisonTest {
    private static final class Loan
            implements OresTaskScope.CompilerLoanRetirement {
        OresTaskScope.CompilerScopeEpoch epoch;
        OresTask<?> child;
        RuntimeException bindFailure;
        RuntimeException retireFailure;
        int physicalCalls;

        @Override
        public void bindScope(OresTaskScope.CompilerScopeEpoch value) {
            epoch = value;
            if (bindFailure != null) throw bindFailure;
        }

        @Override
        public void bindChild(
                OresTaskScope.CompilerScopeEpoch value, OresTask<?> task) {
            assertSame(epoch, value);
            child = task;
        }

        @Override
        public void retireBeforeChildBound(
                OresTaskScope.CompilerScopeEpoch value) {
            assertSame(epoch, value);
            assertNull(child);
            if (retireFailure != null) throw retireFailure;
        }

        @Override
        public void retireNeverStarted(
                OresTaskScope.CompilerScopeEpoch value, OresTask<?> task) {
            assertSame(epoch, value);
            assertSame(child, task);
            if (retireFailure != null) throw retireFailure;
        }

        @Override
        public void retireAfterPhysicalQuiescence(
                OresTaskScope.CompilerScopeEpoch value, OresTask<?> task) {
            assertSame(epoch, value);
            assertSame(child, task);
            physicalCalls++;
            if (retireFailure != null) throw retireFailure;
        }
    }

    @Test
    void partialScopeBindFailurePermanentlyPoisonsQuiescence() {
        try (OresScheduler scheduler = new OresScheduler(1);
             AsyncRuntime runtime = new AsyncRuntime()) {
            OresTaskScope scope = new OresTaskScope(runtime);
            Loan loan = new Loan();
            RuntimeException failure = new RuntimeException("bind poison");
            loan.bindFailure = failure;

            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> scope.startCompilerFrame(
                            scheduler,
                            resume -> new OresScheduler.Done<>(1),
                            () -> { },
                            loan)));
            assertEquals(1, scope.failedCompilerAuthoritiesForTesting());
            assertFalse(scope.physicallyQuiescent());
            assertThrows(IllegalStateException.class, scope::close);
            assertFalse(scope.physicallyQuiescent());
        }
    }

    @Test
    void physicalCleanupFailurePinsCleanupAndStillLiveLoan() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1);
             AsyncRuntime runtime = new AsyncRuntime()) {
            OresTaskScope scope = new OresTaskScope(runtime);
            Loan loan = new Loan();
            RuntimeException failure = new RuntimeException("cleanup poison");

            OresTask<Integer> child = scope.startCompilerFrame(
                    scheduler,
                    resume -> new OresScheduler.Done<>(2),
                    () -> { throw failure; },
                    loan);
            assertThrows(IllegalStateException.class,
                    () -> AsyncRuntime.await(child.getDone()));

            assertEquals(0, loan.physicalCalls,
                    "loan cannot retire after prerequisite cleanup failure");
            assertEquals(2, scope.failedCompilerAuthoritiesForTesting(),
                    "exact cleanup authority and exact live loan must remain pinned");
            assertFalse(scope.physicallyQuiescent());
            assertThrows(IllegalStateException.class, scope::close);
        }
    }

    @Test
    void physicalLoanFailurePinsLoanAfterCleanupSucceeded() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1);
             AsyncRuntime runtime = new AsyncRuntime()) {
            OresTaskScope scope = new OresTaskScope(runtime);
            Loan loan = new Loan();
            loan.retireFailure = new RuntimeException("loan poison");
            java.util.concurrent.atomic.AtomicBoolean cleaned =
                    new java.util.concurrent.atomic.AtomicBoolean();

            OresTask<Integer> child = scope.startCompilerFrame(
                    scheduler,
                    resume -> new OresScheduler.Done<>(3),
                    () -> cleaned.set(true),
                    loan);
            assertThrows(IllegalStateException.class,
                    () -> AsyncRuntime.await(child.getDone()));

            assertTrue(cleaned.get());
            assertEquals(1, loan.physicalCalls);
            assertEquals(1, scope.failedCompilerAuthoritiesForTesting());
            assertFalse(scope.physicallyQuiescent());
            assertThrows(IllegalStateException.class, scope::close);
        }
    }
    @Test
    void preDispatchLoanRollbackFailureRetainsAuthorityAndOwnerPin() {
        OresScheduler scheduler = new OresScheduler(1);
        scheduler.close();
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            OresTaskScope scope = new OresTaskScope(runtime);
            Loan loan = new Loan();
            RuntimeException failure = new RuntimeException("rollback poison");
            loan.retireFailure = failure;

            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> scope.startCompilerFrame(
                            scheduler,
                            resume -> new OresScheduler.Done<>(4),
                            () -> { },
                            loan));

            assertEquals(1, scope.failedCompilerAuthoritiesForTesting());
            assertNotNull(loan.child);
            assertFalse(scope.physicallyQuiescent());
            assertThrows(IllegalStateException.class, scope::close);
            assertFalse(scope.physicallyQuiescent());
        }
    }

}
