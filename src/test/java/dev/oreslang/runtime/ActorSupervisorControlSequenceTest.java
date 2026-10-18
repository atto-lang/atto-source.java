package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSupervisorControlSequenceTest {
    @Test
    void reservationGuaranteesFinalizerSequenceAtExhaustionBoundary() {
        var sequences = new ActorSupervisorControlSequence(Long.MAX_VALUE);
        var reserved = sequences.reserve();

        assertThrows(IllegalStateException.class, sequences::reserve,
                "a second actor cannot consume capacity promised to the first");

        assertEquals(Long.MAX_VALUE, reserved.claim());
        var done = sequences.snapshot();
        assertEquals(0L, done.nextSequence());
        assertEquals(0, done.reserved());
        assertEquals(1L, done.claimed());
        assertEquals(1L, done.reservationRejected());

        assertThrows(IllegalStateException.class, sequences::reserve);
        assertThrows(IllegalStateException.class, reserved::claim);
        assertThrows(IllegalStateException.class, reserved::release);
    }

    @Test
    void gracefulReleaseReturnsUnconsumedCapacity() {
        var sequences = new ActorSupervisorControlSequence(Long.MAX_VALUE);
        var first = sequences.reserve();
        first.release();

        var replacement = sequences.reserve();
        assertEquals(Long.MAX_VALUE, replacement.claim());
        assertEquals(1L, sequences.snapshot().released());
    }

    @Test
    void claimOrderDefinesNoticeOrderNotAdmissionOrder() {
        var sequences = new ActorSupervisorControlSequence(41L);
        var admittedFirst = sequences.reserve();
        var admittedSecond = sequences.reserve();

        assertEquals(41L, admittedSecond.claim(),
                "later-admitted actor may fail first and must get earlier notice sequence");
        assertEquals(42L, admittedFirst.claim());
    }

    @Test
    void concurrentClaimsAreUniqueAndGapFree() throws Exception {
        final int count = 512;
        var sequences = new ActorSupervisorControlSequence(1L);
        var reservations = new ActorSupervisorControlSequence.Reservation[count];
        for (int i = 0; i < count; ++i) reservations[i] = sequences.reserve();

        var start = new CountDownLatch(1);
        var sum = new AtomicLong();
        var xor = new AtomicLong();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (var reservation : reservations) {
                futures.add(executor.submit(() -> {
                    start.await();
                    long sequence = reservation.claim();
                    sum.addAndGet(sequence);
                    xor.getAndUpdate(value -> value ^ sequence);
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(5, TimeUnit.SECONDS);
        }

        long expectedSum = ((long) count * (count + 1L)) / 2L;
        long expectedXor = 0L;
        for (long i = 1; i <= count; ++i) expectedXor ^= i;
        assertEquals(expectedSum, sum.get());
        assertEquals(expectedXor, xor.get());

        var snapshot = sequences.snapshot();
        assertEquals(count + 1L, snapshot.nextSequence());
        assertEquals(0, snapshot.reserved());
        assertEquals(count, snapshot.claimed());
    }
}
