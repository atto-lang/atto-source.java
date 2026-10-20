package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class OresTaskScopeLoanBindingTest {
    private static final class RecordingLoan
            implements OresTaskScope.CompilerLoanRetirement {
        OresTaskScope.CompilerScopeEpoch scopeEpoch;
        OresTask<?> child;
        OresScheduler.CompilerTaskHandle<?> schedulerChild;
        final AtomicInteger preChild = new AtomicInteger();
        final AtomicInteger preScheduler = new AtomicInteger();
        final AtomicInteger neverStarted = new AtomicInteger();
        final AtomicInteger physical = new AtomicInteger();

        @Override
        public synchronized void bindScope(
                OresTaskScope.CompilerScopeEpoch epoch) {
            assertNull(scopeEpoch, "scope epoch may be bound once");
            scopeEpoch = epoch;
        }

        @Override
        public synchronized void bindChild(
                OresTaskScope.CompilerScopeEpoch epoch, OresTask<?> task) {
            assertSame(scopeEpoch, epoch);
            assertNull(child, "child may be bound once");
            child = task;
        }

        @Override
        public synchronized void bindSchedulerChild(
                OresTaskScope.CompilerScopeEpoch epoch,
                OresTask<?> task,
                OresScheduler.CompilerTaskHandle<?> exactSchedulerChild) {
            assertSame(scopeEpoch, epoch);
            assertSame(child, task);
            assertNull(schedulerChild, "scheduler child may be bound once");
            schedulerChild = exactSchedulerChild;
        }

        @Override
        public synchronized void retireBeforeChildBound(
                OresTaskScope.CompilerScopeEpoch epoch) {
            assertSame(scopeEpoch, epoch);
            assertNull(child,
                    "pre-child retirement is valid only before child binding");
            assertEquals(0, physical.get());
            preChild.incrementAndGet();
        }

        @Override
        public synchronized void retireBeforeSchedulerChildBound(
                OresTaskScope.CompilerScopeEpoch epoch, OresTask<?> task) {
            assertSame(scopeEpoch, epoch);
            assertSame(child, task);
            assertNull(schedulerChild,
                    "pre-scheduler retirement cannot own a scheduler child");
            assertEquals(0, physical.get());
            preScheduler.incrementAndGet();
        }

        @Override
        public synchronized void retireNeverStarted(
                OresTaskScope.CompilerScopeEpoch epoch,
                OresTask<?> task,
                OresScheduler.CompilerTaskHandle<?> exactSchedulerChild) {
            assertSame(scopeEpoch, epoch);
            assertSame(child, task);
            assertSame(schedulerChild, exactSchedulerChild,
                    "never-started retirement must carry the exact scheduler child");
            assertEquals(0, physical.get());
            neverStarted.incrementAndGet();
        }

        @Override
        public synchronized void retireAfterPhysicalQuiescence(
                OresTaskScope.CompilerScopeEpoch epoch,
                OresTask<?> task,
                OresScheduler.CompilerTaskHandle<?> exactSchedulerChild) {
            assertSame(scopeEpoch, epoch);
            assertSame(child, task);
            assertSame(schedulerChild, exactSchedulerChild,
                    "physical retirement must carry the exact scheduler child");
            assertEquals(0, neverStarted.get());
            physical.incrementAndGet();
        }
    }

    @Test
    void successfulCompilerFrameRetiresExactBoundChildOnlyAtPhysicalExit()
            throws Exception {
        RecordingLoan loan = new RecordingLoan();
        try (OresScheduler scheduler = new OresScheduler(1);
             AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresTask<Integer> child = scope.startCompilerFrame(
                    scheduler,
                    resume -> new OresScheduler.Done<>(7),
                    () -> { },
                    loan);

            assertSame(child, loan.child);
            assertNotNull(loan.schedulerChild);
            assertNull(AsyncRuntime.await(child.getDone()));
            assertEquals(0, loan.neverStarted.get());
            assertEquals(1, loan.physical.get());
            assertTrue(scope.physicallyQuiescent());
        }
    }

    @Test
    void schedulerRejectionUsesNeverStartedEdgeNotPhysicalRetirement()
            throws Exception {
        RecordingLoan loan = new RecordingLoan();
        OresScheduler scheduler = new OresScheduler(1);
        scheduler.close();
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> scope.startCompilerFrame(
                            scheduler,
                            resume -> new OresScheduler.Done<>(7),
                            () -> { },
                            loan));

            assertNotNull(loan.child,
                    "provisional child identity is bound before scheduler admission");
            assertNotNull(loan.schedulerChild);
            assertEquals(0, loan.preChild.get());
            assertEquals(0, loan.preScheduler.get());
            assertEquals(1, loan.neverStarted.get());
            assertEquals(0, loan.physical.get());
            assertTrue(scope.physicallyQuiescent());
        }
    }

    @Test
    void registeredExecutorRejectionUsesSchedulerQuiescenceEdge() {
        RecordingLoan loan = new RecordingLoan();
        AtomicInteger entered = new AtomicInteger();
        OresScheduler scheduler = OresScheduler.runtimeOwned(
                "registered-rejection-loan-test", 1,
                command -> { throw new java.util.concurrent.RejectedExecutionException(
                        "executor rejected after scheduler registration"); });
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresTask<Integer> child = scope.startCompilerFrame(
                    scheduler,
                    resume -> {
                        entered.incrementAndGet();
                        return new OresScheduler.Done<>(17);
                    },
                    () -> { },
                    loan);

            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> AsyncRuntime.await(child.getAwaited()));
            assertNull(AsyncRuntime.await(child.getDone()));
            assertEquals(0, entered.get());
            assertNotNull(loan.schedulerChild);
            assertEquals(0, loan.neverStarted.get(),
                    "registered TaskRunner rejection is not the direct start-throw edge");
            assertEquals(1, loan.physical.get(),
                    "scheduler quiescence authenticates registered rejection");
            assertTrue(scope.physicallyQuiescent());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void rejectedAdmissionCleanupFailureKeepsNeverStartedLoanPinned() {
        RecordingLoan loan = new RecordingLoan();
        OresScheduler scheduler = new OresScheduler(1);
        scheduler.close();
        AsyncRuntime runtime = new AsyncRuntime();
        OresTaskScope scope = new OresTaskScope(runtime);
        try {
            assertThrows(java.util.concurrent.RejectedExecutionException.class,
                    () -> scope.startCompilerFrame(
                            scheduler,
                            resume -> new OresScheduler.Done<>(13),
                            () -> { throw new IllegalStateException(
                                    "rejected cleanup failed"); },
                            loan));
            assertEquals(0, loan.neverStarted.get(),
                    "cleanup must succeed before never-started loan release");
            assertEquals(0, loan.physical.get());
            assertFalse(scope.physicallyQuiescent(),
                    "failed rejected-admission cleanup keeps owner pin");
            assertThrows(IllegalStateException.class, scope::close);
        } finally {
            runtime.close();
        }
    }

    @Test
    void resourceCleanupFailureDoesNotReleaseCompilerLoan() {
        RecordingLoan loan = new RecordingLoan();
        OresScheduler scheduler = new OresScheduler(1);
        AsyncRuntime runtime = new AsyncRuntime();
        OresTaskScope scope = new OresTaskScope(runtime);
        OresTask<Integer> child = scope.startCompilerFrame(
                scheduler,
                resume -> new OresScheduler.Done<>(11),
                () -> { throw new IllegalStateException("resource retirement failed"); },
                loan);

        assertThrows(IllegalStateException.class,
                () -> AsyncRuntime.await(child.getDone()));
        assertEquals(0, loan.neverStarted.get());
        assertEquals(0, loan.physical.get(),
                "failed external retirement cannot return borrowed memory");
        assertFalse(scope.physicallyQuiescent(),
                "failed retirement retains the runtime owner slot");
        assertThrows(IllegalStateException.class, scope::close);
        scheduler.close();
        runtime.close();
    }

    @Test
    void cancellationRequestCannotRetireLoanWhileGuestTurnStillRuns()
            throws Exception {
        RecordingLoan loan = new RecordingLoan();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (OresScheduler scheduler = new OresScheduler(1);
             AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            OresTask<Integer> child = scope.startCompilerFrame(
                    scheduler,
                    resume -> {
                        entered.countDown();
                        boolean interrupted = false;
                        try {
                            while (!release.await(50, TimeUnit.MILLISECONDS)) {
                                // Keep the guest turn physically live even if
                                // cancellation interrupts a compatibility carrier.
                            }
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                            while (release.getCount() != 0) Thread.onSpinWait();
                        } finally {
                            if (interrupted) Thread.currentThread().interrupt();
                        }
                        return new OresScheduler.Done<>(9);
                    },
                    () -> { },
                    loan);

            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(scope.cancel());
            assertEquals(0, loan.neverStarted.get());
            assertEquals(0, loan.physical.get(),
                    "cancellation/result settlement is not a release witness");
            assertFalse(child.isQuiescent());

            release.countDown();
            assertNull(AsyncRuntime.await(child.getDone()));
            assertEquals(1, loan.physical.get());
            assertTrue(child.isQuiescent());
        } finally {
            release.countDown();
        }
    }
}
