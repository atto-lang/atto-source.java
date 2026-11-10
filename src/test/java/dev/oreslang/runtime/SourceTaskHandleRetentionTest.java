package dev.oreslang.runtime;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression: WeakHashMap<Future, TaskHandle> is NOT weak when the value
 * strongly references its Future key. A process-global registry would keep
 * every completed source task and its scheduler/owner graph reachable.
 */
final class SourceTaskHandleRetentionTest {
    @Test
    @Timeout(10)
    void sourceFutureRetainsItsOwnAuthorityButBorrowedFuturesDoNot() throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle =
                    TaskHandle.start(scheduler, resume -> OresScheduler.done(42));
            OresFuture<Integer> result = SourceTaskHandles.register(handle);

            assertSame(handle, SourceTaskHandles.lookup(result));
            assertEquals(42, result.get(5, TimeUnit.SECONDS));
            assertNotNull(SourceTaskHandles.outcomeSymbol(handle).get(5, TimeUnit.SECONDS));
            assertSame(handle, SourceTaskHandles.lookup(result),
                    "retaining an already-completed Future keeps its get_done authority");
            assertNull(SourceTaskHandles.lookup(new OresFuture<>()));
            assertNull(SourceTaskHandles.lookup(null));
            assertThrows(IllegalStateException.class,
                    () -> SourceTaskHandles.register(handle),
                    "a Future must never accept a second source-task capability");
        }
    }

    private static WeakReference<OresFuture<Integer>> abandonedCompletedTask(
            ReferenceQueue<OresFuture<Integer>> queue) throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1)) {
            TaskHandle<Integer> handle =
                    TaskHandle.start(scheduler, resume -> OresScheduler.done(7));
            OresFuture<Integer> result = SourceTaskHandles.register(handle);
            assertEquals(7, result.get(5, TimeUnit.SECONDS));
            assertNotNull(handle.getDone().get(5, TimeUnit.SECONDS));
            return new WeakReference<>(result, queue);
        }
    }

    @Test
    @Timeout(10)
    void completedAbandonedSourceFutureIsNotRootedByAGlobalRegistry() throws Exception {
        ReferenceQueue<OresFuture<Integer>> queue = new ReferenceQueue<>();
        WeakReference<OresFuture<Integer>> weak = abandonedCompletedTask(queue);

        for (int i = 0; i < 70 && queue.poll() == null; i++) {
            // Trigger ordinary young-gen pressure as well as explicit GC.
            byte[] pressure = new byte[256 * 1024];
            pressure[0] = (byte) i;
            System.gc();
            Thread.sleep(20);
        }
        assertNull(weak.get(),
                "SourceTaskHandles must not hold a process-static strong path back to task Futures");
    }
}
