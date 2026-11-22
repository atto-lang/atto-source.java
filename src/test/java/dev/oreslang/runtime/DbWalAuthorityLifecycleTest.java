package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** HOST-ONLY linearization checks, not an Oreslang guest durability test. */
final class DbWalAuthorityLifecycleTest {
    @Test void revocationWithRunningWriteIsRetryableOnlyAfterPhysicalExit() {
        var gate = new DbWalAuthorityLifecycle();
        assertTrue(gate.beginWrite());
        assertFalse(gate.requestDrain());
        assertEquals(DbWalAuthorityLifecycle.Phase.DRAINING, gate.phase());
        assertFalse(gate.beginWrite(), "DRAINING must reject all new admissions");
        assertFalse(gate.beginClose(), "cannot close while a worker is live");
        assertFalse(gate.requestDrain(), "repeated requests must not clear the loan");
        gate.physicalWriteRetired();
        assertTrue(gate.requestDrain(), "owner must be able to retry after physical exit");
        assertTrue(gate.beginClose());
        assertFalse(gate.beginClose(), "only one physical close can be admitted");
        assertFalse(gate.requestDrain(), "a queued close must never be resubmitted");
        assertEquals(DbWalAuthorityLifecycle.Phase.CLOSING, gate.phase());
        gate.physicalCloseRetired();
        assertEquals(DbWalAuthorityLifecycle.Phase.CLOSED, gate.phase());
        assertFalse(gate.beginWrite());
        assertFalse(gate.requestDrain());
        assertFalse(gate.beginClose());
        assertThrows(IllegalStateException.class, gate::physicalCloseRetired);
        assertThrows(IllegalStateException.class, gate::physicalWriteRetired);
    }

    @Test void discardedNeverStartedWorkDoesNotFreePhysicalAdmission() {
        var gate = new DbWalAuthorityLifecycle();
        assertTrue(gate.beginWrite());
        // An async task that never executes has no right to call
        // physicalWriteRetired just because its Future failed.
        assertFalse(gate.requestDrain());
        assertFalse(gate.beginClose());
        assertTrue(gate.physicallyActive());
        assertEquals(DbWalAuthorityLifecycle.Phase.DRAINING, gate.phase());
    }

    @Test void simultaneousDrainAndWorkerRetirementCannotDoubleClose() throws Exception {
        for (int round = 0; round < 250; ++round) {
            var gate = new DbWalAuthorityLifecycle();
            assertTrue(gate.beginWrite());
            var start = new CountDownLatch(1);
            var finished = new CountDownLatch(2);
            var failures = new AtomicReference<Throwable>();
            Thread drainer = Thread.ofVirtual().start(() -> {
                try {
                    if (!start.await(2, TimeUnit.SECONDS)) throw new AssertionError("start");
                    gate.requestDrain();
                } catch (Throwable t) { failures.compareAndSet(null, t); }
                finally { finished.countDown(); }
            });
            Thread worker = Thread.ofVirtual().start(() -> {
                try {
                    if (!start.await(2, TimeUnit.SECONDS)) throw new AssertionError("start");
                    gate.physicalWriteRetired();
                } catch (Throwable t) { failures.compareAndSet(null, t); }
                finally { finished.countDown(); }
            });
            start.countDown();
            assertTrue(finished.await(3, TimeUnit.SECONDS), "no stranded worker");
            drainer.join();
            worker.join();
            assertNull(failures.get(), () -> String.valueOf(failures.get()));
            assertFalse(gate.beginWrite());
            assertTrue(gate.requestDrain());
            assertTrue(gate.beginClose());
            assertFalse(gate.beginClose());
            gate.physicalCloseRetired();
            assertEquals(DbWalAuthorityLifecycle.Phase.CLOSED, gate.phase());
        }
    }
}
