package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Exact-runtime admission/retirement invariants for ordinary Task handles. */
@Timeout(20)
final class OresTaskRuntimeAdmissionTest {
    @Test
    void runtimeSubmissionBindsTheProvisionalShellAndPhysicalFence() {
        try (var runtime = new AsyncRuntime()) {
            OresTask<Integer> child = runtime.submitTask(() -> 41);
            assertEquals(41, AsyncRuntime.await(child.getAwaited()));
            assertNull(AsyncRuntime.await(child.getDone()));
            assertTrue(child.isQuiescent(),
                    "completed ordinary Task must report its real physical exit");
        }
    }

    @Test
    void scopedSubmissionBindsOwnerAndDoesNotLeakOnSuccess() {
        try (var runtime = new AsyncRuntime();
             var scope = new OresTaskScope(runtime)) {
            OresTask<Integer> child = scope.submit(() -> 42);
            assertEquals(42, AsyncRuntime.await(child.getAwaited()));
            assertNull(AsyncRuntime.await(child.getDone()));
            assertTrue(scope.physicallyQuiescent());
        }
    }

    @Test
    void canceledOrdinaryTaskCannotReleaseScopeWhileCarrierIsInFinally()
            throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch enteredCleanup = new CountDownLatch(1);
        CountDownLatch allowCleanup = new CountDownLatch(1);
        CountDownLatch closeFinished = new CountDownLatch(1);
        AtomicInteger cleaned = new AtomicInteger();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        try (var runtime = new AsyncRuntime();
             var scope = new OresTaskScope(runtime)) {
            OresTask<Integer> child = scope.submit(() -> {
                started.countDown();
                try {
                    Thread.sleep(30_000);
                    return 9;
                } finally {
                    enteredCleanup.countDown();
                    boolean interrupted = false;
                    for (;;) {
                        try {
                            allowCleanup.await();
                            break;
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                    if (interrupted) Thread.currentThread().interrupt();
                    cleaned.incrementAndGet();
                }
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            Thread closer = Thread.ofVirtual().start(() -> {
                try { scope.close(); }
                catch (Throwable failure) { closeFailure.set(failure); }
                finally { closeFinished.countDown(); }
            });
            try {
                assertTrue(enteredCleanup.await(3, TimeUnit.SECONDS));
                assertFalse(closeFinished.await(100, TimeUnit.MILLISECONDS),
                        "scope close must wait for physical cleanup after cancellation");
                assertFalse(child.isQuiescent());
            } finally {
                allowCleanup.countDown();
            }
            assertTrue(closeFinished.await(4, TimeUnit.SECONDS));
            closer.join();
            assertNull(closeFailure.get(), String.valueOf(closeFailure.get()));
            assertEquals(1, cleaned.get());
            assertTrue(child.isQuiescent());
            assertTrue(scope.physicallyQuiescent());
        }
    }

    @Test
    void preparedOwnerCannotBeAdmittedTwice() {
        try (var runtime = new AsyncRuntime()) {
            AsyncRuntime.TaskAdmission<Integer> admission =
                    runtime.prepareTaskAdmission();
            runtime.submitPreparedTask(admission, () -> 5);
            assertThrows(IllegalStateException.class,
                    () -> runtime.submitPreparedTask(admission, () -> 6));
            assertEquals(5, AsyncRuntime.await(admission.owner.getAwaited()));
            assertNull(AsyncRuntime.await(admission.owner.getDone()));
        }
    }
}
