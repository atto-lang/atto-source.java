package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

final class ActorReliabilityKernelConcurrencyTest {
    @Test
    void manyRecordersPreserveBoundedOrderedEvidence() throws Exception {
        var time = new AtomicLong(1);
        var kernel = new ActorReliabilityKernel(64, time::getAndIncrement);
        var actor = new ActorReliabilityKernel.ActorKey(
                UUID.fromString("00000000-0000-0000-0000-000000000041"),
                UUID.fromString("00000000-0000-0000-0000-000000000042"), 1);
        int workers = 8;
        int each = 2_000;
        try (var pool = Executors.newFixedThreadPool(workers)) {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int w = 0; w < workers; w++) {
                tasks.add(pool.submit(() -> {
                    for (int i = 0; i < each; i++) {
                        kernel.record(
                                ActorReliabilityKernel.EventKind.ACTOR_FAILED,
                                actor,
                                ActorReliabilityKernel.FaultClass.HANDLER_FAILURE,
                                i);
                    }
                }));
            }
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }

        var snapshot = kernel.snapshot();
        assertEquals((long) workers * each,
                snapshot.counters().get(ActorReliabilityKernel.EventKind.ACTOR_FAILED));
        assertEquals(64, snapshot.recentEvents().size());
        assertEquals((long) workers * each - 64, snapshot.overwrittenEvents());
        for (int i = 1; i < snapshot.recentEvents().size(); i++) {
            var before = snapshot.recentEvents().get(i - 1);
            var after = snapshot.recentEvents().get(i);
            assertTrue(before.sequence() < after.sequence());
            assertTrue(before.monotonicNanos() <= after.monotonicNanos());
        }
    }
}
