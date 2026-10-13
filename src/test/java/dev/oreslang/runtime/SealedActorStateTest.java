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
    @Test void flatIntegerListsAreDetachedAtEveryActorStateBoundary() throws Exception {
        var failure = new AtomicReference<Throwable>();
        var finished = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var worker = runtime.<String>spawnShared(() -> (mail, ctx) -> {
                try {
                    var slice = ctx.actorOwnedMemory().orElseThrow();
                    long usedBeforeState = slice.usedBytes();
                    var source = new java.util.ArrayList<Long>(java.util.List.of(1L, 2L));
                    var state = new SealedActorState(ctx.self().id(),
                            Map.of("items", "List[int]"), Map.of("items", source));
                    assertEquals(usedBeforeState + 2L * Long.BYTES,
                            slice.usedBytes(), "persisted int list must consume actor quota");
                    source.set(0, 800L); // Constructor must not retain host list.
                    @SuppressWarnings("unchecked")
                    var detached = (java.util.ArrayList<Long>) state.critical(
                            false, access -> access.read("items"));
                    assertEquals(java.util.List.of(1L, 2L), detached);
                    detached.set(0, 900L);
                    assertEquals(java.util.List.of(1L, 2L),
                            state.critical(false, access -> access.read("items")));

                    var replacement = new java.util.ArrayList<Long>(
                            java.util.List.of(3L, 4L));
                    state.critical(true, access -> {
                        access.write("items", replacement);
                        return null;
                    });
                    replacement.set(0, 999L);
                    assertEquals(java.util.List.of(3L, 4L),
                            state.critical(false, access -> access.read("items")));
                    assertEquals(900L, detached.getFirst());
                    long committedCharge = slice.usedBytes();
                    assertThrows(IllegalStateException.class,
                            () -> state.critical(true, access -> {
                                access.write("items", new java.util.ArrayList<>(
                                        java.util.List.of(700L)));
                                throw new IllegalStateException("fail after write");
                            }));
                    assertEquals(java.util.List.of(3L, 4L),
                            state.critical(false, access -> access.read("items")));
                    // A failed growth writes no value and retains no heap charge.
                    assertThrows(IllegalStateException.class,
                            () -> state.critical(true, access -> {
                                access.write("items", new java.util.ArrayList<>(
                                        java.util.List.of(3L, 4L, 5L, 6L)));
                                throw new IllegalStateException("rollback growth");
                            }));
                    assertEquals(committedCharge, slice.usedBytes(),
                            "failed critical write must refund its tentative charge");
                    assertEquals(java.util.List.of(3L, 4L),
                            state.critical(false, access -> access.read("items")));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.critical(true, access -> {
                                access.write("items", new java.util.ArrayList<>(
                                        java.util.List.of(800L)));
                                return new Object(); // invalid returned reference
                            }));
                    assertEquals(java.util.List.of(3L, 4L),
                            state.critical(false, access -> access.read("items")));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.critical(false, access ->
                                    new java.util.ArrayList<>(java.util.List.of("not int"))));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.critical(true, access -> {
                                access.write("items", new java.util.ArrayList<>(
                                        java.util.List.of(java.util.List.of(9L))));
                                return null;
                            }));
                    assertEquals(java.util.List.of(3L, 4L),
                            state.critical(false, access -> access.read("items")));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.critical(false, access ->
                                    new java.util.ArrayList<>(java.util.Collections.nCopies(
                                            4097, 1L))));
                    assertThrows(IllegalArgumentException.class,
                            () -> state.critical(false, access ->
                                    new java.util.LinkedList<>(java.util.List.of(1L))));
                    // Successful growth is quota-charged even if later
                    // writes shrink the retained list: the charge is a
                    // deliberate high-water bound until actor retirement.
                    state.critical(true, access -> {
                        access.write("items", new java.util.ArrayList<>(
                                java.util.List.of(3L, 4L, 5L)));
                        return null;
                    });
                    assertEquals(committedCharge + Long.BYTES, slice.usedBytes());
                    state.critical(true, access -> {
                        access.write("items", new java.util.ArrayList<>(
                                java.util.List.of(3L)));
                        return null;
                    });
                    assertEquals(committedCharge + Long.BYTES, slice.usedBytes());
                    state.retireFromRuntime(ctx.self().id());
                    assertEquals(usedBeforeState, slice.usedBytes(),
                            "retired actor state must return every reservation");
                    finished.countDown();
                } catch (Throwable error) {
                    failure.set(error);
                    finished.countDown();
                }
            });
            worker.send("verify");
            assertTrue(finished.await(15, TimeUnit.SECONDS));
            if (failure.get() != null) fail(failure.get());
        }
    }

}
