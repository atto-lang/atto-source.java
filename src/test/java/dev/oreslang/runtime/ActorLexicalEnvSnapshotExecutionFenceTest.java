package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host model for compiler Env.snapshot/Slot authorization across physical
 * suspension. Source .atto compilation and native stack survival remain
 * unexecuted, independent acceptance gates.
 */
final class ActorLexicalEnvSnapshotExecutionFenceTest {
    private static final Constructor<?> ENV_CTOR;
    private static final Method DEFINE;
    private static final Method LOOKUP;
    private static final Method ASSIGN;
    private static final Method SNAPSHOT;
    private static final Field SLOTS;

    static {
        try {
            Class<?> type = Class.forName("dev.oreslang.nodes.OresEvalRootNode$Env");
            ENV_CTOR = type.getDeclaredConstructor(type);
            ENV_CTOR.setAccessible(true);
            DEFINE = type.getDeclaredMethod("define", String.class, Object.class,
                    Ast.BindingKind.class, Ast.ClassDecl.class);
            LOOKUP = type.getDeclaredMethod("lookup", String.class);
            ASSIGN = type.getDeclaredMethod("assign", String.class, Object.class);
            SNAPSHOT = type.getDeclaredMethod("snapshot");
            SLOTS = type.getDeclaredField("slots");
            for (var method : new Method[] { DEFINE, LOOKUP, ASSIGN, SNAPSHOT }) {
                method.setAccessible(true);
            }
            SLOTS.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Object createEnv() {
        try {
            return ENV_CTOR.newInstance(new Object[] { null });
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object call(Method method, Object env, Object... args) {
        try {
            return method.invoke(env, args);
        } catch (InvocationTargetException e) {
            Throwable failure = e.getCause();
            if (failure instanceof RuntimeException ex) throw ex;
            if (failure instanceof Error fatal) throw fatal;
            throw new AssertionError(failure);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Object boundEnv(String name, long value) {
        Object env = createEnv();
        call(DEFINE, env, name, value, Ast.BindingKind.LET, null);
        return env;
    }

    private static void until(BooleanSupplier ready) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!ready.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(2);
        assertTrue(ready.getAsBoolean(), "bounded SourceTask observation timed out");
    }

    @Test
    void snapshotAndCapturedSlotAreValidInSameTaskAcrossAwaitButNotOnHost()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            var gate = new OresFuture<Integer>();
            AtomicReference<Object> env = new AtomicReference<>();
            AtomicReference<Object> captured = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> result = new AtomicReference<>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicInteger dispatches = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                if ("stop".equals(mail)) { turn.self().stop(); return; }
                ActorFrameArena owner = ActorRuntime.currentActorFrameArena();
                arena.set(owner);
                result.set(runtime.startActorTask(resume -> {
                    if (dispatches.getAndIncrement() == 0) {
                        Object frameEnv = boundEnv("counter", 40L);
                        env.set(frameEnv);
                        captured.set(call(SNAPSHOT, frameEnv));
                        assertEquals(40L, call(LOOKUP, captured.get(), "counter"));
                        assertEquals(64, owner.usedBytes());
                        return OresScheduler.await(gate);
                    }
                    // Same stable scheduler task resumes on an authenticated
                    // physical carrier: a legitimate lexical snapshot works.
                    assertEquals(40L, call(LOOKUP, captured.get(), "counter"));
                    call(ASSIGN, env.get(), "counter", 42L);
                    assertEquals(42L, call(LOOKUP, captured.get(), "counter"));
                    assertEquals(42L, call(LOOKUP, env.get(), "counter"));
                    return OresScheduler.done(42);
                }));
            });
            actor.send("start");
            until(() -> env.get() != null && arena.get() != null
                    && arena.get().usedBytes() == 64);
            assertThrows(SecurityException.class, () -> call(SNAPSHOT, env.get()));
            assertThrows(SecurityException.class,
                    () -> call(LOOKUP, captured.get(), "counter"));
            assertThrows(SecurityException.class,
                    () -> call(ASSIGN, env.get(), "counter", 99L));
            assertFalse(arena.get().isPoisoned(),
                    "foreign-thread denial must not corrupt the real actor");
            gate.completeFromRuntime(1);
            until(() -> result.get() != null && result.get().isDone());
            assertEquals(42, result.get().get(3, TimeUnit.SECONDS));
            assertEquals(2, dispatches.get());
            assertEquals(0, arena.get().usedBytes());
            // Even after all bytes are retired, the leaked Env must not
            // silently fall back to its old JVM Slot.value compatibility copy.
            assertThrows(SecurityException.class,
                    () -> call(LOOKUP, captured.get(), "counter"));
            assertThrows(SecurityException.class,
                    () -> call(SNAPSHOT, env.get()));
            assertFalse(arena.get().isPoisoned());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failure: " + actor.failure());
        }
    }

    @Test
    void secondSourceTaskOnSameActorCannotSnapshotOrAccessFirstTaskSlot()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            var releaseFirst = new OresFuture<Integer>();
            var firstReady = new OresFuture<Integer>();
            AtomicReference<Object> originalEnv = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> first = new AtomicReference<>();
            AtomicReference<OresFuture<Integer>> second = new AtomicReference<>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            AtomicInteger firstDispatches = new AtomicInteger();
            AtomicInteger secondDispatches = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                if ("stop".equals(mail)) { turn.self().stop(); return; }
                arena.set(ActorRuntime.currentActorFrameArena());
                first.set(runtime.startActorTask(resume -> {
                    if (firstDispatches.getAndIncrement() == 0) {
                        originalEnv.set(boundEnv("counter", 11L));
                        firstReady.completeFromRuntime(1);
                        return OresScheduler.await(releaseFirst);
                    }
                    assertEquals(11L, call(LOOKUP, originalEnv.get(), "counter"));
                    return OresScheduler.done(11);
                }));
                second.set(runtime.startActorTask(resume -> {
                    if (secondDispatches.getAndIncrement() == 0)
                        return OresScheduler.await(firstReady);
                    assertNotNull(originalEnv.get());
                    assertThrows(SecurityException.class,
                            () -> call(SNAPSHOT, originalEnv.get()));
                    assertThrows(SecurityException.class,
                            () -> call(LOOKUP, originalEnv.get(), "counter"));
                    assertThrows(SecurityException.class,
                            () -> call(ASSIGN, originalEnv.get(), "counter", 20L));
                    assertFalse(arena.get().isPoisoned(),
                            "a different SourceTask must not poison shared actor arena");
                    return OresScheduler.done(2);
                }));
            });
            actor.send("start");
            until(() -> second.get() != null && second.get().isDone());
            assertEquals(2, second.get().get(3, TimeUnit.SECONDS));
            assertFalse(arena.get().isPoisoned());
            releaseFirst.completeFromRuntime(1);
            until(() -> first.get() != null && first.get().isDone());
            assertEquals(11, first.get().get(3, TimeUnit.SECONDS));
            assertEquals(0, arena.get().usedBytes());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void corruptedOwnerTokenCannotAuthorizeScalarEnvReadOrSnapshot()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 1, 8))) {
            AtomicReference<OresFuture<Integer>> result = new AtomicReference<>();
            AtomicReference<ActorFrameArena> arena = new AtomicReference<>();
            var actor = runtime.<String>spawnShared(factory -> (mail, turn) -> {
                if ("stop".equals(mail)) { turn.self().stop(); return; }
                arena.set(ActorRuntime.currentActorFrameArena());
                result.set(runtime.startActorTask(resume -> {
                    Object env = boundEnv("num", 7L);
                    try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> slots = (Map<String, Object>) SLOTS.get(env);
                        Object bound = slots.get("num");
                        Field owner = bound.getClass().getDeclaredField("scalarOwnerExecution");
                        owner.setAccessible(true);
                        Object original = owner.get(bound);
                        assertSame(OresScheduler.currentExecutionContext(), original);
                        owner.set(bound, null);
                        assertThrows(SecurityException.class,
                                () -> call(LOOKUP, env, "num"));
                        assertThrows(SecurityException.class,
                                () -> call(SNAPSHOT, env));
                        assertFalse(arena.get().isPoisoned());
                        owner.set(bound, original);
                        assertEquals(7L, call(LOOKUP, env, "num"));
                    } catch (ReflectiveOperationException e) {
                        throw new AssertionError(e);
                    }
                    return OresScheduler.done(7);
                }));
            });
            actor.send("run");
            until(() -> result.get() != null && result.get().isDone());
            assertEquals(7, result.get().get(3, TimeUnit.SECONDS));
            assertEquals(0, arena.get().usedBytes());
            actor.send("stop");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }
}
