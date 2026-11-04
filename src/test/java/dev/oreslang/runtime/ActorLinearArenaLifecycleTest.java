package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorLinearArenaLifecycleTest {
    @Test
    void privateActorArenaIsOwnerCheckedAndDetachedAtActorFinalization() throws Exception {
        var retained = new AtomicReference<ActorRuntime.PrivateLinearArena>();
        var entered = new CountDownLatch(1);

        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnPrivate(() -> (message, context) -> {
                var slice = context.privateMemory().orElseThrow();
                var arena = slice.allocateLinearArena(1024, 16);
                assertTrue(slice.usedBytes() >= 1024);

                for (int i = 0; i < 1_000; i++) {
                    long mark = arena.cleanupMark();
                    long handle = arena.allocateOwned(16, 8);
                    assertNotEquals(ActorLinearArena.INVALID_HANDLE, handle);
                    assertTrue(arena.putLong(handle, 0, i));
                    assertEquals(i, arena.getLong(handle, 0));
                    assertTrue(arena.cleanupTo(mark),
                            "compiler-style lexical cleanup must reclaim the owner");
                }
                assertEquals(0, arena.liveAllocations());
                assertEquals(0, arena.usedBytes());
                retained.set(arena);
                entered.countDown();
                context.self().stop();
            });

            actor.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));

            var stale = retained.get();
            assertNotNull(stale);
            assertTrue(stale.closed(),
                    "actor memory-slice finalization must close and detach the direct arena");
            assertThrows(IllegalStateException.class, () -> stale.allocate(8),
                    "retained host wrapper must not keep arena authority alive");
        }
    }

    @Test
    void manualArenaCloseRefundsActorQuotaWithoutWaitingForGc() throws Exception {
        var completed = new CountDownLatch(1);
        var observed = new AtomicReference<Throwable>();

        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnPrivate(() -> (message, context) -> {
                try {
                    var slice = context.privateMemory().orElseThrow();
                    long before = slice.usedBytes();
                    var arena = slice.allocateLinearArena(2048, 8);
                    assertEquals(before + 2048 + 8L * ActorLinearArena.METADATA_BYTES_PER_SLOT, slice.usedBytes());
                    arena.close();
                    assertEquals(before, slice.usedBytes(),
                            "deterministic close must refund logical actor heap quota");
                    assertTrue(arena.closed());
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    completed.countDown();
                    context.self().stop();
                }
            });

            actor.send(1);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
            assertNull(observed.get());
        }
    }
    @Test
    void privateActorTurnCleansNestedOwnersAfterOutOfOrderTransfer() throws Exception {
        var done = new CountDownLatch(1);
        var observed = new AtomicReference<Throwable>();

        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnPrivate(() -> (message, context) -> {
                try {
                    var slice = context.privateMemory().orElseThrow();
                    var arena = slice.allocateLinearArena(512, 8);
                    long outer = arena.cleanupMark();
                    long persistent = arena.allocateOwned(16);
                    assertNotEquals(0L, persistent);
                    assertTrue(arena.putLong(persistent, 0, 81L));
                    long nested = arena.cleanupMark();
                    long temporary = arena.allocateOwned(16);
                    assertNotEquals(0L, temporary);
                    assertTrue(arena.releaseOwned(persistent));
                    assertTrue(arena.cleanupTo(nested));
                    assertThrows(IllegalStateException.class, () -> arena.getLong(temporary, 0));
                    assertEquals(81L, arena.getLong(persistent, 0));
                    assertTrue(arena.adoptOwned(persistent));
                    assertTrue(arena.cleanupTo(outer));
                    assertEquals(0, arena.liveAllocations());
                    assertEquals(0, arena.usedBytes());
                } catch (Throwable failure) {
                    observed.set(failure);
                } finally {
                    done.countDown();
                    context.self().stop();
                }
            });

            actor.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
            assertNull(observed.get(), "nested actor cleanup should finish without escaped owners");
        }
    }

    @Test
    void slotMetadataCannotEscapeTheActorQuota() throws Exception {
        var done = new CountDownLatch(1);
        var observed = new AtomicReference<Throwable>();
        try (var runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnPrivate(() -> (message, context) -> {
                try {
                    var slice = context.privateMemory().orElseThrow();
                    long before = slice.usedBytes();
                    try (var arena = slice.allocateLinearArena(1, 65_535)) {
                        assertEquals(before + 1L + 65_535L * ActorLinearArena.METADATA_BYTES_PER_SLOT,
                                slice.usedBytes(), "one byte of payload must not hide 3 MiB of metadata");
                    }
                    assertEquals(before, slice.usedBytes(), "close must refund the metadata charge");
                } catch (Throwable problem) {
                    observed.set(problem);
                } finally {
                    done.countDown();
                    context.self().stop();
                }
            });
            actor.send(1);
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(10, TimeUnit.SECONDS));
            assertNull(observed.get());
        }
    }
}
