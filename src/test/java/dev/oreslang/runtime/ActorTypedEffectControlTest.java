package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real actor turns and lifecycle CONTROL handoff, not just a catalog unit test.
 * The guest source compiler is exercised separately by effect-admission tests.
 */
@Timeout(15)
final class ActorTypedEffectControlTest {
    @Test void raiseEmitsOneImmutableValueOnlySupervisorNotice() throws Exception {
        try (ActorRuntime actors = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> actor = actors.spawnShared(
                    () -> (message, context) -> {
                        throw GuestActorEffectSignal.raise();
                    });
            actor.send("failure");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().orElseThrow() instanceof GuestActorEffectSignal);

            ActorRuntime.ActorFailureNotice notice =
                    actors.pollActorFailureNotice().orElseThrow();
            assertEquals(actor.id(), notice.actorId());
            assertNull(notice.supervisorId());
            assertEquals(GuestActorEffectSignal.Kind.RAISE, notice.effect());
            assertEquals(ActorErrorCatalog.Kind.ACTOR_SUPERVISION,
                    notice.envelope().kind());
            assertEquals("actor raised a typed Error", notice.envelope().diagnostic());
            assertEquals(actor.id().value().toString(), notice.envelope().actorId());
            assertEquals("", notice.envelope().supervisorId());
            assertTrue(actors.pollActorFailureNotice().isEmpty());
            assertEquals(0, actors.actorFailureNoticesDropped());
        }
    }

    @Test void panicCannotBeDowngradedAndIsDistinctFromRaise() throws Exception {
        try (ActorRuntime actors = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> actor = actors.spawnShared(
                    () -> (message, context) -> {
                        throw GuestActorEffectSignal.panic();
                    });
            actor.send("failure");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            ActorRuntime.ActorFailureNotice notice =
                    actors.pollActorFailureNotice().orElseThrow();
            assertEquals(GuestActorEffectSignal.Kind.PANIC, notice.effect());
            assertEquals(1009, notice.envelope().code());
            assertFalse(notice.envelope().diagnostic().contains("failure"));
            assertEquals("actor panicked with a typed Error",
                    notice.envelope().diagnostic());
        }
    }

    @Test void cancellationAndOrdinaryHostErrorsDoNotSpoofGuestEffects()
            throws Exception {
        try (ActorRuntime actors = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> ordinary = actors.spawnShared(
                    () -> (message, context) -> {
                        throw new IllegalStateException(
                                "secret host exception text");
                    });
            ordinary.send("failure");
            assertTrue(ordinary.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actors.pollActorFailureNotice().isEmpty());

            ActorRuntime.ActorRef<String> cancelled = actors.spawnShared(
                    () -> (message, context) -> { });
            assertTrue(cancelled.cancel());
            assertTrue(cancelled.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actors.pollActorFailureNotice().isEmpty());
        }
    }

    @Test void guestActorSignalsHaveNoMutablePayloadOrHostCause() {
        for (GuestActorEffectSignal signal : new GuestActorEffectSignal[] {
                GuestActorEffectSignal.raise(), GuestActorEffectSignal.panic()
        }) {
            assertNull(signal.getCause());
            assertEquals(0, signal.getSuppressed().length);
            assertEquals(0, signal.getStackTrace().length);
            assertFalse(signal.getMessage().contains("secret"));
        }
    }
}
