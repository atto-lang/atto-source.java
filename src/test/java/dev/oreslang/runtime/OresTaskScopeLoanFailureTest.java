package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class OresTaskScopeLoanFailureTest {
    private static final class FailingLoan
            implements OresTaskScope.CompilerLoanRetirement {
        OresTaskScope.CompilerScopeEpoch epoch;
        OresTask<?> child;
        RuntimeException bindScopeFailure;
        RuntimeException neverStartedFailure;
        RuntimeException physicalFailure;
        int neverStartedCalls;
        int physicalCalls;

        @Override
        public void bindScope(OresTaskScope.CompilerScopeEpoch scopeEpoch) {
            assertNull(epoch);
            epoch = scopeEpoch;
            if (bindScopeFailure != null) throw bindScopeFailure;
        }

        @Override
        public void bindChild(
                OresTaskScope.CompilerScopeEpoch scopeEpoch,
                OresTask<?> exactChild) {
            assertSame(epoch, scopeEpoch);
            assertNull(child);
            child = exactChild;
        }

        @Override
        public void retireNeverStarted(
                OresTaskScope.CompilerScopeEpoch scopeEpoch) {
            assertSame(epoch, scopeEpoch);
            neverStartedCalls++;
            if (neverStartedFailure != null) throw neverStartedFailure;
        }

        @Override
        public void retireAfterPhysicalQuiescence(
                OresTaskScope.CompilerScopeEpoch scopeEpoch,
                OresTask<?> exactChild) {
            assertSame(epoch, scopeEpoch);
            assertSame(child, exactChild);
            physicalCalls++;
            if (physicalFailure != null) throw physicalFailure;
        }
    }

    @Test
    void partialScopeBindingFailurePinsExactLoanWithoutTaskSlot() {
        try (OresScheduler scheduler = new OresScheduler(1);
             AsyncRuntime runtime = new AsyncRuntime()) {
            OresTaskScope scope = new OresTaskScope(runtime);
            RuntimeException bindingFailure =
                    new RuntimeException("scope binding failed after accepting epoch");
            FailingLoan loan = new FailingLoan();
            loan.bindScopeFailure = bindingFailure;

            RuntimeException observed =
                    assertThrows(
                            RuntimeException.class,
                            () -> scope.startCompilerFrame(
                                    scheduler,
                                    resume -> new OresScheduler.Done<>(1),
                                    () -> { },
                                    loan));

            assertSame(bindingFailure, observed);
            assertNotNull(loan.epoch);
            assertNull(loan.child);
            assertSame(bindingFailure, scope.compilerAuthorityFailureForTesting());
            assertEquals(1, scope.failedCompilerAuthoritiesForTesting());
            assertFalse(scope.physicallyQuiescent());

            IllegalStateException closeFailure =
                    assertThrows(IllegalStateException.class, scope::close);
            assertSame(bindingFailure, closeFailure.getCause());
            assertFalse(scope.physicallyQuiescent());
        }
    }

    @Test
    void preparationLoanRollbackFailurePinsAuthorityBeforeSlotRemoval() {
        try (OresScheduler scheduler = new OresScheduler(1);
             AsyncRuntime runtime = new AsyncRuntime()) {
            OresTaskScope scope = new OresTaskScope(runtime);
            assertTrue(scope.cancel());

            RuntimeException loanFailure =
                    new RuntimeException("loan rollback failed");
            FailingLoan loan = new FailingLoan();
            loan.neverStartedFailure = loanFailure;

            RejectedExecutionException rejected =
                    assertThrows(
                            RejectedExecutionException.class,
                            () -> scope.startCompilerFrame(
                                    scheduler,
                                    resume -> new OresScheduler.Done<>(1),
                                    () -> { },
                                    loan));

            assertEquals(1, loan.neverStartedCalls);
            assertSame(loanFailure, scope.compilerAuthorityFailureForTesting());
            assertEquals(1, scope.failedCompilerAuthoritiesForTesting());
            assertFalse(scope.physicallyQuiescent());
            assertTrue(java.util.Arrays.asList(rejected.getSuppressed())
                    .contains(loanFailure));
            assertThrows(IllegalStateException.class, scope::close);
            assertFalse(scope.physicallyQuiescent());
        }
    }

    @Test
    void preparationResourceFailureRetainsStillLiveLoanAndResourceAuthority() {
        try (OresScheduler scheduler = new OresScheduler(1);
             AsyncRuntime runtime = new AsyncRuntime()) {
            OresTaskScope scope = new OresTaskScope(runtime);
            assertTrue(scope.cancel());

            RuntimeException releaseFailure =
                    new RuntimeException("resource cleanup failed");
            FailingLoan loan = new FailingLoan();
            Runnable release = () -> { throw releaseFailure; };

            RejectedExecutionException rejected =
                    assertThrows(
                            RejectedExecutionException.class,
                            () -> scope.startCompilerFrame(
                                    scheduler,
                                    resume -> new OresScheduler.Done<>(1),
                                    release,
                                    loan));

            assertEquals(0, loan.neverStartedCalls,
                    "memory loan must remain live when prerequisite cleanup fails");
            assertSame(releaseFailure, scope.compilerAuthorityFailureForTesting());
            assertEquals(2, scope.failedCompilerAuthoritiesForTesting(),
                    "scope must retain exact resource hook and exact still-live loan");
            assertFalse(scope.physicallyQuiescent());
            assertTrue(java.util.Arrays.asList(rejected.getSuppressed())
                    .contains(releaseFailure));
            assertThrows(IllegalStateException.class, scope::close);
            assertFalse(scope.physicallyQuiescent());
        }
    }

    @Test
    void schedulerRejectionKeepsRegisteredOwnerWhenLoanRollbackFails() {
        OresScheduler scheduler = new OresScheduler(1);
        scheduler.close();
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            OresTaskScope scope = new OresTaskScope(runtime);
            RuntimeException loanFailure =
                    new RuntimeException("never-started loan release failed");
            FailingLoan loan = new FailingLoan();
            loan.neverStartedFailure = loanFailure;

            RejectedExecutionException rejected =
                    assertThrows(
                            RejectedExecutionException.class,
                            () -> scope.startCompilerFrame(
                                    scheduler,
                                    resume -> new OresScheduler.Done<>(1),
                                    () -> { },
                                    loan));

            assertEquals(1, loan.neverStartedCalls);
            assertSame(loanFailure, scope.compilerAuthorityFailureForTesting());
            assertEquals(1, scope.failedCompilerAuthoritiesForTesting());
            assertFalse(scope.physicallyQuiescent());
            assertNotNull(loan.child);
            assertTrue(java.util.Arrays.asList(rejected.getSuppressed())
                    .contains(loanFailure));

            assertThrows(IllegalStateException.class, scope::close);
            assertFalse(scope.physicallyQuiescent());
        }
    }

    @Test
    void physicalLoanRetirementFailurePinsExactOwnerAfterResourceCleanup()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1);
             AsyncRuntime runtime = new AsyncRuntime()) {
            OresTaskScope scope = new OresTaskScope(runtime);
            RuntimeException loanFailure =
                    new RuntimeException("physical loan release failed");
            FailingLoan loan = new FailingLoan();
            loan.physicalFailure = loanFailure;
            AtomicBoolean resourceReleased = new AtomicBoolean();

            OresTask<Integer> child = scope.startCompilerFrame(
                    scheduler,
                    resume -> new OresScheduler.Done<>(7),
                    () -> resourceReleased.set(true),
                    loan);
            assertSame(child, loan.child);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (scope.compilerAuthorityFailureForTesting() == null
                    && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }

            assertTrue(resourceReleased.get(),
                    "resource cleanup must precede memory-loan return");
            assertEquals(1, loan.physicalCalls);
            assertSame(loanFailure, scope.compilerAuthorityFailureForTesting());
            assertEquals(1, scope.failedCompilerAuthoritiesForTesting());
            assertFalse(scope.physicallyQuiescent());
            assertThrows(IllegalStateException.class, scope::close);
            assertFalse(scope.physicallyQuiescent());
        }
    }

    @Test
    void physicalResourceFailureRetainsStillLiveLoanAndExactOwner()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1);
             AsyncRuntime runtime = new AsyncRuntime()) {
            OresTaskScope scope = new OresTaskScope(runtime);
            RuntimeException releaseFailure =
                    new RuntimeException("physical resource release failed");
            FailingLoan loan = new FailingLoan();
            Runnable release = () -> { throw releaseFailure; };

            OresTask<Integer> child = scope.startCompilerFrame(
                    scheduler,
                    resume -> new OresScheduler.Done<>(9),
                    release,
                    loan);
            assertSame(child, loan.child);

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (scope.compilerAuthorityFailureForTesting() == null
                    && System.nanoTime() < deadline) {
                Thread.sleep(1L);
            }

            assertEquals(0, loan.physicalCalls,
                    "memory loan must not retire after prerequisite cleanup failure");
            assertSame(releaseFailure, scope.compilerAuthorityFailureForTesting());
            assertEquals(2, scope.failedCompilerAuthoritiesForTesting());
            assertFalse(scope.physicallyQuiescent());
            assertThrows(IllegalStateException.class, scope::close);
            assertFalse(scope.physicallyQuiescent());
        }
    }
}
