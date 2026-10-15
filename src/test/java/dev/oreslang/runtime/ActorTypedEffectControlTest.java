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

    @Test void optInParentReceivesAutomaticControlWithoutASecondUserMessage()
            throws Exception {
        CountDownLatch childCreated = new CountDownLatch(1);
        CountDownLatch handled = new CountDownLatch(1);
        AtomicReference<ActorRuntime.ActorHandle<String>> child = new AtomicReference<>();
        AtomicReference<ActorRuntime.ActorFailureNotice> seen = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        try (ActorRuntime actors = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> parent = actors.spawnShared(
                    () -> new ActorRuntime.ChildFailureHandler<String>() {
                        @Override
                        public void onMessage(String message, ActorRuntime.ActorContext<String> context) {
                            if (!message.equals("spawn")) {
                                throw new AssertionError("unexpected second user mailbox message");
                            }
                            ActorRuntime.ActorHandle<String> next = actors.spawnChildShared(
                                    childContext -> (mail, ignored) -> {
                                        throw GuestActorEffectSignal.raise();
                                    });
                            child.set(next);
                            next.send("fail");
                            childCreated.countDown();
                        }

                        @Override
                        public void onChildFailure(
                                ActorRuntime.ActorFailureNotice notice,
                                ActorRuntime.ActorContext<String> context) {
                            try {
                                assertTrue(ActorRuntime.inActorExecution(),
                                        "CONTROL callback must run on an actor carrier");
                                assertTrue(actors.currentThreadOwnsActorExecutionLease(),
                                        "CONTROL callback must own the parent execution lease");
                                assertTrue(context.currentMail().isEmpty(),
                                        "automatic CONTROL delivery is not a user mailbox message");
                                assertEquals(context.self().id(), notice.supervisorId());
                                assertEquals(child.get().id(), notice.actorId());
                                assertEquals(GuestActorEffectSignal.Kind.RAISE, notice.effect());
                                assertEquals(0L, context.childFailureNoticesDropped());
                                assertEquals(0L, context.childFailureWakesRejected());
                                assertTrue(context.pollChildFailureNotice().isEmpty(),
                                        "automatic callback must consume the queued CONTROL notice");
                                seen.set(notice);
                            } catch (Throwable error) {
                                callbackFailure.set(error);
                            } finally {
                                handled.countDown();
                            }
                        }
                    });
            parent.send("spawn");
            assertTrue(childCreated.await(3, TimeUnit.SECONDS));
            assertTrue(handled.await(3, TimeUnit.SECONDS),
                    "idle parent must be scheduled by child CONTROL notification");
            assertNull(callbackFailure.get(),
                    () -> "CONTROL callback failed: " + callbackFailure.get());
            assertNotNull(seen.get());
            assertTrue(child.get().ref().awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(child.get().id(),
                    actors.pollActorFailureNotice().orElseThrow().actorId());
            parent.stop();
            assertTrue(parent.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test void automaticChildControlHandlerWaitsForExitHookBarrier()
            throws Exception {
        CountDownLatch exitHookEntered = new CountDownLatch(1);
        CountDownLatch releaseExitHook = new CountDownLatch(1);
        CountDownLatch delivered = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean childCleanupDone =
                new java.util.concurrent.atomic.AtomicBoolean();
        AtomicReference<ActorRuntime.ActorHandle<String>> child = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        try (ActorRuntime actors = new ActorRuntime()) {
            actors.setActorExitHook(domain -> {
                exitHookEntered.countDown();
                try {
                    if (!releaseExitHook.await(3, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test exit hook timed out");
                    }
                    childCleanupDone.set(true);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            ActorRuntime.ActorRef<String> parent = actors.spawnShared(
                    () -> new ActorRuntime.ChildFailureHandler<String>() {
                        @Override
                        public void onMessage(String message, ActorRuntime.ActorContext<String> context) {
                            ActorRuntime.ActorHandle<String> next = actors.spawnChildShared(
                                    childContext -> (mail, ignored) -> {
                                        throw GuestActorEffectSignal.raise();
                                    });
                            child.set(next);
                            next.send("fail");
                        }

                        @Override
                        public void onChildFailure(
                                ActorRuntime.ActorFailureNotice notice,
                                ActorRuntime.ActorContext<String> context) {
                            try {
                                assertTrue(childCleanupDone.get(),
                                        "CONTROL callback ran before child's exit hook finished");
                                assertTrue(actors.currentThreadOwnsActorExecutionLease(),
                                        "parent CONTROL must run inside serialized actor turn");
                                assertEquals(child.get().id(), notice.actorId());
                            } catch (Throwable error) {
                                callbackFailure.set(error);
                            } finally {
                                delivered.countDown();
                            }
                        }
                    });
            try {
                parent.send("spawn");
                assertTrue(exitHookEntered.await(3, TimeUnit.SECONDS));
                assertEquals(1L, delivered.getCount(),
                        "parent handler must remain blocked until child exit");
            } finally {
                releaseExitHook.countDown();
            }
            assertTrue(delivered.await(3, TimeUnit.SECONDS));
            assertNull(callbackFailure.get(),
                    () -> "supervision violated teardown barrier: " + callbackFailure.get());
            assertTrue(child.get().ref().awaitTermination(3, TimeUnit.SECONDS));
            parent.stop();
            assertTrue(parent.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test void retiredParentCountsUnconsumedControlNoticesForHostAudit()
            throws Exception {
        CountDownLatch childCreated = new CountDownLatch(1);
        AtomicReference<ActorRuntime.ActorHandle<String>> child = new AtomicReference<>();
        try (ActorRuntime actors = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> parent = actors.spawnShared(
                    () -> (message, context) -> {
                        ActorRuntime.ActorHandle<String> next = actors.spawnChildShared(
                                childContext -> (mail, ignored) -> {
                                    throw GuestActorEffectSignal.raise();
                                });
                        child.set(next);
                        next.send("fail");
                        childCreated.countDown();
                    });
            parent.send("spawn");
            assertTrue(childCreated.await(3, TimeUnit.SECONDS));
            assertTrue(child.get().ref().awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(0L, actors.childFailureControlNoticesDropped());
            // No automatic handler and no manual poll: parent shutdown must
            // not silently discard a queued terminal CONTROL notification.
            parent.stop();
            assertTrue(parent.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(1L, actors.childFailureControlNoticesDropped());
            assertEquals(0L, actors.actorFailureNoticesDropped(),
                    "global host-audit queue remains independent");
            assertEquals(child.get().id(),
                    actors.pollActorFailureNotice().orElseThrow().actorId());
        }
    }

    @Test void automaticSupervisionEscalatesFromChildRaiseToParentPanic()
            throws Exception {
        CountDownLatch rootNotified = new CountDownLatch(1);
        AtomicReference<ActorRuntime.ActorHandle<String>> parentHandle =
                new AtomicReference<>();
        AtomicReference<ActorRuntime.ActorHandle<String>> grandchildHandle =
                new AtomicReference<>();
        AtomicReference<ActorRuntime.ActorFailureNotice> observed =
                new AtomicReference<>();
        try (ActorRuntime actors = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> root = actors.spawnShared(
                    () -> new ActorRuntime.ChildFailureHandler<String>() {
                        @Override
                        public void onMessage(
                                String message, ActorRuntime.ActorContext<String> context) {
                            ActorRuntime.ActorHandle<String> middle = actors.spawnChildShared(
                                    middleContext -> new ActorRuntime.ChildFailureHandler<String>() {
                                        @Override
                                        public void onMessage(
                                                String m, ActorRuntime.ActorContext<String> inner) {
                                            ActorRuntime.ActorHandle<String> leaf =
                                                    actors.spawnChildShared(
                                                            leafContext -> (mail, ignored) -> {
                                                                throw GuestActorEffectSignal.raise();
                                                            });
                                            grandchildHandle.set(leaf);
                                            leaf.send("fail");
                                        }

                                        @Override
                                        public void onChildFailure(
                                                ActorRuntime.ActorFailureNotice notice,
                                                ActorRuntime.ActorContext<String> inner) {
                                            assertEquals(GuestActorEffectSignal.Kind.RAISE,
                                                    notice.effect());
                                            throw GuestActorEffectSignal.panic();
                                        }
                                    });
                            parentHandle.set(middle);
                            middle.send("spawn leaf");
                        }

                        @Override
                        public void onChildFailure(
                                ActorRuntime.ActorFailureNotice notice,
                                ActorRuntime.ActorContext<String> context) {
                            observed.set(notice);
                            rootNotified.countDown();
                        }
                    });
            root.send("spawn middle");
            assertTrue(rootNotified.await(3, TimeUnit.SECONDS),
                    "root must receive automatic notification from failed middle actor");
            assertEquals(GuestActorEffectSignal.Kind.PANIC, observed.get().effect());
            assertEquals(root.id(), observed.get().supervisorId());
            assertEquals(parentHandle.get().id(), observed.get().actorId());
            assertTrue(parentHandle.get().ref().awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(grandchildHandle.get().ref().awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(0, actors.actorFailureNoticesDropped());
            root.stop();
            assertTrue(root.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test void parentCancellationPreventsAutomaticSupervisorResurrection()
            throws Exception {
        CountDownLatch childEntered = new CountDownLatch(1);
        CountDownLatch releaseChild = new CountDownLatch(1);
        CountDownLatch childSpawned = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger delivered =
                new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<ActorRuntime.ActorHandle<String>> child = new AtomicReference<>();
        try (ActorRuntime actors = new ActorRuntime()) {
            ActorRuntime.ActorRef<String> parent = actors.spawnShared(
                    () -> new ActorRuntime.ChildFailureHandler<String>() {
                        @Override
                        public void onMessage(String message, ActorRuntime.ActorContext<String> context) {
                            ActorRuntime.ActorHandle<String> next = actors.spawnChildShared(
                                    childContext -> (mail, ignored) -> {
                                        childEntered.countDown();
                                        if (!releaseChild.await(3, TimeUnit.SECONDS)) {
                                            throw new IllegalStateException("test latch expired");
                                        }
                                        throw GuestActorEffectSignal.raise();
                                    });
                            child.set(next);
                            next.send("fail");
                            childSpawned.countDown();
                        }

                        @Override
                        public void onChildFailure(
                                ActorRuntime.ActorFailureNotice notice,
                                ActorRuntime.ActorContext<String> context) {
                            delivered.incrementAndGet();
                        }
                    });
            parent.send("spawn");
            try {
                assertTrue(childSpawned.await(3, TimeUnit.SECONDS));
                assertTrue(childEntered.await(3, TimeUnit.SECONDS));
                assertTrue(parent.cancel(), "structured parent cancellation must win");
            } finally {
                releaseChild.countDown();
            }
            assertTrue(parent.awaitTermination(3, TimeUnit.SECONDS));
            assertEquals(0, delivered.get(),
                    "cancellation must not dispatch callbacks to retired supervisors");
            assertTrue(child.get().ref().awaitTermination(3, TimeUnit.SECONDS));
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
