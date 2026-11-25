package dev.oreslang.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real ActorRuntime host actor-cell proof for logical M1 frames. Behavior
 * factories intentionally capture NO external mutable host state: actor
 * isolation admission must not be disabled to make the test pass.
 *
 * Not a source-compiled .atto guest, native StackOverflow recovery, or
 * supervisor epoch/quiescence proof.
 */
final class ActorGuestStackCurrentRuntimeTest {
    @Test
    void independentStacksPoisonOnlyTheirOwnIncarnation() {
        ActorGuestStack exhausted = new ActorGuestStack();
        ActorGuestStack healthy = new ActorGuestStack();
        for (int depth = 1; depth <= GuestCallDepthGuard.MAX_SYNCHRONOUS_GUEST_CALLS; depth++) {
            assertEquals(depth, exhausted.reserveFrame());
        }
        assertThrows(GuestCallDepthExceededException.class, exhausted::reserveFrame);
        assertTrue(exhausted.isPoisoned());
        assertEquals(64, exhausted.highWaterFrames());
        for (int depth = 64; depth >= 1; depth--) exhausted.releaseFrame(depth);
        assertEquals(0, exhausted.activeFrames());
        assertThrows(GuestCallDepthExceededException.class, exhausted::reserveFrame);
        assertEquals(1, healthy.reserveFrame());
        healthy.releaseFrame(1);
        assertFalse(healthy.isPoisoned());
    }

    @Test
    void invalidUnwindPoisonsOnlyOwner() {
        ActorGuestStack exhausted = new ActorGuestStack();
        ActorGuestStack healthy = new ActorGuestStack();
        exhausted.reserveFrame();
        assertThrows(IllegalStateException.class, () -> exhausted.releaseFrame(2));
        assertTrue(exhausted.isPoisoned());
        assertThrows(GuestCallDepthExceededException.class, exhausted::reserveFrame);
        assertEquals(1, healthy.reserveFrame());
        healthy.releaseFrame(1);
    }

    @Test
    void scopeFreeInvalidUnwindFailStopsOnlyCurrentActor() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var doomed = runtime.<String>spawnShared(factory -> (message, turn) -> {
                ActorGuestStack owner = ActorRuntime.currentActorGuestStack();
                assertNotNull(owner);
                assertEquals(0, owner.activeFrames());
                assertThrows(IllegalStateException.class,
                        () -> GuestCallDepthGuard.leave(1));
                // Even after the trusted callback swallows the exception,
                // the runtime must fail the actor at the mailbox boundary.
                assertTrue(owner.isPoisoned());
            });
            var sibling = runtime.<String>spawnShared(factory -> (message, turn) -> {
                assertEquals("alive", message);
                ActorGuestStack owner = ActorRuntime.currentActorGuestStack();
                assertNotNull(owner);
                assertFalse(owner.isPoisoned());
                int depth = GuestCallDepthGuard.enter();
                try {
                    assertEquals(1, depth);
                } finally {
                    GuestCallDepthGuard.leave(depth);
                }
                turn.self().stop();
            });
            doomed.send("bad-unwind");
            assertTrue(doomed.awaitTermination(3, TimeUnit.SECONDS));
            assertInstanceOf(GuestCallDepthExceededException.class,
                    doomed.failure().orElseThrow(),
                    "swallowed invalid unwind must poison the actor, not merely fail a test assertion");
            sibling.send("alive");
            assertTrue(sibling.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(sibling.failure().isEmpty(),
                    "the next actor on the same SHARED carrier must survive");
        }
    }

    @Test
    void hostSupervisorReplacementFencesStaleGenerationAndPreservesSiblingAndControl()
            throws Exception {
        // This is host-triggered replacement under one supervisor, NOT a
        // source-level automatic restart policy or native JVM SOE recovery.
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ExecutorService control = Executors.newSingleThreadExecutor(
                    runnable -> new Thread(runnable, "actor-stack-test-control"))) {
            ActorRuntime.ControlDispatcher controlDispatcher =
                    turn -> OresFuture.fromCallback(done -> control.execute(() -> {
                        try {
                            turn.run();
                            done.resolve(null);
                        } catch (Throwable failed) {
                            done.reject(failed);
                        }
                    }));
            try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(),
                    config, ActorRuntime.TurnExecutor.direct(), controlDispatcher)) {
                var supervisor = runtime.createSupervisor();
                var group = runtime.createActorGroup(supervisor);
                assertEquals(supervisor.id(), group.supervisorId());

                // Failure A: a stateless actor behavior catches the controlled
                // budget error, but the runtime must still fail-stop that cell.
                var exhausted = supervisor.<String>startChildHost(
                        group, ActorRuntime.ActorKind.SHARED,
                        IsolatePolicy.developer(), factory -> (message, turn) -> {
                            assertEquals("exhaust", message);
                            ActorGuestStack owner = ActorRuntime.currentActorGuestStack();
                            assertNotNull(owner);
                            assertEquals(0, owner.highWaterFrames());
                            int depth = 0;
                            try {
                                for (int i = 0;
                                        i < GuestCallDepthGuard.MAX_SYNCHRONOUS_GUEST_CALLS;
                                        i++) {
                                    depth = GuestCallDepthGuard.enter();
                                }
                                assertThrows(GuestCallDepthExceededException.class,
                                        GuestCallDepthGuard::enter);
                            } finally {
                                while (depth > 0) GuestCallDepthGuard.leave(depth--);
                            }
                            assertTrue(owner.isPoisoned());
                        });
                var sibling = supervisor.<String>startChildHost(
                        group, ActorRuntime.ActorKind.SHARED,
                        IsolatePolicy.developer(), factory -> (message, turn) -> {
                            assertEquals("alive", message);
                            ActorGuestStack owner = ActorRuntime.currentActorGuestStack();
                            assertNotNull(owner);
                            assertFalse(owner.isPoisoned());
                            int depth = GuestCallDepthGuard.enter();
                            try {
                                assertEquals(1, depth);
                            } finally {
                                GuestCallDepthGuard.leave(depth);
                            }
                            turn.self().stop();
                        });

                exhausted.send("exhaust");
                assertTrue(exhausted.awaitTermination(5, TimeUnit.SECONDS));
                assertInstanceOf(GuestCallDepthExceededException.class,
                        exhausted.failure().orElseThrow());
                assertThrows(ActorRuntime.ActorTerminatedException.class,
                        () -> exhausted.send("stale-old-incarnation"),
                        "stale handles cannot revive a finalized actor");
                assertThrows(IllegalArgumentException.class,
                        () -> runtime.controlHandle(exhausted.id()),
                        "a finalized old actor must not confer new lifecycle control");

                sibling.send("alive");
                assertTrue(sibling.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(sibling.failure().isEmpty(),
                        "the one-carrier SHARED pool must keep running after A");

                // A distinct injected CONTROL worker must still dispatch after
                // actor A failed. This is not proof of the real VM CONTROL lane.
                AtomicInteger controlTurns = new AtomicInteger();
                runtime.dispatchSupervisorObservationControl(() -> {
                    assertFalse(ActorRuntime.inActorExecution());
                    assertTrue(Thread.currentThread().getName()
                            .contains("actor-stack-test-control"));
                    controlTurns.incrementAndGet();
                }).get(5, TimeUnit.SECONDS);
                assertEquals(1, controlTurns.get());
                assertEquals(supervisor.id(), group.supervisorId());

                // Host/supervisor elects to start a NEW incarnation in the same
                // advisory domain; the old poisoned frame budget is not reused.
                var replacement = supervisor.<String>startChildHost(
                        group, ActorRuntime.ActorKind.SHARED,
                        IsolatePolicy.developer(), factory -> (message, turn) -> {
                            assertEquals("replacement", message);
                            ActorGuestStack owner = ActorRuntime.currentActorGuestStack();
                            assertNotNull(owner);
                            assertEquals(0, owner.highWaterFrames(),
                                    "new incarnation must not inherit poisoned depth");
                            assertEquals(0, owner.activeFrames());
                            assertFalse(owner.isPoisoned());
                            int depth = GuestCallDepthGuard.enter();
                            try {
                                assertEquals(1, depth);
                            } finally {
                                GuestCallDepthGuard.leave(depth);
                            }
                            turn.self().stop();
                        });
                assertNotEquals(exhausted.id(), replacement.id());
                assertTrue(replacement.generation() > exhausted.generation(),
                        "new actor incarnation must mint a strictly newer generation");
                assertNotEquals(exhausted.address(), replacement.address());
                assertThrows(ActorRuntime.ActorTerminatedException.class,
                        () -> exhausted.send("stale-after-replacement"));
                replacement.send("replacement");
                assertTrue(replacement.awaitTermination(5, TimeUnit.SECONDS));
                assertTrue(replacement.failure().isEmpty());
                assertEquals(0, group.workerCount(),
                        "both failed and healthy workers must leave the registry");
                assertEquals(0, runtime.actorCount(),
                        "the test must not leak live actor cells");
                group.close();
            }
        }
    }

    @Test
    void actorKeepsLogicalStackAcrossMailboxTurns() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                ActorGuestStack owner = ActorRuntime.currentActorGuestStack();
                assertNotNull(owner);
                assertFalse(owner.isPoisoned());
                if ("second".equals(message)) {
                    assertEquals(1, owner.highWaterFrames(),
                            "previous mailbox turn must have charged this same actor");
                } else {
                    assertEquals("first", message);
                    assertEquals(0, owner.highWaterFrames());
                }
                int depth = GuestCallDepthGuard.enter();
                try {
                    assertEquals(1, depth);
                } finally {
                    GuestCallDepthGuard.leave(depth);
                }
                if ("second".equals(message)) turn.self().stop();
            });
            actor.send("first");
            actor.send("second");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
        }
    }

    @Test
    void swallowedGuestBudgetFailureFailStopsActorAndSiblingSurvivesSameCarrier()
            throws Exception {
        var config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var exhausted = runtime.<String>spawnShared(factory -> (message, turn) -> {
                assertNotNull(ActorRuntime.currentActorGuestStack());
                int depth = 0;
                try {
                    for (int i = 0; i < GuestCallDepthGuard.MAX_SYNCHRONOUS_GUEST_CALLS; i++) {
                        depth = GuestCallDepthGuard.enter();
                    }
                    // Trusted host intentionally tries to suppress the failure.
                    assertThrows(GuestCallDepthExceededException.class,
                            GuestCallDepthGuard::enter);
                } finally {
                    while (depth > 0) GuestCallDepthGuard.leave(depth--);
                }
                // Runtime must still poison/fail-stop this actor after return.
            });
            var sibling = runtime.<String>spawnShared(factory -> (message, turn) -> {
                assertEquals("alive", message);
                ActorGuestStack owner = ActorRuntime.currentActorGuestStack();
                assertNotNull(owner);
                assertFalse(owner.isPoisoned(),
                        "a sibling must not inherit another actor's poisoned stack");
                int depth = GuestCallDepthGuard.enter();
                try {
                    assertEquals(1, depth,
                            "one-carrier pool must be clean after poisoned actor");
                } finally {
                    GuestCallDepthGuard.leave(depth);
                }
                turn.self().stop();
            });
            exhausted.send("exhaust");
            assertTrue(exhausted.awaitTermination(3, TimeUnit.SECONDS));
            assertInstanceOf(GuestCallDepthExceededException.class,
                    exhausted.failure().orElseThrow());
            sibling.send("alive");
            assertTrue(sibling.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(sibling.failure().isEmpty());
        }
    }
}
