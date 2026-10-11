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
        var stale = new AtomicReference<SealedActorState.Access>();
        var failure = new AtomicReference<Throwable>();
        var completed = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var worker = runtime.<Integer>spawnShared(() -> (mail, ctx) -> {
                try {
                    if (state.get() == null) {
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
                        assertEquals(12L, ((Number) current.critical(s -> s.read("count"))).longValue());
                        assertTrue((Boolean) current.critical(s -> s.read("enabled")));
                        current.critical(outer -> {
                            assertEquals(12L, ((Number) outer.read("count")).longValue());
                            assertEquals(12L, ((Number) current.critical(inner -> {
                                assertThrows(IllegalStateException.class, () -> outer.read("count"));
                                assertEquals(12L, ((Number) inner.read("count")).longValue());
                                return inner.read("count");
                            })).longValue());
                            assertEquals(12L, ((Number) outer.read("count")).longValue());
                            assertThrows(IllegalArgumentException.class,
                                    () -> current.critical(inner -> inner));
                            assertEquals(12L, ((Number) outer.read("count")).longValue());
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
                        current.retireFromRuntime(ctx.self().id());
                        assertThrows(IllegalStateException.class,
                                () -> current.critical(s -> s.read("count")));
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
            assertThrows(IllegalStateException.class, () -> state.get().critical(s -> s.read("count")));
        }
    }
}
