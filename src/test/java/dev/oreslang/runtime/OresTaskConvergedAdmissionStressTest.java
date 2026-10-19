package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress the canonical provisional Task admission API rather than a second
 * independently maintained PreparedTask implementation.
 */
@Timeout(60)
final class OresTaskConvergedAdmissionStressTest {
    @Test
    void tenThousandUnboundCancellationReplaysRetirementOnlyAfterExit() {
        for (int trial = 0; trial < 10_000; trial++) {
            OresFuture<Void> physical = new OresFuture<>();
            OresTask<Integer> shell = OresTask.provisional(physical);
            OresFuture<Integer> result = new OresFuture<>();

            assertNull(shell.cancelDeferredNotification());
            assertFalse(shell.isQuiescent());
            shell.attachCompletion(result);
            assertTrue(result.isCancelled(),
                    "unbound cancellation must replay after Future binding");
            assertNull(shell.cancelDeferredNotification(),
                    "duplicate revocation cannot deliver twice");
            assertFalse(shell.isQuiescent(),
                    "logical cancellation is not physical retirement");
            physical.completeFromRuntime(null);
            assertNull(AsyncRuntime.await(shell.getDone()));
            assertTrue(shell.isQuiescent());
        }
    }

    @Test
    void concurrentAdmissionHasExactlyOneExecutorClaim() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            for (int trial = 0; trial < 128; trial++) {
                AsyncRuntime.TaskAdmission<Integer> admitted =
                        runtime.prepareTaskAdmission();
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger accepted = new AtomicInteger();
                AtomicInteger rejected = new AtomicInteger();
                AtomicInteger executed = new AtomicInteger();
                AtomicReference<Throwable> unexpected = new AtomicReference<>();

                Runnable submitter = () -> {
                    try {
                        if (!start.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("admission latch timed out");
                        }
                        runtime.submitPreparedTask(admitted, executed::incrementAndGet);
                        accepted.incrementAndGet();
                    } catch (IllegalStateException expected) {
                        rejected.incrementAndGet();
                    } catch (Throwable failure) {
                        unexpected.compareAndSet(null, failure);
                    }
                };
                Thread first = Thread.ofVirtual().start(submitter);
                Thread second = Thread.ofVirtual().start(submitter);
                start.countDown();
                first.join(5_000);
                second.join(5_000);
                assertFalse(first.isAlive(), "first contender stuck on " + trial);
                assertFalse(second.isAlive(), "second contender stuck on " + trial);
                assertNull(unexpected.get(), "unexpected admission failure");
                assertEquals(1, accepted.get(), "one accepted submission");
                assertEquals(1, rejected.get(), "one rejected duplicate");
                assertEquals(1, AsyncRuntime.await(admitted.owner.getAwaited()));
                assertNull(AsyncRuntime.await(admitted.owner.getDone()));
                assertEquals(1, executed.get(), "worker executed once");
            }
        }
    }
}
