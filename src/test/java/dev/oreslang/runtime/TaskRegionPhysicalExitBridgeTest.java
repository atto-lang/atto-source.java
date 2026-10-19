package dev.oreslang.runtime;

import dev.oreslang.types.TaskAccessVerifier;
import java.util.List;
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
        var ledger = new TaskAccessVerifier();
        var column = TaskAccessVerifier.Place.whole(
                new TaskAccessVerifier.Root("PROCESS", 203, 1, true))
                .field("position", true);
        var leftClaim = new TaskAccessVerifier.Claim(
                column.range(0, 128, true), TaskAccessVerifier.Mode.WRITE);
        var rightClaim = new TaskAccessVerifier.Claim(
                column.range(128, 256, true), TaskAccessVerifier.Mode.WRITE);
        var leftTicket = ledger.admit(List.of(leftClaim));
        var rightTicket = ledger.admit(List.of(rightClaim));
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
                ledger.quiescent(leftTicket);
                released.incrementAndGet();
            });
            var right = scope.startCompilerFrame(scheduler, resume -> {
                for (int i = 128; i < 256; i++) data[i] = 22;
                bothRunning.countDown();
                assertTrue(mayExit.await(3, TimeUnit.SECONDS));
                return new OresScheduler.Done<>(128);
            }, () -> {
                ledger.quiescent(rightTicket);
                released.incrementAndGet();
            });
            try {
                assertTrue(bothRunning.await(2, TimeUnit.SECONDS));
                assertEquals(2, ledger.activeChildren());
                assertEquals(0, released.get());
                assertTrue(left.cancel());
                assertTrue(left.isCancelled());
                assertFalse(left.isQuiescent());
                assertEquals(0, released.get(),
                        "cancelled result is not a physical-exit proof");
                assertFalse(ledger.parentMayAccess(new TaskAccessVerifier.Claim(
                        column, TaskAccessVerifier.Mode.WRITE)));
                assertThrows(IllegalArgumentException.class, () -> ledger.admit(
                        List.of(new TaskAccessVerifier.Claim(
                                column.range(127, 129, true),
                                TaskAccessVerifier.Mode.WRITE))));
                assertThrows(IllegalStateException.class, ledger::finishScope);
            } finally {
                mayExit.countDown();
            }
            // getDone cannot complete before trusted onPhysicalExit succeeds.
            assertNull(AsyncRuntime.await(left.getDone()));
            assertNull(AsyncRuntime.await(right.getDone()));
            assertEquals(2, released.get());
            assertEquals(0, ledger.activeChildren());
            assertTrue(ledger.parentMayAccess(new TaskAccessVerifier.Claim(
                    column, TaskAccessVerifier.Mode.WRITE)));
            ledger.finishScope();
            for (int i = 0; i < data.length; i++)
                assertEquals(i < 128 ? 11 : 22, data[i]);
        }
    }
}
