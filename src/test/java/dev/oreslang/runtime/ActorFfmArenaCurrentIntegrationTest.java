package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Current-integration proof for deterministic actor-owned FFM data. */
@Timeout(20)
final class ActorFfmArenaCurrentIntegrationTest {
    @Test
    void persistentArenaSurvivesTurnsAndLoanPinsPhysicalRetirement() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");

        var arenaRef = new AtomicReference<ActorArena>();
        var sliceRef = new AtomicReference<ActorRuntime.ActorMemorySlice>();
        var persistentRef = new AtomicReference<ExplicitNativeI64Region.Slot>();
        var loanRef = new AtomicReference<ExplicitNativeI64Region.Loan>();
        var scratchRef = new AtomicReference<ExplicitNativeI64Region.Slot>();
        var firstDone = new CountDownLatch(1);
        var secondDone = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                var memory = turn.localMemory().orElseThrow();
                sliceRef.compareAndSet(null, memory);

                if ("first".equals(message)) {
                    var arena = memory.newActorArena(2, 2);
                    arenaRef.set(arena);
                    persistentRef.set(arena.allocatePersistent(40L));
                    try (var scratch = arena.beginTurn()) {
                        var ephemeral = scratch.allocate(7L);
                        assertEquals(7L, scratch.read(ephemeral));
                        scratchRef.set(ephemeral);
                    }
                    assertEquals(32L, memory.usedBytes(),
                            "two 2-slot FFM regions must remain charged while actor lives");
                    firstDone.countDown();
                    return;
                }

                var arena = arenaRef.get();
                assertSame(memory, sliceRef.get());
                assertEquals(40L, arena.readPersistent(persistentRef.get()),
                        "persistent FFM state must survive a mailbox/carrier boundary");
                try (var scratch = arena.beginTurn()) {
                    assertThrows(IllegalStateException.class,
                            () -> scratch.read(scratchRef.get()),
                            "prior-turn scratch handle must be generation-stale");
                    var current = scratch.allocate(2L);
                    assertEquals(2L, scratch.read(current));
                }
                arena.writePersistent(persistentRef.get(), 42L);
                assertEquals(42L, arena.readPersistent(persistentRef.get()));
                loanRef.set(arena.borrowPersistent(persistentRef.get()));
                secondDone.countDown();
                turn.self().stop();
            });

            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("first");
            assertTrue(firstDone.await(5, TimeUnit.SECONDS));
            actor.send("second");
            assertTrue(secondDone.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty(), () -> "actor failed: " + actor.failure());

            assertTrue(arenaRef.get().closed(), "logical retirement must revoke arena");
            assertFalse(arenaRef.get().physicallyClosed(),
                    "persistent loan must pin only its native FFM region");
            assertEquals(16L, sliceRef.get().usedBytes(),
                    "scratch FFM bytes must be physically released at actor retirement");
            assertThrows(IllegalStateException.class, loanRef.get()::read,
                    "late completion lease must not dereference retired actor memory");

            loanRef.get().close();
            loanRef.get().close();
            assertTrue(arenaRef.get().physicallyClosed());
            assertEquals(0L, sliceRef.get().usedBytes(),
                    "last native loan must refund physical bytes exactly once");
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }

    @Test
    void scratchScopeCannotEscapeMailboxBoundary() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable(), "Java 25 FFM required");

        var arenaRef = new AtomicReference<ActorArena>();
        var sliceRef = new AtomicReference<ActorRuntime.ActorMemorySlice>();
        var ran = new CountDownLatch(1);

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (message, turn) -> {
                var memory = turn.localMemory().orElseThrow();
                sliceRef.set(memory);
                var arena = memory.newActorArena(1, 1);
                arenaRef.set(arena);
                var scratch = arena.beginTurn();
                scratch.allocate(99L);
                // Deliberately omit scratch.close(); runtime boundary must fail-stop.
                ran.countDown();
            });

            actor.ready().get(5, TimeUnit.SECONDS);
            actor.send("escape");
            assertTrue(ran.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            var failure = actor.failure().orElseThrow();
            assertTrue(failure.toString().contains("scratch arena scope escaped"),
                    () -> "unexpected actor failure: " + failure);
            assertTrue(arenaRef.get().closed());
            assertTrue(arenaRef.get().physicallyClosed());
            assertEquals(0L, sliceRef.get().usedBytes());
            assertEquals(0L, runtime.sharedActorLocalMemoryBytes());
        }
    }
}
