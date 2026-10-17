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
    void foreignActorGrantCannotReleaseAnotherActorsSlot() {
        var a = new ActorExecutionGrantGate(8, 1);
        var b = new ActorExecutionGrantGate(8, 1);
        var aGrant = a.tryAcquire();
        var bGrant = b.tryAcquire();
        assertThrows(SecurityException.class, () -> a.release(bGrant));
        assertThrows(SecurityException.class, () -> b.release(aGrant));
        assertEquals(1, a.activeSlots());
        assertEquals(1, b.activeSlots());
        aGrant.close();
        bGrant.close();
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
        assertFalse(gate.tryRetire());
        var grant = gate.tryAcquire();
        gate.seal();
        gate.seal();
        assertTrue(gate.isSealed());
        assertNull(gate.tryAcquire());
        assertFalse(gate.tryRetire());
        grant.close();
        assertTrue(gate.tryRetire());
        assertTrue(gate.isRetired());
        assertFalse(gate.tryRetire());
        assertNull(gate.tryAcquire());
        assertThrows(SecurityException.class, grant::close);
    }

    @Test
    void rejectsInvalidCapacityAndIncarnation() {
        assertThrows(IllegalArgumentException.class, () -> new ActorExecutionGrantGate(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ActorExecutionGrantGate(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ActorExecutionGrantGate(1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ActorExecutionGrantGate(1, -5));
    }
}
