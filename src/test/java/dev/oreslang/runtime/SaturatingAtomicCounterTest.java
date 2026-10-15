package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Bounded host CONTROL loss counters cannot become negative under overflow. */
@Timeout(15)
final class SaturatingAtomicCounterTest {
    @Test void incrementSaturatesWithoutWrapping() {
        AtomicLong counter = new AtomicLong(Long.MAX_VALUE - 2);
        assertEquals(Long.MAX_VALUE - 1, SaturatingAtomicCounter.increment(counter));
        assertEquals(Long.MAX_VALUE, SaturatingAtomicCounter.increment(counter));
        for (int i = 0; i < 100; ++i) {
            assertEquals(Long.MAX_VALUE, SaturatingAtomicCounter.increment(counter));
        }
        assertEquals(Long.MAX_VALUE, counter.get());
    }

    @Test void bulkRetirementLossSaturatesAndRejectsNegativeDeltas() {
        AtomicLong counter = new AtomicLong(Long.MAX_VALUE - 3);
        assertEquals(Long.MAX_VALUE - 2, SaturatingAtomicCounter.add(counter, 1));
        assertEquals(Long.MAX_VALUE, SaturatingAtomicCounter.add(counter, 9));
        assertEquals(Long.MAX_VALUE, SaturatingAtomicCounter.add(counter, 1));
        assertEquals(Long.MAX_VALUE, SaturatingAtomicCounter.add(counter, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SaturatingAtomicCounter.add(counter, -1));
        assertEquals(Long.MAX_VALUE, counter.get());
    }

    @Test void previousOverflowIsClampedRatherThanReexposed() {
        AtomicLong counter = new AtomicLong(Long.MIN_VALUE);
        assertEquals(Long.MAX_VALUE, SaturatingAtomicCounter.increment(counter));
        assertEquals(Long.MAX_VALUE, counter.get());
    }

    @Test void concurrentAuditIncrementsLoseNoEventsAndStillSaturate()
            throws Exception {
        final int workers = 8;
        final int operationsPerWorker = 10000;
        AtomicLong counter = new AtomicLong();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(workers);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (int t = 0; t < workers; ++t) {
            Thread thread = new Thread(() -> {
                try {
                    if (!start.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("audit counter start timeout");
                    }
                    for (int i = 0; i < operationsPerWorker; ++i) {
                        SaturatingAtomicCounter.increment(counter);
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    done.countDown();
                }
            }, "actor-control-counter-" + t);
            thread.setDaemon(true);
            thread.start();
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS),
                "all concurrent counter publishers must complete");
        assertNull(failure.get(), () -> "counter worker failed: " + failure.get());
        assertEquals((long) workers * operationsPerWorker, counter.get());

        // Saturation must remain a terminal value under concurrent increments.
        counter.set(Long.MAX_VALUE - 7);
        start = new CountDownLatch(1);
        done = new CountDownLatch(workers);
        // Exercise the CAS overflow edge with the same number of publishers.
        CountDownLatch edgeStart = start;
        CountDownLatch edgeDone = done;
        for (int t = 0; t < workers; ++t) {
            Thread thread = new Thread(() -> {
                try {
                    if (!edgeStart.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("overflow start timeout");
                    }
                    for (int i = 0; i < operationsPerWorker; ++i) {
                        SaturatingAtomicCounter.increment(counter);
                    }
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally {
                    edgeDone.countDown();
                }
            }, "actor-control-counter-overflow-" + t);
            thread.setDaemon(true);
            thread.start();
        }
        edgeStart.countDown();
        assertTrue(edgeDone.await(10, TimeUnit.SECONDS));
        assertNull(failure.get(), () -> "overflow worker failed: " + failure.get());
        assertEquals(Long.MAX_VALUE, counter.get());
    }
}
