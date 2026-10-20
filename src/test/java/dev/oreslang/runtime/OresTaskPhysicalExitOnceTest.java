package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Exact-once code/region retirement across physical exit and failed admission. */
@Timeout(20)
final class OresTaskPhysicalExitOnceTest {

    @Test
    void manyConcurrentCancellationAndCompletionPathsRetireEachPinOnce() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(4);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            final int count = 96;
            AtomicInteger releases = new AtomicInteger();
            List<OresTask<Integer>> children = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                OresTask<Integer> child = scope.startCompilerFrame(
                        scheduler, resume -> new OresScheduler.Done<>(index),
                        releases::incrementAndGet);
                children.add(child);
                if ((i & 1) == 0) child.cancel();
            }
            for (OresTask<Integer> child : children) {
                assertNull(AsyncRuntime.await(child.getDone()));
                assertTrue(child.isQuiescent());
            }
            assertEquals(count, releases.get(),
                    "each scheduler physical exit must retire exactly one pin");
            scope.cancel();
            assertEquals(count, releases.get(),
                    "cancellation after quiescence must not release a pin twice");
        }
    }

    @Test
    void rejectedAdmissionAndSubsequentCloseCannotRetireTwice() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            assertTrue(scope.cancel());
            AtomicInteger releases = new AtomicInteger();
            assertThrows(RejectedExecutionException.class,
                    () -> scope.startCompilerFrame(scheduler,
                            resume -> new OresScheduler.Done<>(1),
                            releases::incrementAndGet));
            assertEquals(1, releases.get());
            scope.close();
            assertEquals(1, releases.get());
        }
    }

    @Test
    void malformedSchedulerOrFrameStillRetiresPreacquiredPin() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            AtomicInteger releases = new AtomicInteger();
            assertThrows(NullPointerException.class,
                    () -> scope.startCompilerFrame(null,
                            resume -> new OresScheduler.Done<>(1),
                            releases::incrementAndGet));
            assertThrows(NullPointerException.class,
                    () -> scope.startCompilerFrame(scheduler, null,
                            releases::incrementAndGet));
            assertEquals(2, releases.get(),
                    "both rejected admissions must retire their pins");
            assertDoesNotThrow(scope::close);
            assertEquals(2, releases.get(),
                    "closed scope must not retry already retired pins");
        }
    }

    @Test
    void malformedFrameKeepsCleanupFailureAttachedToOriginalError() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresTaskScope scope = new OresTaskScope(runtime)) {
            AtomicInteger releases = new AtomicInteger();
            NullPointerException invalid = assertThrows(NullPointerException.class,
                    () -> scope.startCompilerFrame(null,
                            resume -> new OresScheduler.Done<>(1),
                            () -> {
                                releases.incrementAndGet();
                                throw new IllegalStateException("cleanup rejected");
                            }));
            assertEquals(1, releases.get());
            assertEquals(1, invalid.getSuppressed().length);
            assertTrue(invalid.getSuppressed()[0].toString().contains("cleanup rejected"));
        }
    }

    @Test
    void duplicateWhileRetirementIsRunningFailsClosedInsteadOfCompletingFence()
            throws Exception {
        CountDownLatch retiring = new CountDownLatch(1);
        CountDownLatch mayRetire = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        var once = new OresTaskScope.PhysicalExitOnce(() -> {
            callbacks.incrementAndGet();
            retiring.countDown();
            try {
                assertTrue(mayRetire.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        });
        Thread owner = new Thread(once::run, "test-physical-exit-owner");
        owner.start();
        try {
            assertTrue(retiring.await(5, TimeUnit.SECONDS));
            IllegalStateException incomplete =
                    assertThrows(IllegalStateException.class, once::run);
            assertTrue(incomplete.getMessage().contains("not yet completed"));
            assertEquals(1, callbacks.get());
        } finally {
            mayRetire.countDown();
            owner.join(5_000);
        }
        assertFalse(owner.isAlive());
        assertDoesNotThrow(once::run);
        assertEquals(1, callbacks.get(), "successful retirement must be idempotent");
    }

    @Test
    void failedRetirementCannotLaterBeMistakenForSuccess() {
        AtomicInteger invocations = new AtomicInteger();
        var failure = new IllegalStateException("resource still pinned");
        var once = new OresTaskScope.PhysicalExitOnce(() -> {
            invocations.incrementAndGet();
            throw failure;
        });
        assertSame(failure, assertThrows(IllegalStateException.class, once::run));
        var repeat = assertThrows(IllegalStateException.class, once::run);
        assertSame(failure, repeat.getCause());
        assertEquals(1, invocations.get(), "failed retirement cannot be replayed");
    }

    @Test
    void failedPhysicalRetirementIsObservedAndNeverRetried() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1)) {
            OresTaskScope scope = new OresTaskScope(runtime);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            AtomicInteger invocations = new AtomicInteger();
            try {
                OresTask<Integer> task = scope.startCompilerFrame(scheduler,
                        resume -> {
                            entered.countDown();
                            assertTrue(finish.await(5, TimeUnit.SECONDS));
                            return new OresScheduler.Done<>(1);
                        }, () -> {
                            invocations.incrementAndGet();
                            throw new IllegalStateException("retirement failed");
                        });
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    assertTrue(task.cancel());
                    assertEquals(0, invocations.get(),
                            "cancellation is not physical retirement");
                } finally {
                    finish.countDown();
                }
                assertThrows(RuntimeException.class,
                        () -> AsyncRuntime.await(task.getDone()));
                assertEquals(1, invocations.get());
                assertThrows(RuntimeException.class, scope::close);
                assertEquals(1, invocations.get(),
                        "failed code-image release must not be retried on close");
            } finally {
                finish.countDown();
            }
        }
    }
}
