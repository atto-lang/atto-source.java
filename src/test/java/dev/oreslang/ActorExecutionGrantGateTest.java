package dev.oreslang;

import dev.oreslang.runtime.ActorExecutionGrantGate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorExecutionGrantGateTest {
    @Test
    void enforcesCapacityUnderTrueConcurrentContention() throws Exception {
        for (int capacity : new int[] {1, 2, 3}) {
            var gate = new ActorExecutionGrantGate(101, capacity);
            var barrier = new CyclicBarrier(12);
            var attempts = new CountDownLatch(12);
            var release = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(12);
            List<Future<Boolean>> futures = new ArrayList<>();
            try {
                for (int i = 0; i < 12; ++i) {
                    futures.add(pool.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try (var grant = gate.tryAcquire()) {
                            attempts.countDown();
                            if (grant == null) return false;
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("carrier release timed out");
                            }
                            return true;
                        }
                    }));
                }
                assertTrue(attempts.await(5, TimeUnit.SECONDS));
                assertEquals(capacity, gate.activeSlots(), "max physical grants");
            } finally {
                release.countDown();
                pool.shutdown();
                assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
            }
            int admitted = 0;
            for (Future<Boolean> result : futures) if (result.get(2, TimeUnit.SECONDS)) ++admitted;
            assertEquals(capacity, admitted);
            assertEquals(0, gate.activeSlots());
        }
    }

    @Test
    void oldGrantCannotReleaseReacquiredGrantOnSameCarrier() {
        var gate = new ActorExecutionGrantGate(42, 3);
        var old = gate.tryAcquire();
        assertNotNull(old);
        long prior = old.sequence();
        old.close();

        var successor = gate.tryAcquire();
        assertNotNull(successor);
        assertTrue(successor.sequence() > prior);
        assertThrows(SecurityException.class, old::close);
        assertEquals(1, gate.activeSlots());
        successor.close();
        assertEquals(0, gate.activeSlots());
    }

    @Test
    void anotherCarrierCannotReleaseLiveGrant() throws Exception {
        var gate = new ActorExecutionGrantGate(111, 3);
        var grant = gate.tryAcquire();
        AtomicReference<Throwable> rejected = new AtomicReference<>();
        var attacker = new Thread(() -> {
            try { grant.close(); } catch (Throwable failure) { rejected.set(failure); }
        });
        attacker.start();
        attacker.join(3000);
        assertFalse(attacker.isAlive());
        assertInstanceOf(SecurityException.class, rejected.get());
        assertEquals(1, gate.activeSlots());
        grant.close();
    }

    @Test
    void foreignActorGrantCannotReleaseAnotherActorsSlot() throws Exception {
        var a = new ActorExecutionGrantGate(8, 1);
        var b = new ActorExecutionGrantGate(8, 1);
        var aGrant = a.tryAcquire();
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AtomicReference<ActorExecutionGrantGate.Grant> otherGrant = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var owner = new Thread(() -> {
            try (var granted = b.tryAcquire()) {
                otherGrant.set(granted);
                ready.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("timeout");
            } catch (Throwable e) {
                failure.set(e);
                ready.countDown();
            }
        });
        owner.start();
        try {
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            assertNull(failure.get());
            var bGrant = otherGrant.get();
            assertNotNull(bGrant);
            assertThrows(SecurityException.class, () -> a.release(bGrant));
            assertThrows(SecurityException.class, () -> b.release(aGrant));
            assertEquals(1, a.activeSlots());
            assertEquals(1, b.activeSlots());
        } finally {
            aGrant.close();
            release.countDown();
            owner.join(3000);
        }
        assertFalse(owner.isAlive());
        assertNull(failure.get());
        assertEquals(0, b.activeSlots());
    }

    @Test
    void sameCarrierCannotAcquireIndependentNestedSlot() {
        var gate = new ActorExecutionGrantGate(33, 3);
        var grant = gate.tryAcquire();
        assertThrows(IllegalStateException.class, gate::tryAcquire);
        assertEquals(1, gate.activeSlots());
        grant.close();
    }

    @Test
    void sealedActorCannotAdmitAndRetirementWaitsForCarrierUnwind() {
        var gate = new ActorExecutionGrantGate(99, 3);
        assertFalse(gate.tryRetirePhysicalGate());
        var grant = gate.tryAcquire();
        gate.seal();
        gate.seal();
        assertTrue(gate.isSealed());
        assertNull(gate.tryAcquire());
        assertFalse(gate.tryRetirePhysicalGate());
        grant.close();
        assertTrue(gate.tryRetirePhysicalGate());
        assertTrue(gate.isPhysicalGateRetired());
        assertFalse(gate.tryRetirePhysicalGate());
        assertNull(gate.tryAcquire());
        assertThrows(SecurityException.class, grant::close);
    }

    @Test
    void nestedCrossActorExecutionOnSameCarrierIsRejected() {
        var a = new ActorExecutionGrantGate(1, 3);
        var b = new ActorExecutionGrantGate(2, 3);
        var aGrant = a.tryAcquire();
        try {
            assertThrows(IllegalStateException.class, b::tryAcquire);
            assertEquals(1, a.activeSlots());
            assertEquals(0, b.activeSlots());
        } finally {
            aGrant.close();
        }
        var bGrant = b.tryAcquire();
        assertNotNull(bGrant);
        bGrant.close();
    }

    @Test
    void boundedWaitReturnsOnDrainAndCannotCompleteOnTimeoutOrUnsealedGate() throws Exception {
        var gate = new ActorExecutionGrantGate(101, 1);
        assertThrows(IllegalStateException.class,
                () -> gate.awaitPhysicalDrain(0, TimeUnit.NANOSECONDS));
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var owner = new Thread(() -> {
            try (var grant = gate.tryAcquire()) {
                ready.countDown();
                if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("timeout");
            } catch (Throwable t) {
                failure.set(t);
                ready.countDown();
            }
        });
        owner.start();
        try {
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            assertNull(failure.get());
            gate.seal();
            assertFalse(gate.awaitPhysicalDrain(0, TimeUnit.NANOSECONDS));
            assertFalse(gate.awaitPhysicalDrain(1, TimeUnit.MILLISECONDS));
            assertFalse(gate.tryRetirePhysicalGate());
            assertNull(gate.tryAcquire());
        } finally {
            release.countDown();
            owner.join(3000);
        }
        assertFalse(owner.isAlive());
        assertNull(failure.get());
        assertTrue(gate.awaitPhysicalDrain(1, TimeUnit.SECONDS));
        assertTrue(gate.tryRetirePhysicalGate());
        assertThrows(IllegalArgumentException.class,
                () -> gate.awaitPhysicalDrain(-1, TimeUnit.SECONDS));
        assertThrows(NullPointerException.class,
                () -> gate.awaitPhysicalDrain(1, null));
    }

    @Test
    void holderCannotWaitForItsOwnPhysicalGrantToDrain() {
        var gate = new ActorExecutionGrantGate(11, 1);
        var grant = gate.tryAcquire();
        gate.seal();
        assertThrows(IllegalStateException.class,
                () -> gate.awaitPhysicalDrain(1, TimeUnit.MILLISECONDS));
        grant.close();
        assertDoesNotThrow(() -> assertTrue(gate.awaitPhysicalDrain(0, TimeUnit.NANOSECONDS)));
    }

    @Test
    void virtualThreadCannotMasqueradeAsAPlatformCarrier() throws Exception {
        var gate = new ActorExecutionGrantGate(101, 1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        var virtual = Thread.ofVirtual().start(() -> {
            try { gate.tryAcquire(); }
            catch (Throwable e) { error.set(e); }
        });
        virtual.join(3000);
        assertFalse(virtual.isAlive());
        assertInstanceOf(IllegalStateException.class, error.get());
        assertEquals(0, gate.activeSlots());
    }

    @Test
    void interruptedDrainingWaitMustNotRetireOrReopenAdmission() throws Exception {
        var gate = new ActorExecutionGrantGate(7, 1);
        var grant = gate.tryAcquire();
        gate.seal();
        var entered = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var waiter = new Thread(() -> {
            try {
                entered.countDown();
                gate.awaitPhysicalDrain(30, TimeUnit.SECONDS);
                failure.set(new AssertionError("wait unexpectedly completed"));
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            } catch (Throwable unexpected) { failure.set(unexpected); }
        });
        waiter.start();
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        waiter.interrupt();
        waiter.join(3000);
        assertFalse(waiter.isAlive());
        assertNull(failure.get());
        assertEquals(1, gate.activeSlots());
        assertFalse(gate.tryRetirePhysicalGate());
        grant.close();
        assertTrue(gate.awaitPhysicalDrain(1, TimeUnit.SECONDS));
    }

    @Test
    void guestFailureCannotLeakASchedulerOwnedGrantWhenFinallyUnwinds() {
        var gate = new ActorExecutionGrantGate(4, 1);
        assertThrows(AssertionError.class, () -> {
            try (var grant = gate.tryAcquire()) {
                assertNotNull(grant);
                throw new AssertionError("simulated guest panic");
            }
        });
        assertEquals(0, gate.activeSlots());
        assertDoesNotThrow(() -> {
            try (var grant = gate.tryAcquire()) {
                assertNotNull(grant);
            }
        });
        gate.seal();
        assertTrue(gate.tryRetirePhysicalGate());
    }

    @Test
    void sealedActorCannotReopenWhileOtherActorOwnsCarrier() {
        var a = new ActorExecutionGrantGate(1, 1);
        var b = new ActorExecutionGrantGate(2, 1);
        var ticket = a.tryAcquire();
        a.seal();
        assertNull(a.tryAcquire());
        assertFalse(a.tryRetirePhysicalGate());
        assertThrows(IllegalStateException.class, b::tryAcquire);
        ticket.close();
        assertTrue(a.tryRetirePhysicalGate());
        try (var bTicket = b.tryAcquire()) {
            assertNotNull(bTicket);
            assertEquals(1, b.activeSlots());
        }
    }

    @Test
    void rejectsInvalidCapacityAndIncarnation() {
        assertThrows(IllegalArgumentException.class, () -> new ActorExecutionGrantGate(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ActorExecutionGrantGate(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ActorExecutionGrantGate(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ActorExecutionGrantGate(1, -5));
        assertThrows(IllegalArgumentException.class, () -> new ActorExecutionGrantGate(1, 4));
    }
}
