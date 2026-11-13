package dev.oreslang.runtime;

import java.util.concurrent.TimeUnit;
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
