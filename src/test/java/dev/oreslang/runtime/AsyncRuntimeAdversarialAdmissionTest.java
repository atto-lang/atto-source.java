package dev.oreslang.runtime;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic runtime source tests, not independent model substitutes.
 * Force failures from ExecutorService.execute AFTER TaskExit registration.
 */
@Timeout(20)
final class AsyncRuntimeAdversarialAdmissionTest {
    static final class FaultExecutor extends AbstractExecutorService {
        final CountDownLatch submitted = new CountDownLatch(1);
        final CountDownLatch admit = new CountDownLatch(1);
        final RuntimeException injected;
        volatile boolean closed;

        FaultExecutor(RuntimeException injected) {
            this.injected = injected;
        }

        @Override public void execute(Runnable command) {
            submitted.countDown();
            boolean interrupted = false;
            for (;;) {
                try {
                    admit.await();
                    break;
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            throw injected;
        }

        @Override public void shutdown() {
            closed = true;
            admit.countDown();
        }

        @Override public List<Runnable> shutdownNow() {
            shutdown();
            return List.of();
        }

        @Override public boolean isShutdown() { return closed; }
        @Override public boolean isTerminated() { return closed; }

        @Override public boolean awaitTermination(long timeout, TimeUnit unit) {
            return closed;
        }
    }

    @Test
    void closeDuringBlockedRejectedAdmissionRetiresExactlyOnce() throws Exception {
        var executor = new FaultExecutor(
                new RejectedExecutionException("injected executor rejection"));
        try (var runtime = new AsyncRuntime(AsyncRuntime.TurnExecutor.direct(), executor)) {
            var admission = runtime.<Integer>prepareTaskAdmission();
            var releases = new AtomicInteger();
            var failure = new AtomicReference<Throwable>();
            admission.installRetirement(releases::incrementAndGet);

            Thread submitter = Thread.ofVirtual().start(() -> {
                try {
                    runtime.submitPreparedTask(admission, () -> 11);
                } catch (Throwable rejected) {
                    failure.set(rejected);
                }
            });
            try {
                assertTrue(executor.submitted.await(3, TimeUnit.SECONDS),
                        "submit never reached controlled executor admission");
                // Future already exists in the runtime map, but the
                // executor has not returned a scheduled carrier.
                runtime.close();
            } finally {
                executor.admit.countDown();
                submitter.join(3000);
            }
            assertFalse(submitter.isAlive());
            assertInstanceOf(RejectedExecutionException.class, failure.get());
            assertNull(AsyncRuntime.await(admission.owner.getDone()));
            assertEquals(1, releases.get(),
                    "shutdown and rejection must not both retire owner");
        }
    }

    @Test
    void nonRejectionAdmissionFailureAlsoSettlesPreparedPhysicalFence() {
        var executor = new FaultExecutor(
                new IllegalStateException("injected executor admission fault"));
        executor.admit.countDown();
        try (var runtime = new AsyncRuntime(AsyncRuntime.TurnExecutor.direct(), executor)) {
            var admission = runtime.<Integer>prepareTaskAdmission();
            var releases = new AtomicInteger();
            admission.installRetirement(releases::incrementAndGet);
            var error = assertThrows(IllegalStateException.class,
                    () -> runtime.submitPreparedTask(admission, () -> 12));
            assertTrue(error.getMessage().contains("injected executor admission fault"));
            assertNull(AsyncRuntime.await(admission.owner.getDone()));
            assertEquals(1, releases.get());
        }
    }

    @Test
    void failedRetirementPreservesRejectionAndFailsPhysicalFence() {
        var executor = new FaultExecutor(
                new RejectedExecutionException("injected rejection"));
        executor.admit.countDown();
        try (var runtime = new AsyncRuntime(AsyncRuntime.TurnExecutor.direct(), executor)) {
            var admission = runtime.<Integer>prepareTaskAdmission();
            var releases = new AtomicInteger();
            admission.installRetirement(() -> {
                releases.incrementAndGet();
                throw new IllegalStateException("injected cleanup failure");
            });
            assertThrows(RejectedExecutionException.class,
                    () -> runtime.submitPreparedTask(admission, () -> 13));
            var failure = assertThrows(IllegalStateException.class,
                    () -> AsyncRuntime.await(admission.owner.getDone()));
            assertTrue(failure.toString().contains("injected cleanup failure"));
            assertEquals(1, releases.get(), "retirement failure must not retry");
        }
    }
}
