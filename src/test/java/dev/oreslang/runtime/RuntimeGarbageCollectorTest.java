package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

final class RuntimeGarbageCollectorTest {
    @Test
    void processSweepRunsOnlyQueuedRuntimeCleanup() {
        AtomicInteger cleaned = new AtomicInteger();
        Object owner = new Object();

        try (RuntimeGarbageCollector collector = new RuntimeGarbageCollector(1_000)) {
            RuntimeGarbageCollector.Registration registration =
                    collector.trackProcess(owner, "test-process-handle", cleaned::incrementAndGet);

            assertEquals(1L, collector.stats().tracked());
            assertTrue(registration.enqueueForTesting());

            RuntimeGarbageCollector.GcReport report = collector.collectProcess();
            assertEquals(1L, report.cleaned());
            assertEquals(0L, report.failed());
            assertEquals(1, cleaned.get());
            assertFalse(registration.active());

            // Cleanup is idempotent even if an explicit close races a sweep.
            registration.close();
            assertEquals(1, cleaned.get());
        }
    }

    @Test
    void actorSweepNeverCleansAnotherActorsCandidate() {
        AtomicInteger actorA = new AtomicInteger();
        AtomicInteger actorB = new AtomicInteger();
        Object ownerA = new Object();
        Object ownerB = new Object();

        try (RuntimeGarbageCollector collector = new RuntimeGarbageCollector(1_000)) {
            RuntimeGarbageCollector.Registration a =
                    collector.trackActor(ownerA, "actor-a", "private-a", actorA::incrementAndGet);
            RuntimeGarbageCollector.Registration b =
                    collector.trackActor(ownerB, "actor-b", "private-b", actorB::incrementAndGet);

            assertTrue(a.enqueueForTesting());
            assertTrue(b.enqueueForTesting());

            RuntimeGarbageCollector.GcReport local = collector.collectActor("actor-a");
            assertEquals(1L, local.cleaned());
            assertEquals(1, actorA.get());
            assertEquals(0, actorB.get());
            assertTrue(b.active());

            RuntimeGarbageCollector.GcReport process = collector.collectProcess();
            assertEquals(1L, process.cleaned());
            assertEquals(1, actorB.get());
        }
    }

    @Test
    void runtimeCloseCleansStillReachableFallbackResources() {
        AtomicInteger cleaned = new AtomicInteger();
        Object owner = new Object();

        RuntimeGarbageCollector collector = new RuntimeGarbageCollector();
        RuntimeGarbageCollector.Registration registration =
                collector.trackProcess(owner, "live-runtime-handle", cleaned::incrementAndGet);

        collector.close();

        assertEquals(1, cleaned.get());
        assertFalse(registration.active());
        assertEquals(0L, collector.stats().tracked());
    }
}
