package dev.oreslang;

import dev.oreslang.runtime.ActorStateLoans;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Native Java runtime substrate tests. A successful guard test does NOT
 * establish Oreslang critical{} parsing, effect checking, or Graal guest ABI.
 */
@Timeout(15)
final class CriticalTryLoanRuntimeTest {
    @Test
    void immediateUncontendedTryAcquiresAndContendedTryDoesNotQueue() {
        try (var arbiter = new ActorStateLoans(2)) {
            Object owner = new Object(), other = new Object();
            var loan = arbiter.tryAcquire(owner).orElseThrow();
            assertTrue(loan.heldBy(owner));
            assertEquals(1, arbiter.activeCount());
            for (int n = 0; n < 1000; ++n) {
                assertTrue(arbiter.tryAcquire(other).isEmpty());
                assertEquals(0, arbiter.pendingCount());
            }
            assertThrows(IllegalStateException.class, () -> arbiter.tryAcquire(owner));
            loan.close();
            assertEquals(0, arbiter.activeCount());
            var replacement = arbiter.tryAcquire(other).orElseThrow();
            assertNotEquals(loan.epoch(), replacement.epoch());
            replacement.close();
        }
    }

    @Test
    void tryDoesNotOvertakeQueuedFifoSuccessor() throws Exception {
        try (var arbiter = new ActorStateLoans(2)) {
            Object owner = new Object(), waitingOwner = new Object();
            var first = arbiter.tryAcquire(owner).orElseThrow();
            var future = arbiter.acquireAsync(waitingOwner);
            assertEquals(1, arbiter.pendingCount());
            assertTrue(arbiter.tryAcquire(new Object()).isEmpty());
            assertEquals(1, arbiter.pendingCount());
            first.close();
            var successor = future.get(2, TimeUnit.SECONDS);
            assertTrue(successor.heldBy(waitingOwner));
            assertTrue(arbiter.tryAcquire(new Object()).isEmpty());
            successor.close();
            assertEquals(0, arbiter.activeCount());
            assertEquals(0, arbiter.pendingCount());
        }
    }

    @Test
    void cancelledWaiterCannotRetainIdentityOrStealReleasedGuard() throws Exception {
        try (var arbiter = new ActorStateLoans(2)) {
            Object owner = new Object(), cancelledOwner = new Object(), next = new Object();
            var held = arbiter.tryAcquire(owner).orElseThrow();
            var cancelled = arbiter.acquireAsync(cancelledOwner);
            var survivor = arbiter.acquireAsync(next);
            assertTrue(cancelled.cancel(false));
            assertTrue(arbiter.tryAcquire(cancelledOwner).isEmpty());
            held.close();
            var admitted = survivor.get(2, TimeUnit.SECONDS);
            assertTrue(admitted.heldBy(next));
            admitted.close();
            var retry = arbiter.tryAcquire(cancelledOwner).orElseThrow();
            retry.close();
        }
    }

    @Test
    void logicalIdentityMayMigrateCarrierThreadsWithoutReleasingGuard() throws Exception {
        try (var arbiter = new ActorStateLoans(1)) {
            Object owner = new Object();
            var loan = arbiter.tryAcquire(owner).orElseThrow();
            var failure = new AtomicReference<Throwable>();
            var observed = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                try {
                    assertTrue(loan.heldBy(owner));
                    assertTrue(arbiter.tryAcquire(new Object()).isEmpty());
                    loan.close();
                } catch (Throwable problem) {
                    failure.set(problem);
                } finally { observed.countDown(); }
            }, "logical-loan-migration-test");
            worker.start();
            assertTrue(observed.await(3, TimeUnit.SECONDS));
            worker.join(3000);
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            var resumed = arbiter.tryAcquire(owner).orElseThrow();
            assertTrue(resumed.heldBy(owner));
            resumed.close();
        }
    }

    @Test
    void closingArbiterFencesNewTryAndActiveLoanAccess() {
        var arbiter = new ActorStateLoans(1);
        Object owner = new Object();
        var loan = arbiter.tryAcquire(owner).orElseThrow();
        arbiter.close();
        assertFalse(loan.heldBy(owner));
        assertThrows(IllegalStateException.class, () -> arbiter.tryAcquire(new Object()));
        loan.close();
        assertEquals(0, arbiter.activeCount());
    }

    @Test
    void cancellationRacingWithGrantReleasesAtMostOneOwnerAndPreservesSuccessor() throws Exception {
        for (int iteration = 0; iteration < 64; ++iteration) {
            try (var arbiter = new ActorStateLoans(2)) {
                var held = arbiter.tryAcquire(new Object()).orElseThrow();
                var candidate = arbiter.acquireAsync(new Object());
                Object nextIdentity = new Object();
                var survivor = arbiter.acquireAsync(nextIdentity);
                var start = new CountDownLatch(1);
                var error = new AtomicReference<Throwable>();
                Thread cancel = new Thread(() -> {
                    try {
                        if (!start.await(2, TimeUnit.SECONDS))
                            throw new AssertionError("race start timed out");
                        candidate.cancel(false);
                    } catch (Throwable problem) { error.compareAndSet(null, problem); }
                }, "critical-cancel-race");
                Thread grant = new Thread(() -> {
                    try {
                        if (!start.await(2, TimeUnit.SECONDS))
                            throw new AssertionError("race start timed out");
                        held.close();
                    } catch (Throwable problem) { error.compareAndSet(null, problem); }
                }, "critical-grant-race");
                cancel.start();
                grant.start();
                start.countDown();
                cancel.join(3000);
                grant.join(3000);
                assertFalse(cancel.isAlive(), "cancel thread failed to terminate");
                assertFalse(grant.isAlive(), "grant thread failed to terminate");
                assertNull(error.get(), "race callback failure");
                if (!candidate.isCancelled()) {
                    var granted = candidate.get(2, TimeUnit.SECONDS);
                    assertEquals(1, arbiter.activeCount());
                    granted.close();
                }
                var successor = survivor.get(2, TimeUnit.SECONDS);
                assertTrue(successor.heldBy(nextIdentity));
                successor.close();
                assertEquals(0, arbiter.activeCount());
                assertEquals(0, arbiter.pendingCount());
            }
        }
    }

    @Test
    void zeroQueueCapacityStillSupportsImmediateNonParkingTry() {
        try (var arbiter = new ActorStateLoans(0)) {
            Object holder = new Object();
            var loan = arbiter.tryAcquire(holder).orElseThrow();
            assertTrue(arbiter.tryAcquire(new Object()).isEmpty());
            assertEquals(0, arbiter.pendingCount());
            loan.close();
            var next = arbiter.tryAcquire(new Object()).orElseThrow();
            next.close();
        }
    }
}
