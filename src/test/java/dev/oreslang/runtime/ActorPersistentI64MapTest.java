package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class ActorPersistentI64MapTest {
    @Test
    void concurrentMessagesReuseOneArenaBackedFieldWithScopedCriticalAccess() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        final int messages = 200;
        var memory = new AtomicReference<ActorArena>();
        var map = new AtomicReference<ActorPersistentI64Map>();
        var escaped = new AtomicReference<ActorPersistentI64Map.Access>();
        var error = new AtomicReference<Throwable>();
        var done = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var worker = runtime.<Integer>spawnShared(() -> (mail, ctx) -> {
                try {
                    if (map.get() == null) {
                        var a = ctx.actorOwnedMemory().orElseThrow().newActorArena(3 * 16, 2);
                        memory.set(a);
                        map.set(a.newPersistentI64Map(16));
                    }
                    var current = map.get();
                    if (mail == -1) {
                        current.critical(view -> {
                            assertEquals(messages, view.getOrDefault(0L, -1L));
                            assertEquals(messages, view.getOrDefault(42L, -1L));
                            assertEquals(2, view.size());
                            assertThrows(IllegalStateException.class, escaped.get()::size);
                            assertEquals(2, current.critical(nested -> {
                                assertThrows(IllegalStateException.class, view::size,
                                        "outer access must be fenced during nested callback");
                                assertEquals(2, nested.size());
                                return nested.size();
                            }));
                            assertEquals(2, view.size(),
                                    "outer access restored after nested callback");
                            return null;
                        });
                        assertThrows(IllegalStateException.class, escaped.get()::size);
                        done.countDown();
                    } else {
                        current.critical(view -> {
                            long next = view.getOrDefault(0L, 0L) + 1;
                            view.put(0L, next);
                            view.put(42L, next);
                            escaped.set(view);
                            return null;
                        });
                    }
                } catch (Throwable problem) {
                    error.compareAndSet(null, problem);
                    done.countDown();
                }
            });
            for (int i = 0; i < messages; i++) worker.send(i);
            worker.send(-1);
            assertTrue(done.await(20, TimeUnit.SECONDS));
            if (error.get() != null) fail(error.get());
            assertThrows(IllegalStateException.class, () -> map.get().critical(view -> view.size()),
                    "host/carrier without actor authority must not access persistent state");
        }
        assertTrue(memory.get().closed());
        assertTrue(memory.get().physicallyClosed());
        assertTrue(map.get().closed());
    }

    @Test
    void tombstonesRollbackAndFailureReleaseAndZeroizeState() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        var arena = new AtomicReference<ActorArena>();
        var problem = new AtomicReference<Throwable>();
        var done = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var worker = runtime.<String>spawnPrivate(() -> (mail, ctx) -> {
                try {
                    ActorArena a = ctx.actorOwnedMemory().orElseThrow().newActorArena(3 * 8, 1);
                    arena.set(a);
                    try (var state = a.newPersistentI64Map(8)) {
                        state.critical(view -> {
                            view.put(0L, 100L);
                            view.put(Long.MIN_VALUE, 200L);
                            view.put(Long.MAX_VALUE, 300L);
                            assertTrue(view.remove(Long.MIN_VALUE));
                            assertFalse(view.remove(Long.MIN_VALUE));
                            view.put(Long.MIN_VALUE, 400L);
                            assertEquals(400L, view.getOrDefault(Long.MIN_VALUE, -1L));
                            assertEquals(3, view.size());
                            view.clear();
                            assertEquals(0, view.size());
                            assertFalse(view.containsKey(0L));
                            return null;
                        });
                        assertThrows(IllegalArgumentException.class, () -> state.critical(view -> {
                            throw new IllegalArgumentException("callback failed");
                        }));
                        state.critical(view -> {
                            view.put(0L, 42L);
                            assertEquals(42L, view.getOrDefault(0L, 0L));
                            return null;
                        });
                    }
                    assertThrows(IllegalStateException.class,
                            () -> a.newPersistentI64Map(9),
                            "insufficient native slots must rollback all partially allocated slots");
                    try (var again = a.newPersistentI64Map(8)) {
                        again.critical(view -> {
                            assertEquals(0L, view.getOrDefault(0L, 0L));
                            return null;
                        });
                    }
                    a.close();
                    done.countDown();
                } catch (Throwable failure) {
                    problem.compareAndSet(null, failure);
                    done.countDown();
                }
            });
            worker.send("check");
            assertTrue(done.await(20, TimeUnit.SECONDS));
            if (problem.get() != null) fail(problem.get());
        }
        assertTrue(arena.get().physicallyClosed());
    }
}
