package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host ABI-only tests. The Java guest evaluator still uses the JVM stack;
 * these cursor records do not certify compiler-backed return-PC lowering.
 */
final class ActorGuestReturnRecordTest {
    @Test
    void exactActorOwnedReturnPcRecordRoundTripsAndZeroizesOnUnwind() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                ActorFrameArena store = ActorRuntime.currentActorFrameArena();
                arena.set(store);
                var cursor = new ActorGuestReturnRecord.Cursor(4, 8, 13, 2);
                try (var record = ActorGuestReturnRecord.enter(cursor)) {
                    assertEquals(cursor, record.readCursor());
                    assertEquals(1, store.liveFrames());
                    assertEquals(ActorGuestReturnRecord.RECORD_BYTES, store.usedBytes());
                    assertEquals(ActorGuestReturnRecord.RECORD_BYTES, store.peakBytes());
                }
                assertEquals(0, store.usedBytes());
                assertEquals(0, store.liveFrames());
                turn.self().stop();
            });
            actor.send("return");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "record test failed: " + actor.failure());
            assertTrue(arena.get().isRetired());
        }
    }

    @Test
    void foreignCarrierCannotReadOrRetireAnOwnedReturnCursor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                var cursor = new ActorGuestReturnRecord.Cursor(1, 2, 3, 4);
                try (var record = ActorGuestReturnRecord.enter(cursor)) {
                    Thread intruder = new Thread(() -> {
                        try {
                            assertThrows(SecurityException.class, record::readCursor);
                            assertThrows(SecurityException.class, record::close);
                        } catch (Throwable failure) {
                            unexpected.set(failure);
                        }
                    });
                    intruder.start();
                    intruder.join(2000);
                    assertFalse(intruder.isAlive());
                    assertNull(unexpected.get());
                    assertEquals(cursor, record.readCursor());
                }
                turn.self().stop();
            });
            actor.send("foreign");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void callReturnRecordFrameQuotaFailsActorLocallyWithoutKillingSibling()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorFrameArena> brokenArena = new AtomicReference<>();
            AtomicInteger healthy = new AtomicInteger();
            var exhausted = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                ActorFrameArena arena = ActorRuntime.currentActorFrameArena();
                brokenArena.set(arena);
                ArrayDeque<ActorGuestReturnRecord> stack = new ArrayDeque<>();
                try {
                    assertThrows(ActorFrameArena.BudgetExceeded.class, () -> {
                        for (int i = 0; i < 65; i++) {
                            stack.addLast(ActorGuestReturnRecord.enter(
                                    new ActorGuestReturnRecord.Cursor(i, i, i + 1, i)));
                        }
                    });
                    assertTrue(arena.isPoisoned());
                    assertEquals(64, stack.size());
                } finally {
                    while (!stack.isEmpty()) stack.removeLast().close();
                }
                assertEquals(0, arena.usedBytes());
            });
            var sibling = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                healthy.incrementAndGet();
                turn.self().stop();
            });
            exhausted.send("exhaust");
            assertTrue(exhausted.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(brokenArena.get().isRetired());
            sibling.send("alive");
            assertTrue(sibling.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(sibling.failure().isEmpty());
            assertEquals(1, healthy.get());
        }
    }

    @Test
    void malformedReturnDescriptorsAreRejectedBeforeActorAllocation() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGuestReturnRecord.Cursor(-1, 0, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGuestReturnRecord.Cursor(1, -1, 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGuestReturnRecord.Cursor(1, 1, -1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ActorGuestReturnRecord.Cursor(1, 1, 1, -1));
        assertThrows(SecurityException.class, () ->
                ActorGuestReturnRecord.enter(
                        new ActorGuestReturnRecord.Cursor(1, 2, 3, 0)));
    }
    @Test
    void laterPhysicalTurnOnSameActorCarrierCannotReusePriorReturnRecord()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorGuestReturnRecord> escaped = new AtomicReference<>();
            AtomicReference<Thread> firstCarrier = new AtomicReference<>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicInteger denied = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                if ("first".equals(mail)) {
                    firstCarrier.set(Thread.currentThread());
                    arena.set(ActorRuntime.currentActorFrameArena());
                    // Deliberately retain a host capability beyond the
                    // dispatch that minted it. A future dispatcher must
                    // retire it; a later mailbox turn may never reuse it.
                    escaped.set(ActorGuestReturnRecord.enter(
                            new ActorGuestReturnRecord.Cursor(1, 8, 13, 2)));
                    return;
                }
                assertEquals("second", mail);
                assertSame(firstCarrier.get(), Thread.currentThread(),
                        "one-carrier setup should exercise same-thread reuse");
                ActorGuestReturnRecord stale = escaped.get();
                assertNotNull(stale);
                assertThrows(SecurityException.class, stale::readCursor);
                assertThrows(SecurityException.class, stale::close);
                assertEquals(ActorGuestReturnRecord.RECORD_BYTES,
                        arena.get().usedBytes(), "denial must not free the prior lease");
                assertFalse(arena.get().isPoisoned(),
                        "a foreign dispatch denial must not poison the owning actor");
                denied.incrementAndGet();
                turn.self().stop();
            });
            actor.send("first");
            actor.send("second");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(1, denied.get());
            assertTrue(actor.failure().isEmpty(), () ->
                    "same-carrier fence test failed: " + actor.failure());
            assertTrue(arena.get().isRetired());
            assertEquals(0, arena.get().usedBytes(),
                    "actor retirement must reclaim an orphaned record");
        }
    }

    @Test
    void secondSourceTaskOnSameActorCannotStealFirstTaskReturnRecord()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorGuestReturnRecord> escaped = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> one = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> two = new AtomicReference<>();
            OresFuture<Integer> firstRecordReady = new OresFuture<>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                if ("stop".equals(mail)) {
                    turn.self().stop();
                    return;
                }
                arena.set(ActorRuntime.currentActorFrameArena());
                one.set(runtime.startActorTask(resume -> {
                    escaped.set(ActorGuestReturnRecord.enter(
                            new ActorGuestReturnRecord.Cursor(2, 3, 4, 1)));
                    firstRecordReady.completeFromRuntime(1);
                    return OresScheduler.done(1);
                }));
                two.set(runtime.startActorTask(resume -> {
                    // Do not rely on arbitrary task scheduling order:
                    // the second task must await an explicit first-record
                    // witness before attempting a foreign capability.
                    if (resume.initial()) return OresScheduler.await(firstRecordReady);
                    assertNotNull(escaped.get(), "first task must create a return record");
                    assertThrows(SecurityException.class, escaped.get()::readCursor);
                    assertThrows(SecurityException.class, escaped.get()::close);
                    return OresScheduler.done(2);
                }));
            });
            actor.send("start");
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while ((two.get() == null || !two.get().isDone())
                    && System.nanoTime() < until) {
                Thread.sleep(2);
            }
            assertNotNull(one.get());
            assertNotNull(two.get());
            assertEquals(1, one.get().get(3, TimeUnit.SECONDS));
            assertEquals(2, two.get().get(3, TimeUnit.SECONDS));
            assertFalse(arena.get().isPoisoned());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
            assertEquals(0, arena.get().usedBytes());
        }
    }

    private static void corruptReturnRecord(
            ActorGuestReturnRecord record, int offset, byte[] replacement)
            throws ReflectiveOperationException {
        var klass = ActorGuestReturnRecord.class;
        var arenaField = klass.getDeclaredField("arena");
        var executionField = klass.getDeclaredField("execution");
        var handleField = klass.getDeclaredField("handle");
        arenaField.setAccessible(true);
        executionField.setAccessible(true);
        handleField.setAccessible(true);
        var arena = (ActorFrameArena) arenaField.get(record);
        var execution = (ExecutionContext) executionField.get(record);
        var handle = (ActorFrameArena.FrameRef) handleField.get(record);
        arena.write(execution, handle, offset, replacement);
    }

    @Test
    void forgedReturnPcRecordMagicPoisonsOnlyOwningActor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorFrameArena> brokenArena = new AtomicReference<>();
            AtomicInteger siblingCalls = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                var arena = ActorRuntime.currentActorFrameArena();
                brokenArena.set(arena);
                try (var record = ActorGuestReturnRecord.enter(
                        new ActorGuestReturnRecord.Cursor(8, 12, 23, 1))) {
                    corruptReturnRecord(record, 0, new byte[] { 0 });
                    assertThrows(IllegalStateException.class, record::readCursor);
                    assertTrue(arena.isPoisoned(), "corrupt ABI header must fail-stop owner");
                }
                turn.self().stop();
            });
            var good = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                siblingCalls.incrementAndGet();
                turn.self().stop();
            });
            actor.send("corrupt");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(brokenArena.get().isRetired());
            good.send("alive");
            assertTrue(good.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(good.failure().isEmpty());
            assertEquals(1, siblingCalls.get());
        }
    }

    @Test
    void changedLogicalExecutionIdentityInReturnBytesFailsClosed()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                arena.set(ActorRuntime.currentActorFrameArena());
                try (var record = ActorGuestReturnRecord.enter(
                        new ActorGuestReturnRecord.Cursor(0, 1, 2, 3))) {
                    // The final eight bytes encode the unique execution ID.
                    // Replace them with zero even while the magic and cursor
                    // fields remain fully well formed.
                    corruptReturnRecord(record, 40, new byte[8]);
                    assertThrows(IllegalStateException.class, record::readCursor);
                    assertTrue(arena.get().isPoisoned());
                }
                turn.self().stop();
            });
            actor.send("altered-execution");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(arena.get().isRetired());
        }
    }

}
