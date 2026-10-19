package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Host shutdown is a physical carrier barrier, not a closed-bit observer. */
final class AsyncRuntimeShutdownTest {
    @Test
    void concurrentCloserWaitsForCarrierFinallyAndCancellationIsPublished()
            throws Exception {
        AsyncRuntime runtime = new AsyncRuntime();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondDone = new CountDownLatch(1);
        AtomicReference<Throwable> firstError = new AtomicReference<>();
        AtomicReference<Throwable> secondError = new AtomicReference<>();
        OresTask<Void> task = runtime.submitTask(() -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } finally {
                cleaning.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS));
            }
            return null;
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        Thread first = Thread.ofVirtual().start(() -> {
            try { runtime.close(); }
            catch (Throwable failure) { firstError.set(failure); }
        });
        try {
            assertTrue(cleaning.await(2, TimeUnit.SECONDS));
            Thread second = Thread.ofVirtual().start(() -> {
                try { runtime.close(); }
                catch (Throwable failure) { secondError.set(failure); }
                finally { secondDone.countDown(); }
            });
            try {
                assertTrue(task.isCancelled(), "shutdown revokes Task futures");
                assertFalse(task.isQuiescent(), "finally has not exited");
                assertFalse(secondDone.await(100, TimeUnit.MILLISECONDS),
                        "a second close() must not bypass the termination barrier");
            } finally {
                release.countDown();
                second.join(2_000);
            }
            assertEquals(0L, secondDone.getCount());
        } finally {
            release.countDown();
            first.join(2_000);
        }
        assertNull(firstError.get());
        assertNull(secondError.get());
        assertTrue(task.isQuiescent());
    }

    @Test
    void interruptedCloserStillWaitsAndRestoresInterruptFlag() throws Exception {
        AsyncRuntime runtime = new AsyncRuntime();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean restored = new AtomicBoolean();
        OresTask<Void> task = runtime.submitTask(() -> {
            started.countDown();
            try {
                Thread.sleep(30_000);
            } finally {
                cleaning.countDown();
                assertTrue(release.await(2, TimeUnit.SECONDS));
            }
            return null;
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        Thread closer = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            try { runtime.close(); }
            catch (Throwable error) { failure.set(error); }
            finally {
                restored.set(Thread.currentThread().isInterrupted());
                closeReturned.countDown();
            }
        });
        try {
            assertTrue(cleaning.await(2, TimeUnit.SECONDS));
            assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS));
            assertFalse(task.isQuiescent());
        } finally {
            release.countDown();
            closer.join(2_000);
        }
        assertEquals(0L, closeReturned.getCount());
        assertNull(failure.get());
        assertTrue(restored.get());
        assertTrue(task.isQuiescent());
    }

    @Test
    void failedTimeoutDoesNotMakeSubsequentCloseFalselySuccessful()
            throws Exception {
        AsyncRuntime runtime = new AsyncRuntime();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cleaning = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch retryReturned = new CountDownLatch(1);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        AtomicReference<Throwable> retryFailure = new AtomicReference<>();
        OresTask<Void> task = runtime.submitTask(() -> {
            started.countDown();
            try { Thread.sleep(30_000); }
            finally {
                cleaning.countDown();
                // Ignore interrupt until the test explicitly permits exit.
                boolean interrupted = false;
                while (release.getCount() != 0) {
                    try { release.await(); }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            return null;
        });
        assertTrue(started.await(2, TimeUnit.SECONDS));
        Thread first = Thread.ofVirtual().start(() -> {
            try { runtime.close(); }
            catch (Throwable problem) { firstFailure.set(problem); }
        });
        try {
            assertTrue(cleaning.await(2, TimeUnit.SECONDS));
            first.join(2_000);
            assertFalse(first.isAlive());
            assertInstanceOf(IllegalStateException.class, firstFailure.get(),
                    "timed-out close must fail closed");
            assertFalse(task.isQuiescent());
            Thread retry = Thread.ofVirtual().start(() -> {
                try { runtime.close(); }
                catch (Throwable problem) { retryFailure.set(problem); }
                finally { retryReturned.countDown(); }
            });
            try {
                assertFalse(retryReturned.await(100, TimeUnit.MILLISECONDS),
                        "closed flag is not a quiescence proof on retry");
            } finally {
                release.countDown();
                retry.join(2_000);
            }
            assertEquals(0L, retryReturned.getCount());
            assertNull(retryFailure.get());
            assertTrue(task.isQuiescent());
        } finally {
            release.countDown();
            first.join(2_000);
        }
    }

    @Test
    void asyncCarrierCannotBlockOnItsOwnRuntimeClose() {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            OresTask<Boolean> task = runtime.submitTask(() -> {
                assertThrows(IllegalStateException.class, runtime::close);
                return !runtime.isClosed();
            });
            assertTrue(AsyncRuntime.await(task.asFuture()));
        }
    }
}
