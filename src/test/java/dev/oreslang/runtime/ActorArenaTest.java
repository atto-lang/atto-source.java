package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

final class ActorArenaTest {
    @Test
    void persistentStateSurvivesTurnsWhileScratchResetsAndStaleHandlesFail() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        var arena = new AtomicReference<ActorArena>();
        var durable = new AtomicReference<ExplicitNativeI64Region.Slot>();
        var stale = new AtomicReference<ExplicitNativeI64Region.Slot>();
        var oldTurn = new AtomicReference<ActorArena.ScratchTurn>();
        var loan = new AtomicReference<ExplicitNativeI64Region.Loan>();
        var error = new AtomicReference<Throwable>();
        var first = new CountDownLatch(1);
        var second = new CountDownLatch(1);
        var third = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var worker = runtime.<String>spawnPrivate(() -> (mail, ctx) -> {
                try {
                    if ("first".equals(mail)) {
                        var memory = ctx.actorOwnedMemory().orElseThrow();
                        var value = memory.newActorArena(2, 2);
                        arena.set(value);
                        durable.set(value.allocatePersistent(40));
                        long lastGeneration = 0;
                        for (int index = 0; index < 8192; index++) {
                            var temporary = value.allocatePersistent(index);
                            assertEquals(1, temporary.index());
                            assertTrue(temporary.generation() > lastGeneration);
                            assertEquals(index, value.readPersistent(temporary));
                            lastGeneration = temporary.generation();
                            value.dropPersistent(temporary);
                            assertThrows(IllegalStateException.class,
                                    () -> value.readPersistent(temporary));
                        }
                        loan.set(value.borrowPersistent(durable.get()));
                        try (var scope = value.beginTurn()) {
                            oldTurn.set(scope);
                            var slot = scope.allocate(2);
                            stale.set(slot);
                            assertEquals(2, scope.read(slot));
                            scope.write(slot, 3);
                            assertEquals(3, scope.read(slot));
                            assertThrows(IllegalStateException.class, value::beginTurn);
                        }
                        assertTrue(oldTurn.get().done());
                        first.countDown();
                    } else if ("second".equals(mail)) {
                        var value = arena.get();
                        assertEquals(40, value.readPersistent(durable.get()));
                        assertThrows(IllegalStateException.class,
                                () -> value.writePersistent(durable.get(), 41));
                        assertThrows(IllegalStateException.class,
                                () -> oldTurn.get().read(stale.get()));
                        try (var scope = value.beginTurn()) {
                            var reused = scope.allocate(0);
                            assertEquals(0, scope.read(reused));
                            assertThrows(IllegalStateException.class,
                                    () -> scope.read(stale.get()));
                        }
                        loan.get().close();
                        value.writePersistent(durable.get(), 41);
                        second.countDown();
                    } else {
                        assertEquals(41, arena.get().readPersistent(durable.get()));
                        try (var scope = arena.get().beginTurn()) {
                            assertEquals(0, scope.read(scope.allocate(0)));
                        }
                        arena.get().close();
                        assertTrue(arena.get().closed());
                        assertTrue(arena.get().physicallyClosed());
                        third.countDown();
                    }
                } catch (Throwable problem) {
                    error.compareAndSet(null, problem);
                    first.countDown(); second.countDown(); third.countDown();
                }
            });
            worker.send("first");
            assertTrue(first.await(20, TimeUnit.SECONDS));
            if (error.get() != null) fail(error.get());
            worker.send("second");
            assertTrue(second.await(20, TimeUnit.SECONDS));
            if (error.get() != null) fail(error.get());
            worker.send("third");
            assertTrue(third.await(20, TimeUnit.SECONDS));
            if (error.get() != null) fail(error.get());
        }
        assertTrue(arena.get().physicallyClosed());
    }

    @Test
    void leakedScratchScopeFailsTheActorAtItsMailboxBoundary() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        var arena = new AtomicReference<ActorArena>();
        var reached = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (mail, ctx) -> {
                ActorArena value = ctx.actorOwnedMemory().orElseThrow().newActorArena(2, 2);
                arena.set(value);
                value.beginTurn().allocate(99);
                reached.countDown();
                // Compiler/runtime cannot allow ephemeral native memory to
                // escape this message, even when guest forgets to close it.
            });
            actor.send("leak");
            assertTrue(reached.await(20, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(20, TimeUnit.SECONDS));
            var problem = actor.failure().orElseThrow();
            assertTrue(problem.toString().contains("scratch arena scope escaped"),
                    () -> "unexpected failure: " + problem);
        }
        assertTrue(arena.get().closed());
        assertTrue(arena.get().physicallyClosed());
    }

    @Test
    void actorTerminationRetainsNativeQuotaUntilLoanReleases() throws Exception {
        assumeTrue(ExplicitNativeI64Region.isAvailable());
        var arena = new AtomicReference<ActorArena>();
        var loan = new AtomicReference<ExplicitNativeI64Region.Loan>();
        var error = new AtomicReference<Throwable>();
        var created = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<String>spawnShared(() -> (mail, ctx) -> {
                try {
                    var instance = ctx.actorOwnedMemory().orElseThrow().newActorArena(2, 2);
                    arena.set(instance);
                    loan.set(instance.borrowPersistent(instance.allocatePersistent(42)));
                } catch (Throwable problem) {
                    error.compareAndSet(null, problem);
                } finally {
                    created.countDown();
                }
            });
            actor.send("start");
            assertTrue(created.await(20, TimeUnit.SECONDS));
            if (error.get() != null) fail(error.get());
        }
        assertTrue(arena.get().closed());
        assertFalse(arena.get().physicallyClosed());
        assertThrows(IllegalStateException.class, () -> loan.get().read());
        loan.get().close();
        assertTrue(arena.get().physicallyClosed());
    }
}
