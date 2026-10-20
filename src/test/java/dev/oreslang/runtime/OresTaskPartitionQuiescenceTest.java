package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host/runtime proof of parallel disjoint loops + physical completion.
 * This does NOT enable Oreslang guest nb/borrow syntax or replace a
 * compiler-minted region permit. Region indices are test-owned constants.
 */
final class OresTaskPartitionQuiescenceTest {
    @Test
    void twoSiblingCompilerFramesDoNotReleaseBackingUntilPhysicalExit() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(2);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            int[] positions = new int[8192];
            CountDownLatch bothInside = new CountDownLatch(2);
            CountDownLatch finish = new CountDownLatch(1);
            AtomicInteger physicalExits = new AtomicInteger();
            AtomicInteger pins = new AtomicInteger(2);

            OresTask<Integer> left = scope.startCompilerFrame(scheduler, resume -> {
                for (int i = 0; i < 4096; i++) positions[i] += 1;
                bothInside.countDown();
                assertTrue(finish.await(3, TimeUnit.SECONDS));
                return new OresScheduler.Done<>(4096);
            }, () -> {
                pins.decrementAndGet();
                physicalExits.incrementAndGet();
            });
            OresTask<Integer> right = scope.startCompilerFrame(scheduler, resume -> {
                for (int i = 4096; i < positions.length; i++) positions[i] += 2;
                bothInside.countDown();
                assertTrue(finish.await(3, TimeUnit.SECONDS));
                return new OresScheduler.Done<>(4096);
            }, () -> {
                pins.decrementAndGet();
                physicalExits.incrementAndGet();
            });
            try {
                assertTrue(bothInside.await(3, TimeUnit.SECONDS),
                        "both independent regions should make progress concurrently");
                assertTrue(left.cancel(), "cancel requests do not release the active region");
                assertTrue(left.isCancelled());
                assertFalse(left.isQuiescent());
                assertEquals(2, pins.get());
                assertEquals(0, physicalExits.get());
            } finally {
                finish.countDown();
            }
            assertNull(AsyncRuntime.await(left.getDone()));
            assertNull(AsyncRuntime.await(right.getDone()));
            assertEquals(0, pins.get());
            assertEquals(2, physicalExits.get());
            assertEquals(4096, AsyncRuntime.await(right.getAwaited()));
            for (int i = 0; i < positions.length; i++)
                assertEquals(i < 4096 ? 1 : 2, positions[i]);
        }
    }

    @Test
    void cancelledTaskCannotReleaseBeforeFinallyCleanupQuiesces() throws Exception {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            CountDownLatch running = new CountDownLatch(1);
            CountDownLatch finish = new CountDownLatch(1);
            AtomicInteger liveRegionPins = new AtomicInteger(1);
            OresTask<Integer> worker = scope.startCompilerFrame(scheduler, resume -> {
                try {
                    running.countDown();
                    assertTrue(finish.await(3, TimeUnit.SECONDS));
                    return new OresScheduler.Done<>(1);
                } finally {
                    // The cancellation request must not reclaim the region
                    // while this finalizer is still executing.
                    assertEquals(1, liveRegionPins.get());
                }
            }, liveRegionPins::decrementAndGet);
            assertTrue(running.await(2, TimeUnit.SECONDS));
            try {
                assertTrue(worker.cancel());
                assertEquals(1, liveRegionPins.get());
                assertFalse(worker.isQuiescent());
            } finally {
                finish.countDown();
            }
            assertNull(AsyncRuntime.await(worker.getDone()));
            assertEquals(0, liveRegionPins.get());
        }
    }
}
