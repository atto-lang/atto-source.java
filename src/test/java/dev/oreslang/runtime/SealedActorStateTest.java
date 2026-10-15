package dev.oreslang.runtime;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SealedActorStateTest {
    @Test void synchronousStateIsPrivateToActorAndScopedToCriticalCallback() throws Exception {
        var state = new AtomicReference<SealedActorState>();
        var ownerId = new AtomicReference<ActorRuntime.ActorId>();
        var stale = new AtomicReference<SealedActorState.Access>();
        var failure = new AtomicReference<Throwable>();
        var completed = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var worker = runtime.<Integer>spawnShared(() -> (mail, ctx) -> {
                try {
                    if (state.get() == null) {
                        ownerId.set(ctx.self().id());
                        state.set(new SealedActorState(ctx.self().id(),
                                Map.of("count", "int", "enabled", "bool", "name", "string"),
                                Map.of("count", 0L, "enabled", true, "name", "counter")));
                    }
                    SealedActorState current = state.get();
                    if (mail > 0) {
                        current.critical(access -> {
                            long previous = ((Number) access.read("count")).longValue();
                            access.write("count", previous + 1);
                            stale.set(access);
                            return null;
                        });
                    } else {
                        // A declared empty state map is legal, but remains
                        // fixed-schema; callbacks cannot invent keys.
                        SealedActorState empty = new SealedActorState(
                                ctx.self().id(), Map.of(), Map.of());
                        assertTrue(empty.declaredKeys().isEmpty());
                        assertEquals(7, empty.critical(false, s -> 7));
                        assertThrows(IllegalArgumentException.class,
                                () -> empty.critical(false, s -> s.read("missing")));
                        assertThrows(IllegalArgumentException.class,
                                () -> empty.critical(true, s -> {
                                    s.write("dynamic", 1L);
                                    return null;
                                }));
                        assertEquals(12L, ((Number) current.critical(s -> s.read("count"))).longValue());
                        assertEquals(true, current.critical(s -> s.read("enabled")));
                        current.critical(outer -> {
                            assertEquals(12L, outer.read("count"));
                            assertEquals(12L, ((Number) current.critical(inner -> {
                                assertThrows(IllegalStateException.class, () -> outer.read("count"));
                                assertEquals(12L, inner.read("count"));
                                return inner.read("count");
                            })).longValue());
                            assertEquals(12L, outer.read("count"));
                            assertThrows(IllegalArgumentException.class,
                                    () -> current.critical(inner -> inner));
                            assertThrows(IllegalArgumentException.class,
                                    () -> current.critical(inner ->
                                            new java.util.concurrent.atomic.AtomicInteger(7)));
                            assertThrows(IllegalArgumentException.class,
                                    () -> current.critical(inner ->
                                            new java.util.concurrent.atomic.AtomicLong(9)));
                            assertEquals(12L, outer.read("count"));
                            return null;
                        });
                        // Guest READ mode is enforced by the actor-owned runtime too.
                        assertEquals(12L, ((Number) current.critical(false,
                                s -> s.read("count"))).longValue());
                        assertThrows(SecurityException.class, () -> current.critical(false, s -> {
                            s.write("count", 99L);
                            return null;
                        }));
                        current.critical(false, read -> {
                            assertThrows(SecurityException.class, () ->
                                    current.critical(true, write -> {
                                        write.write("count", 88L);
                                        return null;
                                    }));
                            assertEquals(12L, ((Number) read.read("count")).longValue());
                            return null;
                        });
                        assertThrows(IllegalStateException.class, () -> stale.get().read("count"));
                        assertThrows(IllegalArgumentException.class,
                                () -> current.critical(s -> { s.write("count", "invalid"); return null; }));
                        assertThrows(IllegalArgumentException.class,
                                () -> current.critical(s -> s.read("missing")));
                        assertThrows(IllegalArgumentException.class,
                                () -> current.critical(s -> {
                                    s.write("count", "not an integer");
                                    return null;
                                }));
                        assertThrows(IllegalArgumentException.class,
                                () -> current.critical(s -> {
                                    s.write("name", "x".repeat(64 * 1024 + 1));
                                    return null;
                                }));
                        // Knowing the correct owner ID inside a live actor
                        // does not authorize preemptive state retirement.
                        assertThrows(SecurityException.class,
                                () -> current.retireFromRuntime(ctx.self().id()));
                        assertEquals(12L, current.critical(s -> s.read("count")));
                        completed.countDown();
                    }
                } catch (Throwable x) {
                    failure.compareAndSet(null, x);
                    completed.countDown();
                }
            });
            for (int i = 0; i < 12; i++) worker.send(1);
            worker.send(0);
            assertTrue(completed.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
            // An external thread can know the actor identity but cannot
            // forge the runtime's quiescence-only state-wipe authority.
            assertThrows(SecurityException.class,
                    () -> state.get().retireFromRuntime(ownerId.get()));
            assertThrows(SecurityException.class,
                    () -> state.get().critical(s -> s.read("count")));
        }
    }
}
