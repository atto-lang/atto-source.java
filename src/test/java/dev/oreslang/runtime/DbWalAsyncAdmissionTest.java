package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Host physical I/O admission tests. Never-started hooks are not logical
 * Future-cancel callbacks and cannot be used after a kernel write has begun.
 */
final class DbWalAsyncAdmissionTest {
    @Test void capacityRejectionReleasesOnlyNeverStartedReservation() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var io = new AsyncNativeIo(1, 1, 1)) {
            var holding = io.submit("hold-worker", () -> {
                entered.countDown();
                release.await();
                return 1;
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var gate = new DbWalAuthorityLifecycle();
            assertTrue(gate.beginWrite());
            var hooks = new AtomicInteger();
            var rejected = io.submitWithNonStartHook("rejected", () -> {
                fail("capacity-refused work must not run");
                return 0;
            }, () -> { hooks.incrementAndGet(); gate.neverStartedWrite(); });
            assertTrue(rejected.isDone());
            assertEquals(1, hooks.get());
            assertFalse(gate.physicallyActive());
            assertTrue(gate.beginWrite(), "safe retry after proven non-start");
            assertFalse(gate.requestDrain());
            assertFalse(gate.beginClose());
            // The retry was only a reservation: finish by a never-started
            // receipt, not by pretending it was a kernel completion.
            gate.neverStartedWrite();
            assertTrue(gate.requestDrain());
            assertTrue(gate.beginClose());
            gate.physicalCloseRetired();
            release.countDown();
            assertEquals(1, holding.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test void queuedShutdownInvokesHookOnceAndCannotReleaseRunningWorker() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var io = new AsyncNativeIo(2, 1, 1);
        try {
            var runningGate = new DbWalAuthorityLifecycle();
            var queuedGate = new DbWalAuthorityLifecycle();
            assertTrue(runningGate.beginWrite());
            assertTrue(queuedGate.beginWrite());
            var started = io.submitWithNonStartHook("running", () -> {
                entered.countDown();
                release.await();
                try {
                    return 1;
                } finally {
                    runningGate.physicalWriteRetired();
                }
            }, () -> fail("already-started operation must not report never-started"));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            var hooks = new AtomicInteger();
            var queued = io.submitWithNonStartHook("queued", () -> {
                fail("queued job must not execute after shutdown");
                return 2;
            }, () -> { hooks.incrementAndGet(); queuedGate.neverStartedWrite(); });
            assertFalse(queued.isDone());
            io.requestShutdown();
            assertEquals(1, hooks.get());
            assertFalse(queuedGate.physicallyActive());
            assertTrue(runningGate.physicallyActive(),
                    "shutdown cannot forge physical retirement of started work");
            assertFalse(runningGate.requestDrain());
            release.countDown();
            // Shutdown rejects the result of a running worker even if its
            // callable finished; physical loan/retirement is separate.
            assertThrows(Exception.class, () -> started.get(2, TimeUnit.SECONDS));
            assertFalse(runningGate.physicallyActive());
            assertTrue(runningGate.requestDrain());
        } finally {
            release.countDown();
            io.close();
        }
    }

    @Test void postShutdownRegisteredHandleBelongsToQuarantineUntilSweep() {
        var io = new AsyncNativeIo(2);
        var closed = new AtomicInteger();
        AutoCloseable resource = closed::incrementAndGet;
        io.requestShutdown();
        try {
            assertThrows(IllegalStateException.class, () -> io.register(resource));
            assertTrue(io.retainsRegisteredResource(resource));
            assertEquals(0, closed.get(),
                    "rejected registration must not free context-owned resource");
        } finally {
            io.close();
        }
        assertEquals(1, closed.get(), "context post-join sweep closes once");
    }
}
