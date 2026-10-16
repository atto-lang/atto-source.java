package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ActorPhysicalTurnCreditsTest {
    @Test void defaultsToOnePhysicalTurn() {
        var gate = new ActorPhysicalTurnCredits();
        assertEquals(1, gate.maximum());
        var first = gate.tryAcquire();
        assertNotNull(first);
        assertNull(gate.tryAcquire());
        assertEquals(1, gate.active());
        first.close();
        first.close();
        assertEquals(0, gate.active());
        assertNotNull(gate.tryAcquire());
    }

    @Test void explicitThreeAdmitsAtMostThreeAndReleasesOnce() {
        var gate = new ActorPhysicalTurnCredits(3);
        var a = gate.tryAcquire();
        var b = gate.tryAcquire();
        var c = gate.tryAcquire();
        assertNotNull(a); assertNotNull(b); assertNotNull(c);
        assertEquals(3, gate.active());
        assertNull(gate.tryAcquire());
        b.close();
        b.close();
        assertEquals(2, gate.active());
        var d = gate.tryAcquire();
        assertNotNull(d);
        assertEquals(3, gate.active());
        gate.close();
        assertNull(gate.tryAcquire());
        a.close(); c.close(); d.close();
        assertEquals(0, gate.active());
        assertTrue(gate.isClosed());
        assertNull(gate.tryAcquire());
    }

    @Test void invalidPhysicalParallelismRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ActorPhysicalTurnCredits(0));
        assertThrows(IllegalArgumentException.class, () -> new ActorPhysicalTurnCredits(4));
        assertThrows(IllegalArgumentException.class, () -> new ActorPhysicalTurnCredits(-1));
    }

    @Test void concurrentClaimsCannotExceedThree() throws Exception {
        var gate = new ActorPhysicalTurnCredits(3);
        var ready = new CountDownLatch(16);
        var start = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var observedPeak = new AtomicInteger();
        var granted = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(16)) {
            var jobs = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 16; i++) {
                jobs.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("start barrier timed out");
                    var permit = gate.tryAcquire();
                    if (permit == null) return;
                    try (permit) {
                        granted.incrementAndGet();
                        observedPeak.accumulateAndGet(gate.active(), Math::max);
                        if (!release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("release barrier timed out");
                    }
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            try {
                // Workers may race a single-attempt CAS; no minimum count is
                // required. The strict safety property is never exceeding three.
                while (ready.getCount() != 0) Thread.onSpinWait();
                assertTrue(gate.active() <= 3);
            } finally {
                release.countDown();
            }
            for (var job : jobs) job.get(10, TimeUnit.SECONDS);
        }
        assertTrue(granted.get() <= 3);
        assertTrue(observedPeak.get() <= 3);
        assertEquals(0, gate.active());
    }
}
