package dev.oreslang.runtime;

import dev.oreslang.types.TaskRegionTestLedger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact host runtime/compiler-ledger seam: a compiler-owned region ticket
 * is retired by the trusted physical-exit callback, never by cancellation
 * or result settlement. Guest source must not be allowed to mint tickets.
 */
final class TaskRegionPhysicalExitBridgeTest {
    @Test
    void twoDisjointChildrenReleaseLoansOnlyFromPhysicalExitCallbacks() throws Exception {
        var ledger = new TaskRegionTestLedger();
        int[] data = new int[256];
        CountDownLatch bothRunning = new CountDownLatch(2);
        CountDownLatch mayExit = new CountDownLatch(1);
        AtomicInteger released = new AtomicInteger();

        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(2);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            var left = scope.startCompilerFrame(scheduler, resume -> {
                for (int i = 0; i < 128; i++) data[i] = 11;
                bothRunning.countDown();
                assertTrue(mayExit.await(3, TimeUnit.SECONDS));
                return new OresScheduler.Done<>(128);
            }, () -> {
                ledger.physicalExitLeft();
                released.incrementAndGet();
            });
            var right = scope.startCompilerFrame(scheduler, resume -> {
                for (int i = 128; i < 256; i++) data[i] = 22;
                bothRunning.countDown();
                assertTrue(mayExit.await(3, TimeUnit.SECONDS));
                return new OresScheduler.Done<>(128);
            }, () -> {
                ledger.physicalExitRight();
                released.incrementAndGet();
            });
            try {
                assertTrue(bothRunning.await(2, TimeUnit.SECONDS));
                assertEquals(2, ledger.activeChildren());
                assertEquals(0, released.get());
                assertTrue(left.cancel());
                ledger.cancelLeft();
                assertTrue(left.isCancelled());
                assertFalse(left.isQuiescent());
                assertEquals(0, released.get(),
                        "cancelled result is not a physical-exit proof");
                assertFalse(ledger.parentCanWrite());
                ledger.verifyOverlapRejected();
                ledger.verifyPrematureFinishRejected();
            } finally {
                mayExit.countDown();
            }
            // getDone cannot complete before trusted onPhysicalExit succeeds.
            assertNull(AsyncRuntime.await(left.getDone()));
            assertNull(AsyncRuntime.await(right.getDone()));
            assertEquals(2, released.get());
            assertEquals(0, ledger.activeChildren());
            assertTrue(ledger.parentCanWrite());
            ledger.finishScope();
            for (int i = 0; i < data.length; i++)
                assertEquals(i < 128 ? 11 : 22, data[i]);
        }
    }
    @Test
    void failedChildStillReleasesRegionOnlyAfterPhysicalExit() throws Exception {
        var ledger = new TaskRegionTestLedger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch allowExit = new CountDownLatch(1);
        AtomicInteger retired = new AtomicInteger();

        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(2);
             OresTaskScope scope = new OresTaskScope(runtime)) {
            var failed = scope.startCompilerFrame(scheduler, resume -> {
                entered.countDown();
                assertTrue(allowExit.await(5, TimeUnit.SECONDS));
                throw new IllegalStateException("intentional Task failure");
            }, () -> {
                ledger.physicalExitLeft();
                retired.incrementAndGet();
            });
            var sibling = scope.startCompilerFrame(scheduler, resume ->
                    new OresScheduler.Done<>(1), () -> {
                ledger.physicalExitRight();
                retired.incrementAndGet();
            });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(ledger.parentCanWrite());
                ledger.verifyPrematureFinishRejected();
            } finally {
                allowExit.countDown();
            }
            assertThrows(IllegalStateException.class,
                    () -> AsyncRuntime.await(failed.getAwaited()));
            assertNull(AsyncRuntime.await(failed.getDone()));
            assertNull(AsyncRuntime.await(sibling.getDone()));
            assertEquals(2, retired.get());
            assertEquals(0, ledger.activeChildren());
            assertTrue(ledger.parentCanWrite());
            ledger.finishScope();
        }
    }

}
