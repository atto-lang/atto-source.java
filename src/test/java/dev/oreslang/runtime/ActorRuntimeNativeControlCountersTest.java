package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Exercises real Java actor registration and asynchronous STOP retirement
 * against a separate FFM CONTROL root. JVM carrier/metadata allocations are
 * still managed; this test does not claim compiled Atto or process zero-GC.
 */
@Timeout(30)
final class ActorRuntimeNativeControlCountersTest {
    @Test
    void startupActorAdmissionRollbackAndQuiescentShutdown() throws Exception {
        ActorRuntime runtime = new ActorRuntime();
        try {
            long before = runtime.sharedMemoryBytes();
            assertEquals(0, runtime.nativeControlReservedBytes());
            assertThrows(IllegalStateException.class, runtime::nativeControlActorReservations);
            assertThrows(IllegalStateException.class, runtime::nativeControlCompletedMessages);
            assertThrows(IllegalStateException.class, () -> runtime.nativeControlSnapshot(new long[2]));
            runtime.enableNativeControlCounters();
            assertEquals(44L, runtime.nativeControlReservedBytes());
            assertEquals(before + 44L, runtime.sharedMemoryBytes(),
                    "native CONTROL root must count against the aggregate runtime budget");
            assertEquals(0L, runtime.nativeControlActorReservations());
            assertEquals(0L, runtime.nativeControlCompletedMessages());
            long[] snapshot = new long[2];
            runtime.nativeControlSnapshot(snapshot);
            assertArrayEquals(new long[] {0L, 0L}, snapshot);
            assertThrows(IllegalArgumentException.class,
                    () -> runtime.nativeControlSnapshot(new long[1]));
            assertThrows(IllegalStateException.class, runtime::enableNativeControlCounters);

            ActorRuntime.ActorRef<String> first =
                    runtime.spawnPrivate(() -> (message, context) -> {});
            assertEquals(1, runtime.actorCount());
            assertEquals(1L, runtime.nativeControlActorReservations(),
                    "native control reservation reflects actual actor admission");
            first.send("ping");
            long completionDeadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < completionDeadline
                    && runtime.nativeControlCompletedMessages() != 1L) {
                Thread.sleep(1);
            }
            assertEquals(1L, runtime.nativeControlCompletedMessages(),
                    "real actor mailbox completion must be published to native CONTROL bytes");
            runtime.nativeControlSnapshot(snapshot);
            assertArrayEquals(new long[] {1L, 1L}, snapshot,
                    "one atomic native 128-bit snapshot records live actors and completions");
            runtime.stop(first);
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (System.nanoTime() < deadline &&
                    (runtime.actorCount() != 0 ||
                     runtime.nativeControlActorReservations() != 0)) {
                Thread.sleep(1);
            }
            assertEquals(0, runtime.actorCount(), "actor STOP must physically unregister");
            assertEquals(0L, runtime.nativeControlActorReservations(),
                    "control native count is reclaimed only after actor retirement");
            runtime.nativeControlSnapshot(snapshot);
            assertArrayEquals(new long[] {0L, 1L}, snapshot,
                    "STOP clears actor count without losing committed message completion");
            // A warmed, caller-owned two-limb snapshot proves no per-read
            // Java object allocation; it does not certify the actor scheduler.
            var bean = java.lang.management.ManagementFactory.getThreadMXBean();
            assertInstanceOf(com.sun.management.ThreadMXBean.class, bean,
                    "JDK25 allocation counter is required for strict host-path evidence");
            var measured = (com.sun.management.ThreadMXBean) bean;
            assertTrue(measured.isThreadAllocatedMemorySupported());
            if (!measured.isThreadAllocatedMemoryEnabled())
                measured.setThreadAllocatedMemoryEnabled(true);
            for (int i = 0; i < 8192; i++) {
                runtime.nativeControlSnapshot(snapshot);
                if (snapshot[0] != 0L || snapshot[1] != 1L)
                    throw new AssertionError("native snapshot warmup mismatch");
            }
            long allocatedBefore = measured.getThreadAllocatedBytes(Thread.currentThread().threadId());
            for (int i = 0; i < 8192; i++) {
                runtime.nativeControlSnapshot(snapshot);
                if (snapshot[0] != 0L || snapshot[1] != 1L)
                    throw new AssertionError("native snapshot mismatch");
            }
            long allocatedAfter = measured.getThreadAllocatedBytes(Thread.currentThread().threadId());
            assertTrue(allocatedBefore >= 0 && allocatedAfter >= allocatedBefore);
            assertEquals(allocatedBefore, allocatedAfter,
                    "warmed actor-runtime native CONTROL snapshot must allocate zero JVM bytes");
            System.out.println("PASS java actor-runtime native control snapshot managed-byte delta=0 (HOST ONLY)");
        } finally {
            runtime.close();
        }
        assertEquals(0, runtime.nativeControlReservedBytes(),
                "control backing is invalidated after carrier quiescence");
        assertThrows(IllegalStateException.class, runtime::nativeControlActorReservations);
        assertThrows(IllegalStateException.class, runtime::nativeControlCompletedMessages);
        assertThrows(IllegalStateException.class, () -> runtime.nativeControlSnapshot(new long[2]));
        System.out.println("PASS java actor-native CONTROL registration and STOP (HOST only, not Atto guest)");
    }
}
