package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Authored host-runtime evidence for real scalar lexical load/store against
 * the actor SourceTask frame. Does not demonstrate stackless JVM execution.
 */
final class ActorSourceTaskLexicalFrameTest {
    private static void until(BooleanSupplier check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!check.getAsBoolean() && System.nanoTime() < end) Thread.sleep(2);
        assertTrue(check.getAsBoolean(), "bounded actor lexical frame observation timed out");
    }

    @Test
    void lexicalScalarValueIsReadFromActorOwnedBytesAcrossAwaitAndReassignment()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            var gate = new OresFuture<Integer>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> task = new AtomicReference<>();
            AtomicReference<ExecutionContext> before = new AtomicReference<>();
            AtomicReference<ExecutionContext> after = new AtomicReference<>();
            AtomicInteger runs = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                if ("stop".equals(message)) { turn.self().stop(); return; }
                ActorFrameArena store = ActorRuntime.currentActorFrameArena();
                arena.set(store);
                task.set(runtime.startActorTask(resume -> {
                    assertSame(store, ActorRuntime.currentActorFrameArena());
                    if (runs.getAndIncrement() == 0) {
                        before.set(OresScheduler.currentExecutionContext());
                        assertTrue(OresScheduler.bindCompilerScalarLocal(1001L, "total", 40L));
                        assertEquals(40L, OresScheduler.readCompilerScalarLocal(1001L, "total"));
                        assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES, store.usedBytes());
                        return OresScheduler.await(gate);
                    }
                    after.set(OresScheduler.currentExecutionContext());
                    assertEquals(40L, OresScheduler.readCompilerScalarLocal(1001L, "total"));
                    OresScheduler.writeCompilerScalarLocal(1001L, "total", 42L);
                    assertEquals(42L, OresScheduler.readCompilerScalarLocal(1001L, "total"));
                    return OresScheduler.done(42);
                }));
            });
            actor.send("start");
            until(() -> before.get() != null && arena.get() != null
                    && arena.get().usedBytes() == ActorSourceTaskFrame.FRAME_CHARGE_BYTES);
            assertEquals(1, arena.get().liveFrames(), "lexical slots share one bootstrap frame");
            gate.completeFromRuntime(1);
            until(() -> task.get() != null && task.get().isDone());
            assertEquals(42, task.get().get(3, TimeUnit.SECONDS));
            assertSame(before.get(), after.get());
            assertEquals(2, runs.get());
            assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES, arena.get().peakBytes());
            assertEquals(0, arena.get().usedBytes());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void parametersAndLexicalSlotsShareSixSlotCapWithoutExtraFrame() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<OresFuture<Integer>> result = new AtomicReference<>();
            AtomicReference<ActorFrameArena> owner = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(factory -> (msg, turn) -> {
                if ("stop".equals(msg)) { turn.self().stop(); return; }
                ActorFrameArena arena = ActorRuntime.currentActorFrameArena();
                owner.set(arena);
                result.set(runtime.startActorTask(resume -> {
                    List<Object> args = List.of(10L, 20L);
                    assertNotSame(args, OresScheduler.bindCompilerSourceArguments(args));
                    for (int i = 0; i < 4; i++) {
                        assertTrue(OresScheduler.bindCompilerScalarLocal(
                                300L, "local-" + i, (long) i));
                    }
                    assertFalse(OresScheduler.bindCompilerScalarLocal(300L, "overflow", 9L));
                    OresScheduler.verifyCompilerSourceArguments(args);
                    assertEquals(1, arena.liveFrames());
                    assertEquals(64, arena.usedBytes());
                    for (int i = 0; i < 4; i++) {
                        assertEquals((long) i,
                                OresScheduler.readCompilerScalarLocal(300L, "local-" + i));
                    }
                    return OresScheduler.done(1);
                }));
            });
            actor.send("run");
            until(() -> result.get() != null && result.get().isDone());
            assertEquals(1, result.get().get(3, TimeUnit.SECONDS));
            assertEquals(64, owner.get().peakBytes());
            assertEquals(0, owner.get().usedBytes());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void identicallyNamedLexicalBindingsInDistinctScopesDoNotAlias() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<OresFuture<Integer>> result = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(factory -> (msg, turn) -> {
                if ("stop".equals(msg)) { turn.self().stop(); return; }
                result.set(runtime.startActorTask(resume -> {
                    assertTrue(OresScheduler.bindCompilerScalarLocal(111L, "counter", 10));
                    assertTrue(OresScheduler.bindCompilerScalarLocal(222L, "counter", 20));
                    OresScheduler.writeCompilerScalarLocal(111L, "counter", 11);
                    assertEquals(11, OresScheduler.readCompilerScalarLocal(111L, "counter"));
                    assertEquals(20, OresScheduler.readCompilerScalarLocal(222L, "counter"));
                    return OresScheduler.done(31);
                }));
            });
            actor.send("run");
            until(() -> result.get() != null && result.get().isDone());
            assertEquals(31, result.get().get(3, TimeUnit.SECONDS));
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void foreignCarrierCannotReadOrMutateSuspendedLexicalSlots() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            var gate = new OresFuture<Integer>();
            AtomicReference<ActorFrameArena> owner = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> result = new AtomicReference<>();
            AtomicInteger turns = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (msg, turn) -> {
                if ("stop".equals(msg)) { turn.self().stop(); return; }
                owner.set(ActorRuntime.currentActorFrameArena());
                result.set(runtime.startActorTask(resume -> {
                    if (turns.getAndIncrement() == 0) {
                        assertTrue(OresScheduler.bindCompilerScalarLocal(11L, "v", 12L));
                        return OresScheduler.await(gate);
                    }
                    assertEquals(12L, OresScheduler.readCompilerScalarLocal(11L, "v"));
                    return OresScheduler.done(12);
                }));
            });
            actor.send("start");
            until(() -> turns.get() == 1 && owner.get() != null && owner.get().usedBytes() == 64);
            assertThrows(SecurityException.class,
                    () -> OresScheduler.readCompilerScalarLocal(11L, "v"));
            assertThrows(SecurityException.class,
                    () -> OresScheduler.writeCompilerScalarLocal(11L, "v", 99L));
            assertFalse(owner.get().isPoisoned(), "foreign attempt must not poison owner");
            gate.completeFromRuntime(1);
            until(() -> result.get() != null && result.get().isDone());
            assertEquals(12, result.get().get(3, TimeUnit.SECONDS));
            assertEquals(0, owner.get().usedBytes());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void primitiveKindMutationFailsClosedWhileHealthySiblingSurvives() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<OresFuture<Integer>> pending = new AtomicReference<>();
            AtomicReference<ActorFrameArena> store = new AtomicReference<>();
            AtomicInteger healthy = new AtomicInteger();
            var broken = runtime.<String>spawnShared(factory -> (msg, turn) -> {
                store.set(ActorRuntime.currentActorFrameArena());
                pending.set(runtime.startActorTask(resume -> {
                    assertTrue(OresScheduler.bindCompilerScalarLocal(11L, "count", 40L));
                    assertThrows(IllegalStateException.class, () ->
                            OresScheduler.writeCompilerScalarLocal(11L, "count", true));
                    assertTrue(store.get().isPoisoned());
                    return OresScheduler.done(-1);
                }));
            });
            var good = runtime.<String>spawnShared(factory -> (msg, turn) -> {
                healthy.incrementAndGet();
                turn.self().stop();
            });
            broken.send("poison");
            until(() -> pending.get() != null && pending.get().isDone());
            assertTrue(store.get().isPoisoned());
            good.send("alive");
            assertTrue(good.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(1, healthy.get());
            assertTrue(good.failure().isEmpty());
            broken.stop();
            assertTrue(broken.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancelledTaskCannotReanimateItsFormerLexicalFrame() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            var gate = new OresFuture<Integer>();
            AtomicReference<OresFuture<Integer>> result = new AtomicReference<>();
            AtomicReference<ActorFrameArena> owner = new AtomicReference<>();
            AtomicInteger turns = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (msg, turn) -> {
                if ("stop".equals(msg)) { turn.self().stop(); return; }
                owner.set(ActorRuntime.currentActorFrameArena());
                result.set(runtime.startActorTask(resume -> {
                    turns.incrementAndGet();
                    assertTrue(OresScheduler.bindCompilerScalarLocal(7L, "value", 9L));
                    return OresScheduler.await(gate);
                }));
            });
            actor.send("start");
            until(() -> turns.get() == 1 && owner.get() != null && owner.get().usedBytes() == 64);
            assertTrue(result.get().cancel(false));
            until(() -> owner.get().usedBytes() == 0);
            gate.completeFromRuntime(1);
            assertEquals(1, turns.get());
            assertEquals(0, owner.get().liveFrames());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }
}
