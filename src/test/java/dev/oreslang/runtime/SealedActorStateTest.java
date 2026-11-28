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

    @Test void ownedByteMoveInvalidatesSourceAndRetirementRevokesAllocations() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var completed = new CountDownLatch(1);
        var escaped = new AtomicReference<SealedActorState.OwnedBytes>();
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(() -> (mail, context) -> {
                try {
                    var state = new SealedActorState(context.self().id(),
                            Map.of("blob", "owned:bytes", "count", "int"),
                            Map.of("count", 0L));
                    var fresh = state.allocateOwnedBytes(3);
                    fresh.write(0, 42);
                    fresh.write(1, 255);
                    assertEquals(3, fresh.length());
                    assertThrows(IllegalArgumentException.class,
                            () -> fresh.write(0, 256));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.allocateOwnedBytes(64 * 1024 + 1));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.critical(true, access -> {
                                access.takeOwnedBytes("count", fresh);
                                return null;
                            }));
                    assertEquals(42, fresh.read(0)); // failed transfer is non-consuming
                    state.critical(false, read -> {
                        assertThrows(SecurityException.class, () -> fresh.write(0, 8));
                        assertThrows(SecurityException.class, () -> state.allocateOwnedBytes(1));
                        return null;
                    });
                    assertEquals(42, fresh.read(0));
                    assertThrows(SecurityException.class,
                            () -> state.critical(false, access -> {
                                access.takeOwnedBytes("blob", fresh);
                                return null;
                            }));
                    var other = new SealedActorState(context.self().id(),
                            Map.of("blob", "owned:bytes"), Map.of());
                    var foreignIssuer = other.allocateOwnedBytes(1);
                    assertThrows(SecurityException.class,
                            () -> state.critical(true, access -> {
                                access.takeOwnedBytes("blob", foreignIssuer);
                                return null;
                            }));
                    assertEquals(1, foreignIssuer.length());
                    other.retireFromRuntime(context.self().id());

                    state.critical(true, access -> {
                        assertThrows(IllegalStateException.class,
                                () -> state.retireFromRuntime(context.self().id()));
                        access.takeOwnedBytes("blob", fresh);
                        assertEquals(42, access.readOwnedByte("blob", 0));
                        assertEquals(255, access.readOwnedByte("blob", 1));
                        assertThrows(SecurityException.class, () -> access.read("blob"));
                        assertThrows(IllegalStateException.class, () -> fresh.read(0));
                        assertThrows(IllegalStateException.class,
                                () -> access.takeOwnedBytes("blob", fresh));
                        access.writeOwnedByte("blob", 2, 99);
                        return null;
                    });
                    int observed = state.critical(false,
                            access -> access.readOwnedByte("blob", 2));
                    assertEquals(99, observed);
                    assertThrows(SecurityException.class,
                            () -> state.critical(false, access -> {
                                access.writeOwnedByte("blob", 0, 11);
                                return null;
                            }));
                    var replacement = state.allocateOwnedBytes(2);
                    replacement.write(0, 7);
                    state.critical(true, access -> {
                        access.takeOwnedBytes("blob", replacement);
                        assertEquals(7, access.readOwnedByte("blob", 0));
                        assertThrows(IndexOutOfBoundsException.class,
                                () -> access.readOwnedByte("blob", 2));
                        return null;
                    });
                    assertThrows(IllegalStateException.class, () -> replacement.length());

                    var untransferred = state.allocateOwnedBytes(2);
                    untransferred.write(0, 17);
                    escaped.set(untransferred);
                    assertThrows(IllegalArgumentException.class,
                            () -> state.critical(true, access -> fresh));
                    state.retireFromRuntime(context.self().id());
                    assertThrows(IllegalStateException.class, () -> untransferred.read(0));
                    assertThrows(IllegalStateException.class,
                            () -> state.allocateOwnedBytes(1));
                    completed.countDown();
                } catch (Throwable thrown) {
                    failure.set(thrown);
                    completed.countDown();
                }
            });
            actor.send(1);
            assertTrue(completed.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
            assertThrows(IllegalStateException.class, () -> escaped.get().read(0));
        }
    }

}
