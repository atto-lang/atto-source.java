package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.lang.management.ManagementFactory;
import com.sun.management.ThreadMXBean;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-backend actor-memory v1 HOST-SUBSTRATE probe.
 * This uses simulated turn boundaries, not compiler-lowered AttoLang guest code.
 * Direct buffers and JVM facades do not establish zero JVM GC.
 */
final class ActorMemoryE2EV1Test {
    @Test
    void ownedStateSurvivesTurnsWhileTemporaryOwnersDrop() {
        try (var actor = new ActorLinearArena(4096, 8);
             var foreignActor = new ActorLinearArena(4096, 8)) {
            long outer = actor.cleanupMark();
            long state = actor.allocateOwned(16, 8);
            long foreign = foreignActor.allocate(16, 8);
            assertNotEquals(ActorLinearArena.INVALID_HANDLE, state);
            assertNotEquals(ActorLinearArena.INVALID_HANDLE, foreign);
            assertTrue(actor.putLong(state, 0, 42));
            assertFalse(actor.putLong(foreign, 0, 999),
                    "foreign actor handle must not grant private heap authority");

            for (int i = 0; i < 1024; i++) {
                assertEquals(42L + i, actor.getLong(state, 0),
                        "persistent value must survive a simulated turn exit");
                long turnMark = actor.cleanupMark();
                long temporary = actor.allocateOwned(16, 8);
                assertNotEquals(0L, temporary);
                assertEquals(0L, actor.getLong(temporary, 0),
                        "recycled native bytes must be cleared");
                assertTrue(actor.putLong(temporary, 0, i));
                assertTrue(actor.putLong(state, 0, 43L + i));
                assertTrue(actor.cleanupTo(turnMark),
                        "compiler-style cleanup must reclaim temporary owner");
                assertEquals(1, actor.liveAllocations(),
                        "only actor persistent state may survive the turn");
                assertThrows(IllegalStateException.class,
                        () -> actor.getLong(temporary, 0),
                        "dropped temporary handle must remain invalid");
            }

            long stale = actor.allocateOwned(8, 8);
            assertNotEquals(0L, stale);
            assertTrue(actor.dropOwned(stale));
            long reused = actor.allocateOwned(8, 8);
            assertNotEquals(0L, reused);
            assertNotEquals(stale, reused, "slot reuse must mint a fresh handle");
            assertEquals(0L, actor.getLong(reused, 0));
            assertFalse(actor.putLong(stale, 0, 12));
            assertThrows(IllegalStateException.class, () -> actor.getLong(stale, 0));
            assertTrue(actor.cleanupTo(outer), "all tracked owners must drop");
            assertEquals(0, actor.liveAllocations());
            assertEquals(0, actor.usedBytes());
            actor.close();
            assertTrue(actor.closed());
            assertEquals(ActorLinearArena.INVALID_HANDLE, actor.allocate(8));
        }
        System.out.println("ACTOR_MEMORY_E2E_V1_HOST_ONLY=PASS");
        System.out.println("ACTOR_MEMORY_E2E_V1_COMPILED_GUEST=NOT_EXECUTED");
    }

    @Test
    void warmedOwnedTurnUsesNoPerTurnJvmAllocations() {
        var platformBean = ManagementFactory.getThreadMXBean();
        assertInstanceOf(ThreadMXBean.class, platformBean,
                "JDK25 host must expose per-thread allocated-byte instrumentation");
        var bean = (ThreadMXBean) platformBean;
        assertTrue(bean.isThreadAllocatedMemorySupported(),
                "allocation monitoring must be available rather than silently skipped");
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        final long threadId = Thread.currentThread().threadId();
        try (var arena = new ActorLinearArena(256, 2)) {
            final long retained = arena.allocateOwned(16, 8);
            assertNotEquals(0L, retained, "persistent owner admission");
            assertTrue(arena.putLong(retained, 0, 42L));

            // Run the exact measured operations before taking the thread allocation
            // baseline. Do not include JUnit assertions or lambda creation in the
            // measured scope: both can allocate control-plane objects.
            long warmChecksum = 0;
            for (int i = 0; i < 30_000; ++i) {
                long mark = arena.cleanupMark();
                long temp = arena.allocateOwned(16, 8);
                if (temp == 0L) fail("warmup native owner allocation unexpectedly exhausted");
                warmChecksum += arena.getLong(retained, 0);
                if (!arena.putLong(temp, 0, i) || !arena.cleanupTo(mark)) {
                    fail("warmup native owner cleanup failed");
                }
            }
            assertTrue(arena.usedBytes() > 0,
                    "persistent actor owner must survive warmup");
            assertEquals(1, arena.liveAllocations());
            assertNotEquals(0L, warmChecksum);

            long before = bean.getThreadAllocatedBytes(threadId);
            assertTrue(before >= 0L, "no silent -1 allocation-counter value");
            long checksum = 0L;
            boolean operationsSucceeded = true;
            for (int i = 0; i < 16_384; ++i) {
                long mark = arena.cleanupMark();
                long temp = arena.allocateOwned(16, 8);
                if (temp == 0L) {
                    operationsSucceeded = false;
                    break;
                }
                checksum += arena.getLong(retained, 0);
                operationsSucceeded &= arena.putLong(temp, 0, i);
                operationsSucceeded &= arena.cleanupTo(mark);
            }
            long allocatedBytes = bean.getThreadAllocatedBytes(threadId) - before;
            assertTrue(operationsSucceeded,
                    "warmed primitive-handle native ownership operations must all succeed");
            assertEquals(42L * 16_384, checksum,
                    "retained actor state must be observed each iteration");
            assertEquals(0L, allocatedBytes,
                    "warmed host-only actor arena loop must not allocate JVM heap bytes");
            assertEquals(1, arena.liveAllocations());
            assertTrue(arena.dropOwned(retained));
            assertEquals(0, arena.liveAllocations());
            assertEquals(0, arena.usedBytes());
        }
    }
}
