package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host-runtime evidence only. Actual .atto compiler-backed actor failure,
 * continuation frames, supervisor restarts and native low-stack survival
 * still require their own receipts.
 */
final class ActorGuestStackTest {
    @Test
    void logicalStacksAreIndependentAndAnExhaustedIncarnationNeverRecovers() {
        ActorGuestStack a = new ActorGuestStack();
        ActorGuestStack b = new ActorGuestStack();
        for (int depth = 1; depth <= GuestCallDepthGuard.MAX_SYNCHRONOUS_GUEST_CALLS; depth++) {
            assertEquals(depth, a.reserveFrame());
        }
        assertThrows(GuestCallDepthExceededException.class, a::reserveFrame);
        assertTrue(a.isPoisoned());
        assertEquals(GuestCallDepthGuard.MAX_SYNCHRONOUS_GUEST_CALLS, a.highWaterFrames());
        for (int depth = GuestCallDepthGuard.MAX_SYNCHRONOUS_GUEST_CALLS; depth >= 1; depth--) {
            a.releaseFrame(depth);
        }
        assertEquals(0, a.activeFrames());
        assertThrows(GuestCallDepthExceededException.class, a::reserveFrame,
                "a failed actor generation must not regain guest admission");
        assertEquals(1, b.reserveFrame(), "another actor has an independent budget");
        b.releaseFrame(1);
        assertFalse(b.isPoisoned());
    }

    @Test
    void invalidStackUnwindFailsClosedWithoutAffectingAnotherActor() {
        ActorGuestStack bad = new ActorGuestStack();
        ActorGuestStack good = new ActorGuestStack();
        bad.reserveFrame();
        assertThrows(IllegalStateException.class, () -> bad.releaseFrame(2));
        assertTrue(bad.isPoisoned());
        assertThrows(GuestCallDepthExceededException.class, bad::reserveFrame);
        assertEquals(1, good.reserveFrame());
        good.releaseFrame(1);
    }

    @Test
    void actorOwnedBudgetSurvivesAnotherMailboxTurnOnSameActor() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<ActorGuestStack> first = new AtomicReference<>();
            AtomicInteger seen = new AtomicInteger();
            var actor = runtime.<String>spawnShared(factory -> (message, turn) -> {
                ActorGuestStack current = ActorRuntime.currentActorGuestStack();
                assertNotNull(current);
                if (first.get() == null) first.set(current);
                assertSame(first.get(), current);
                int depth = GuestCallDepthGuard.enter();
                try {
                    assertEquals(1, depth);
                } finally {
                    GuestCallDepthGuard.leave(depth);
                }
                if (seen.incrementAndGet() == 2) turn.self().stop();
            });
            actor.send("one");
            actor.send("two");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
            assertEquals(2, seen.get());
            assertEquals(0, first.get().activeFrames());
            assertEquals(1, first.get().highWaterFrames());
        }
    }

    @Test
    void swallowedBudgetExceptionStillFailsActorAndDoesNotPoisonSiblingCarrier() throws Exception {
        ActorRuntime.DispatcherConfig config = new ActorRuntime.DispatcherConfig(1, 1, 8);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            AtomicReference<ActorGuestStack> failedStack = new AtomicReference<>();
            AtomicReference<ActorGuestStack> healthyStack = new AtomicReference<>();
            AtomicInteger siblingCalls = new AtomicInteger();

            var exhausted = runtime.<String>spawnShared(factory -> (message, turn) -> {
                failedStack.set(ActorRuntime.currentActorGuestStack());
                int depth = 0;
                try {
                    for (int i = 0; i < GuestCallDepthGuard.MAX_SYNCHRONOUS_GUEST_CALLS; i++) {
                        depth = GuestCallDepthGuard.enter();
                    }
                    // Deliberately swallow the exception in host behavior.
                    // ActorRuntime must still fail-stop this incarnation.
                    assertThrows(GuestCallDepthExceededException.class,
                            GuestCallDepthGuard::enter);
                } finally {
                    while (depth > 0) GuestCallDepthGuard.leave(depth--);
                }
            });
            var sibling = runtime.<String>spawnShared(factory -> (message, turn) -> {
                healthyStack.set(ActorRuntime.currentActorGuestStack());
                int depth = GuestCallDepthGuard.enter();
                try {
                    assertEquals(1, depth, "the reused worker has a fresh carrier scope");
                    siblingCalls.incrementAndGet();
                } finally {
                    GuestCallDepthGuard.leave(depth);
                    turn.self().stop();
                }
            });

            exhausted.send("exhaust");
            assertTrue(exhausted.awaitTermination(3, TimeUnit.SECONDS));
            assertInstanceOf(GuestCallDepthExceededException.class,
                    exhausted.failure().orElseThrow());
            assertEquals(0, failedStack.get().activeFrames());
            assertTrue(failedStack.get().isPoisoned());

            sibling.send("alive");
            assertTrue(sibling.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(sibling.failure().isEmpty());
            assertEquals(1, siblingCalls.get());
            assertNotSame(failedStack.get(), healthyStack.get(),
                    "separate actor incarnations must never share a stack");
            assertFalse(healthyStack.get().isPoisoned());
        }
    }
}
