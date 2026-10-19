package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Exact physical-fence contracts for pre-executor AsyncRuntime Task ownership. */
final class AsyncRuntimePreparedTaskTest {
    @Test
    void discardedPreparationSettlesExactPhysicalFenceAndCannotBeReused()
            throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            AsyncRuntime.PreparedTask<Integer> prepared = runtime.prepareTask();
            OresTask<Integer> owner = prepared.owner();

            assertFalse(owner.isQuiescent());
            runtime.discardPreparedTask(prepared);
            assertNull(owner.getDone().get(2, TimeUnit.SECONDS));
            assertTrue(owner.isQuiescent());

            assertThrows(IllegalStateException.class,
                    () -> runtime.submitPreparedTask(() -> 1, prepared));
        }
    }

    @Test
    void closedRuntimeSubmissionFailureStillSettlesPreparedPhysicalFence()
            throws Exception {
        AsyncRuntime runtime = new AsyncRuntime();
        AsyncRuntime.PreparedTask<Integer> prepared = runtime.prepareTask();
        OresTask<Integer> owner = prepared.owner();
        runtime.close();

        assertThrows(RejectedExecutionException.class,
                () -> runtime.submitPreparedTask(() -> 1, prepared));
        assertNull(owner.getDone().get(2, TimeUnit.SECONDS));
        assertTrue(owner.isQuiescent());
    }

    @Test
    void cancellationRecordedBeforeCompletionBindingIsReplayedOnPreparedSubmit()
            throws Exception {
        AtomicInteger entered = new AtomicInteger();
        try (AsyncRuntime runtime = new AsyncRuntime()) {
            AsyncRuntime.PreparedTask<Integer> prepared = runtime.prepareTask();
            OresTask<Integer> owner = prepared.owner();

            assertNull(owner.cancelDeferredNotification());
            OresTask<Integer> submitted = runtime.submitPreparedTask(() -> {
                entered.incrementAndGet();
                return 7;
            }, prepared);

            assertSame(owner, submitted);
            assertTrue(submitted.isCancelled());
            assertNull(submitted.getDone().get(2, TimeUnit.SECONDS));
            assertTrue(submitted.isQuiescent());
            assertTrue(entered.get() == 0 || entered.get() == 1,
                    "a cancellation racing executor entry may win before or after guest entry, but must quiesce");
        }
    }
}
