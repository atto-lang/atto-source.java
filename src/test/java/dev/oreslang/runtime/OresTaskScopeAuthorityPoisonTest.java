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
        OresScheduler.CompilerTaskHandle<?> schedulerChild;
        RuntimeException bindFailure;
        RuntimeException childBindFailure;
        RuntimeException schedulerBindFailure;
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
            if (childBindFailure != null) throw childBindFailure;
        }

        @Override
        public void bindSchedulerChild(
                OresTaskScope.CompilerScopeEpoch value,
                OresTask<?> task,
                OresScheduler.CompilerTaskHandle<?> exactSchedulerChild) {
            assertSame(epoch, value);
            assertSame(child, task);
            if (schedulerBindFailure != null) throw schedulerBindFailure;
            schedulerChild = exactSchedulerChild;
        }

        @Override
        public void retireBeforeChildBound(
                OresTaskScope.CompilerScopeEpoch value) {
            assertSame(epoch, value);
            assertNull(child);
            if (retireFailure != null) throw retireFailure;
        }

        @Override
        public void retireBeforeSchedulerChildBound(
                OresTaskScope.CompilerScopeEpoch value, OresTask<?> task) {
            assertSame(epoch, value);
            assertSame(child, task);
            assertNull(schedulerChild);
            if (retireFailure != null) throw retireFailure;
        }

        @Override
        public void retireNeverStarted(
                OresTaskScope.CompilerScopeEpoch value,
                OresTask<?> task,
                OresScheduler.CompilerTaskHandle<?> exactSchedulerChild) {
            assertSame(epoch, value);
            assertSame(child, task);
            assertSame(schedulerChild, exactSchedulerChild);
            if (retireFailure != null) throw retireFailure;
        }

        @Override
        public void retireAfterPhysicalQuiescence(
                OresTaskScope.CompilerScopeEpoch value,
                OresTask<?> task,
                OresScheduler.CompilerTaskHandle<?> exactSchedulerChild) {
            assertSame(epoch, value);
            assertSame(child, task);
            assertSame(schedulerChild, exactSchedulerChild);
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
    void partialOwnerBindFailurePinsExactLoanAuthority() {
        try (OresScheduler scheduler = new OresScheduler(1);
             AsyncRuntime runtime = new AsyncRuntime()) {
            OresTaskScope scope = new OresTaskScope(runtime);
            Loan loan = new Loan();
            RuntimeException failure =
                    new RuntimeException("owner bind poison");
            loan.childBindFailure = failure;

            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> scope.startCompilerFrame(
                            scheduler,
                            resume -> new OresScheduler.Done<>(1),
                            () -> { },
                            loan)));
            assertNotNull(loan.child,
                    "fault injection partially accepted the owner identity");
            assertEquals(1, scope.failedCompilerAuthoritiesForTesting());
            assertFalse(scope.physicallyQuiescent(),
                    "partial owner bind must pin compiler authority");
            assertThrows(IllegalStateException.class, scope::close);
        }
    }

    @Test
    void partialSchedulerChildBindFailurePinsExactLoanAuthority() {
        try (OresScheduler scheduler = new OresScheduler(1);
             AsyncRuntime runtime = new AsyncRuntime()) {
            OresTaskScope scope = new OresTaskScope(runtime);
            Loan loan = new Loan();
            RuntimeException failure =
                    new RuntimeException("scheduler child bind poison");
            loan.schedulerBindFailure = failure;

            assertSame(failure, assertThrows(RuntimeException.class,
                    () -> scope.startCompilerFrame(
                            scheduler,
                            resume -> new OresScheduler.Done<>(1),
                            () -> { },
                            loan)));
            assertNotNull(loan.child);
            assertNull(loan.schedulerChild,
                    "fault injection threw before recording committed scheduler authority");
            assertEquals(1, scope.failedCompilerAuthoritiesForTesting());
            assertFalse(scope.physicallyQuiescent(),
                    "partial scheduler bind must not regress to owner-only rollback");
            assertThrows(IllegalStateException.class, scope::close);
        }
    }

    @Test
    void schedulerChildBindAndRollbackFailurePinsLoanAndOwner() {
        try (OresScheduler scheduler = new OresScheduler(1);
             AsyncRuntime runtime = new AsyncRuntime()) {
            OresTaskScope scope = new OresTaskScope(runtime);
            Loan loan = new Loan();
            RuntimeException bindFailure =
                    new RuntimeException("scheduler child bind failed");
            RuntimeException rollbackFailure =
                    new RuntimeException("pre-scheduler rollback failed");
            loan.schedulerBindFailure = bindFailure;
            loan.retireFailure = rollbackFailure;

            RuntimeException observed = assertThrows(
                    RuntimeException.class,
                    () -> scope.startCompilerFrame(
                            scheduler,
                            resume -> new OresScheduler.Done<>(1),
                            () -> { },
                            loan));

            assertSame(bindFailure, observed);
            assertNotNull(loan.child);
            assertNull(loan.schedulerChild);
            assertEquals(1, scope.failedCompilerAuthoritiesForTesting());
            assertFalse(scope.physicallyQuiescent(),
                    "failed pre-scheduler rollback must retain authority");
            assertThrows(IllegalStateException.class, scope::close);
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
