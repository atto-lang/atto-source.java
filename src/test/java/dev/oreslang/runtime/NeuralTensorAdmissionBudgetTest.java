package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

final class NeuralTensorAdmissionBudgetTest {
    private static NeuralActorSchema.Ticket ticket() {
        return new NeuralActorSchema.Ticket(UUID.randomUUID(),
                new NeuralActorSchema.ModelVersion("llm", "ckpt17", 9), 1, 0, 5, 0);
    }

    private static NeuralActorSchema.TensorShape shape(long elements) {
        return new NeuralActorSchema.TensorShape(
                NeuralActorSchema.DType.FP32, List.of(elements));
    }

    @Test
    void batchReservationIsAllOrNothingAndIdempotentOnRelease() {
        var budget = new NeuralTensorAdmissionBudget(1024, 3);
        var owner = ticket();
        var first = budget.reserve(owner, List.of(shape(100), shape(25)));
        assertEquals(500, budget.snapshot().reservedBytes());
        assertEquals(2, budget.snapshot().reservedDescriptors());
        assertDoesNotThrow(() -> first.assertCurrent(owner));
        assertThrows(SecurityException.class, () -> first.assertCurrent(ticket()));
        assertThrows(IllegalStateException.class, () ->
                budget.reserve(owner, List.of(shape(100), shape(40))));
        assertEquals(500, budget.snapshot().reservedBytes());
        assertEquals(2, budget.snapshot().reservedDescriptors());
        first.close();
        first.close();
        assertEquals(0, budget.snapshot().reservedBytes());
        assertEquals(0, budget.snapshot().reservedDescriptors());
        assertThrows(SecurityException.class, () -> first.assertCurrent(owner));
        try (var second = budget.reserve(owner, List.of(shape(100)))) {
            assertTrue(second.generation() > first.generation());
        }
        assertEquals(0, budget.snapshot().reservedBytes());
    }

    @Test
    void invalidAndOversizedBatchesFailWithoutAllocatingPermits() {
        var budget = new NeuralTensorAdmissionBudget(512, 2);
        var owner = ticket();
        assertThrows(IllegalArgumentException.class, () ->
                budget.reserve(owner, List.of()));
        assertThrows(IllegalArgumentException.class, () ->
                budget.reserve(owner, List.of(shape(1), shape(1), shape(1))));
        assertThrows(IllegalStateException.class, () ->
                budget.reserve(owner, List.of(shape(512))));
        assertThrows(IllegalArgumentException.class, () ->
                new NeuralTensorAdmissionBudget(0, 1));
        assertEquals(0, budget.snapshot().reservedDescriptors());
    }

    @Test
    void concurrentReservationsNeverOverbookDescriptorOrByteBudget() throws Exception {
        var budget = new NeuralTensorAdmissionBudget(128, 1);
        var start = new CountDownLatch(1);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var wins = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var owner = ticket();
            var first = pool.submit(() -> {
                start.await();
                try (var permit = budget.reserve(owner, List.of(shape(16)))) {
                    wins.incrementAndGet();
                    entered.countDown();
                    if (!release.await(2, TimeUnit.SECONDS)) throw new AssertionError("test timeout");
                }
                return null;
            });
            assertEquals(1, start.getCount());
            start.countDown();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () ->
                    budget.reserve(owner, List.of(shape(16))));
            release.countDown();
            first.get(2, TimeUnit.SECONDS);
        }
        assertEquals(1, wins.get());
        assertEquals(0, budget.snapshot().reservedBytes());
    }
}
