package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler boundary host tests for SourceTask formal parameter lowering.
 *
 * These tests exercise real actor-owned frame bytes and scheduler suspension,
 * but are not compiler-backed .atto execution or physical JVM stack isolation.
 */
final class SourceTaskScalarParameterBindingTest {
    private static void until(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(2);
        }
        assertTrue(condition.getAsBoolean(), "timed out observing source task frame");
    }

    @Test
    void compilerScalarParametersAreDecodedFromChargedTaskFrameBeforeAwait() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            OresFuture<Integer> gate = new OresFuture<>();
            AtomicReference<OresFuture<Integer>> result = new AtomicReference<>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicReference<ExecutionContext> first = new AtomicReference<>();
            AtomicReference<ExecutionContext> next = new AtomicReference<>();
            AtomicInteger runs = new AtomicInteger();
            List<Object> arguments = List.of(101L, (byte) -9, true,
                    Float.intBitsToFloat(0x7fc00055));
            var actor = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                if ("stop".equals(mail)) { turn.self().stop(); return; }
                ActorFrameArena store = ActorRuntime.currentActorFrameArena();
                arena.set(store);
                result.set(runtime.startActorTask(resume -> {
                    assertSame(store, ActorRuntime.currentActorFrameArena());
                    assertNotNull(OresScheduler.currentActorSourceTaskFrame());
                    if (runs.getAndIncrement() == 0) {
                        first.set(OresScheduler.currentExecutionContext());
                        List<?> bound = OresScheduler.bindCompilerSourceArguments(arguments);
                        assertNotSame(arguments, bound,
                                "supported guest parameter values must be decoded from actor bytes");
                        assertEquals(arguments, bound);
                        assertEquals(0x7fc00055,
                                Float.floatToRawIntBits((Float) bound.get(3)));
                        assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES, store.usedBytes(),
                                "parameter lowering must not allocate a second frame");
                        assertThrows(IllegalStateException.class, () ->
                                OresScheduler.currentActorSourceTaskFrame()
                                        .saveTypedScalars(List.of(42L)),
                                "host snapshots must not overwrite compiler-owned parameters");
                        return OresScheduler.await(gate);
                    }
                    next.set(OresScheduler.currentExecutionContext());
                    OresScheduler.verifyCompilerSourceArguments(arguments);
                    assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES, store.usedBytes());
                    return OresScheduler.done(42);
                }));
            });
            actor.send("start");
            until(() -> first.get() != null && arena.get() != null
                    && arena.get().usedBytes() == ActorSourceTaskFrame.FRAME_CHARGE_BYTES);
            assertNull(OresScheduler.currentActorSourceTaskFrame(),
                    "the host must not see the task frame between dispatches");
            gate.completeFromRuntime(1);
            until(() -> result.get() != null && result.get().isDone());
            assertEquals(42, result.get().get(3, TimeUnit.SECONDS));
            assertSame(first.get(), next.get(), "task identity must survive await");
            assertEquals(2, runs.get());
            assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES, arena.get().peakBytes());
            assertEquals(0, arena.get().usedBytes());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void unsupportedSourceArgumentsStayOnExplicitLegacyPath() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<OresFuture<Integer>> result = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                if ("stop".equals(mail)) { turn.self().stop(); return; }
                result.set(runtime.startActorTask(resume -> {
                    Object reference = new Object();
                    List<Object> unsupported = List.of(reference);
                    assertSame(unsupported, OresScheduler.bindCompilerSourceArguments(unsupported),
                            "guest object/capture references must never become raw frame pointers");
                    List<Long> wide = java.util.Collections.nCopies(7, 1L);
                    assertSame(wide, OresScheduler.bindCompilerSourceArguments(wide),
                            "wider source parameter lists need real compiler frame layouts");
                    OresScheduler.verifyCompilerSourceArguments(unsupported);
                    assertEquals(ActorSourceTaskFrame.FRAME_CHARGE_BYTES,
                            ActorRuntime.currentActorFrameArena().usedBytes());
                    calls.incrementAndGet();
                    return OresScheduler.done(1);
                }));
            });
            actor.send("start");
            until(() -> result.get() != null && result.get().isDone());
            assertEquals(1, result.get().get(3, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void changedScalarParameterImagePoisonIsActorLocalAndSiblingSurvives()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<ActorFrameArena> failedArena = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> pending = new AtomicReference<>();
            AtomicInteger healthyCalls = new AtomicInteger();
            var failing = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                failedArena.set(ActorRuntime.currentActorFrameArena());
                pending.set(runtime.startActorTask(resume -> {
                    List<Object> values = List.of(2L, true);
                    OresScheduler.bindCompilerSourceArguments(values);
                    assertThrows(IllegalStateException.class, () ->
                            OresScheduler.verifyCompilerSourceArguments(List.of(3L, true)));
                    assertTrue(ActorRuntime.currentActorFrameArena().isPoisoned());
                    return OresScheduler.done(-1);
                }));
            });
            var healthy = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                healthyCalls.incrementAndGet();
                turn.self().stop();
            });
            failing.send("start");
            until(() -> pending.get() != null && pending.get().isDone());
            assertTrue(failedArena.get().isPoisoned(),
                    "swallowing a corrupted typed frame cannot recover actor admission");
            healthy.send("alive");
            assertTrue(healthy.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(healthy.failure().isEmpty());
            assertEquals(1, healthyCalls.get());
            failing.stop();
            assertTrue(failing.awaitTermination(3, TimeUnit.SECONDS));
        }
    }
}
