package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
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

    @Test void terminationBarrierNeverOvertakesTypedControlPublication() throws Exception {
        try (ActorRuntime actors = new ActorRuntime()) {
            // Repeated independent actor lifecycles exercise the race between
            // finalizeStopLocked(), awaitTermination() and queue publication.
            for (int i = 0; i < 24; i++) {
                final int turn = i;
                ActorRuntime.ActorRef<String> actor = actors.spawnShared(
                        () -> (message, context) -> {
                            if ((turn & 1) == 0) {
                                throw GuestActorEffectSignal.raise();
                            }
                            throw GuestActorEffectSignal.panic();
                        });
                actor.send("terminate");
                assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS),
                        "actor lifecycle " + i + " did not finish");
                // No eventual polling/retry: after the termination barrier, the
                // notice must already be present and must belong to this actor.
                ActorRuntime.ActorFailureNotice notice =
                        actors.pollActorFailureNotice().orElseThrow();
                assertEquals(actor.id(), notice.actorId());
                assertEquals((i & 1) == 0
                                ? GuestActorEffectSignal.Kind.RAISE
                                : GuestActorEffectSignal.Kind.PANIC,
                        notice.effect());
                assertTrue(actors.pollActorFailureNotice().isEmpty());
            }
            assertEquals(0L, actors.actorFailureNoticesDropped());
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

    @Test void structuredChildFailureIdentifiesItsOwningSupervisor() throws Exception {
        CountDownLatch childCreated = new CountDownLatch(1);
        AtomicReference<ActorRuntime.ActorHandle<String>> child = new AtomicReference<>();
        try (ActorRuntime actors = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> parent = actors.spawnShared(
                    () -> (message, context) -> {
                        ActorRuntime.ActorHandle<String> next = actors.spawnChildShared(
                                childContext -> (mail, c) -> {
                                    throw GuestActorEffectSignal.raise();
                                });
                        child.set(next);
                        next.send("fail");
                        childCreated.countDown();
                    });
            parent.send("create");
            assertTrue(childCreated.await(3, TimeUnit.SECONDS));
            ActorRuntime.ActorHandle<String> handle = child.get();
            assertNotNull(handle);
            assertTrue(handle.ref().awaitTermination(3, TimeUnit.SECONDS));
            ActorRuntime.ActorFailureNotice notice =
                    actors.pollActorFailureNotice().orElseThrow();
            assertEquals(handle.id(), notice.actorId());
            assertEquals(parent.id(), notice.supervisorId());
            assertEquals(parent.id().value().toString(),
                    notice.envelope().supervisorId());
            assertTrue(actors.pollActorFailureNotice().isEmpty());
            parent.stop();
            assertTrue(parent.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test void controlNoticeIsWithheldUntilActorExitHookCompletes() throws Exception {
        CountDownLatch enteredTeardown = new CountDownLatch(1);
        CountDownLatch releaseTeardown = new CountDownLatch(1);
        try (ActorRuntime actors = new ActorRuntime()) {
            actors.setActorExitHook(domain -> {
                enteredTeardown.countDown();
                try {
                    if (!releaseTeardown.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test teardown barrier expired");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            ActorRuntime.ActorRef<String> actor = actors.spawnShared(
                    () -> (message, context) -> {
                        throw GuestActorEffectSignal.raise();
                    });
            try {
                actor.send("failure");
                assertTrue(enteredTeardown.await(3, TimeUnit.SECONDS),
                        "actor must reach physical teardown");
                assertTrue(actors.pollActorFailureNotice().isEmpty(),
                        "supervisor must not see failure before isolate cleanup");
            } finally {
                releaseTeardown.countDown();
            }
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(actor.id(),
                    actors.pollActorFailureNotice().orElseThrow().actorId());
        }
    }

    @Test void parentConsumesOnlyItsOwnChildControlNoticeOnItsActorTurn() throws Exception {
        CountDownLatch childCreated = new CountDownLatch(1);
        CountDownLatch parentInspected = new CountDownLatch(1);
        CountDownLatch siblingInspected = new CountDownLatch(1);
        AtomicReference<ActorRuntime.ActorHandle<String>> child = new AtomicReference<>();
        AtomicReference<ActorRuntime.ActorFailureNotice> observed = new AtomicReference<>();
        AtomicReference<ActorRuntime.ActorFailureNotice> siblingObserved =
                new AtomicReference<>();
        try (ActorRuntime actors = new ActorRuntime()) {
            // Host code cannot drain a parent's actor-owned CONTROL inbox.
            assertThrows(IllegalStateException.class,
                    actors::pollCurrentActorChildFailureNotice);
            assertThrows(IllegalStateException.class,
                    actors::currentActorChildFailureNoticesDropped);

            ActorRuntime.ActorRef<String> parent = actors.spawnShared(
                    () -> (message, context) -> {
                        if (message.equals("spawn")) {
                            ActorRuntime.ActorHandle<String> next = actors.spawnChildShared(
                                    childContext -> (mail, c) -> {
                                        throw GuestActorEffectSignal.raise();
                                    });
                            child.set(next);
                            next.send("boom");
                            childCreated.countDown();
                        } else if (message.equals("inspect")) {
                            observed.set(context.pollChildFailureNotice().orElse(null));
                            assertEquals(0L, context.childFailureNoticesDropped());
                            parentInspected.countDown();
                        }
                    });
            ActorRuntime.ActorRef<String> sibling = actors.spawnShared(
                    () -> (message, context) -> {
                        siblingObserved.set(context.pollChildFailureNotice().orElse(null));
                        siblingInspected.countDown();
                    });
            parent.send("spawn");
            assertTrue(childCreated.await(3, TimeUnit.SECONDS));
            ActorRuntime.ActorHandle<String> handle = child.get();
            assertNotNull(handle);
            assertTrue(handle.awaitTermination(3, TimeUnit.SECONDS));

            sibling.send("inspect");
            assertTrue(siblingInspected.await(3, TimeUnit.SECONDS));
            assertNull(siblingObserved.get(), "sibling cannot steal parent's notice");

            parent.send("inspect");
            assertTrue(parentInspected.await(3, TimeUnit.SECONDS));
            ActorRuntime.ActorFailureNotice notice = observed.get();
            assertNotNull(notice, "parent receives a terminal CONTROL notice");
            assertEquals(parent.id(), notice.supervisorId());
            assertEquals(handle.id(), notice.actorId());
            assertEquals(GuestActorEffectSignal.Kind.RAISE, notice.effect());
            // Host auditing and actor-owned delivery are independently visible.
            assertEquals(handle.id(), actors.pollActorFailureNotice()
                    .orElseThrow().actorId());
            parent.stop();
            sibling.stop();
            assertTrue(parent.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(sibling.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test void controlNoticeRejectsSourceSuppliedDiagnosticsAndMismatchedEffects() {
        try (ActorRuntime actors = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> actor = actors.spawnShared(
                    () -> (message, context) -> { });
            String id = actor.id().value().toString();
            var forged = new ActorErrorCatalog.Envelope(
                    ActorErrorCatalog.Kind.ACTOR_SUPERVISION,
                    "secret from guest heap", id, "");
            assertThrows(IllegalArgumentException.class,
                    () -> new ActorRuntime.ActorFailureNotice(
                            actor.id(), null, GuestActorEffectSignal.Kind.RAISE, forged));
            var wrongKindLabel = new ActorErrorCatalog.Envelope(
                    ActorErrorCatalog.Kind.ACTOR_SUPERVISION,
                    "actor raised a typed Error", id, "");
            assertThrows(IllegalArgumentException.class,
                    () -> new ActorRuntime.ActorFailureNotice(
                            actor.id(), null, GuestActorEffectSignal.Kind.PANIC, wrongKindLabel));
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
            assertSame(signal.kind() == GuestActorEffectSignal.Kind.RAISE
                            ? GuestActorEffectSignal.raise()
                            : GuestActorEffectSignal.panic(),
                    signal, "terminal signals must reuse immutable host objects");
        }
        assertNotSame(GuestActorEffectSignal.raise(), GuestActorEffectSignal.panic());
    }
}
