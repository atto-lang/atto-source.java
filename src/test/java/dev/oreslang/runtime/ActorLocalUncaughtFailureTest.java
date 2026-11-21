package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host boundary conformance: self-local uncaught hooks are NOT supervisor hooks.
 * Source-level @Implements lowering and teardown are separate release gates.
 */
final class ActorLocalUncaughtFailureTest {
    private static final class GuestThrow extends GuestFailureSignal {
        GuestThrow() { super(SupervisorFailureLane.Kind.THROW, "test-throw"); }
    }
    private static final class GuestRaise extends GuestFailureSignal {
        GuestRaise() { super(SupervisorFailureLane.Kind.RAISE, "test-raise"); }
    }
    private static final class GuestPanic extends GuestFailureSignal {
        GuestPanic() { super(SupervisorFailureLane.Kind.PANIC, "test-panic"); }
    }

    @Test
    void locallyHandledThrowAndRaiseAbandonFailedTurnButKeepActorAlive() throws Exception {
        AtomicInteger throwsHandled = new AtomicInteger();
        AtomicInteger raisesHandled = new AtomicInteger();
        CountDownLatch reachedLaterMail = new CountDownLatch(1);
        class HandlingActor implements ActorRuntime.Behavior<Integer>,
                ActorRuntime.LocalUncaughtFailureHandler {
            @Override public void onMessage(Integer message, ActorRuntime.ActorContext<Integer> context) {
                switch (message) {
                    case 1 -> throw new GuestThrow();
                    case 2 -> throw new GuestRaise();
                    case 3 -> {
                        reachedLaterMail.countDown();
                        context.self().stop();
                    }
                    default -> fail("unexpected message");
                }
            }
            @Override public ActorRuntime.LocalFailureResolution onThrow(GuestFailureSignal failure) {
                assertInstanceOf(GuestThrow.class, failure);
                throwsHandled.incrementAndGet();
                return ActorRuntime.LocalFailureResolution.HANDLED;
            }
            @Override public ActorRuntime.LocalFailureResolution onRaise(GuestFailureSignal failure) {
                assertInstanceOf(GuestRaise.class, failure);
                raisesHandled.incrementAndGet();
                return ActorRuntime.LocalFailureResolution.HANDLED;
            }
        }
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawn(HandlingActor::new);
            ref.send(1);
            ref.send(2);
            ref.send(3);
            assertTrue(reachedLaterMail.await(5, TimeUnit.SECONDS));
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, throwsHandled.get());
            assertEquals(1, raisesHandled.get());
            assertTrue(ref.failure().isEmpty());
            assertTrue(runtime.pollSupervisionFailure().isEmpty());
        }
    }

    @Test
    void panicCannotInvokeEitherLocalHookEvenWhenBothArePresent() throws Exception {
        AtomicInteger localHookInvocations = new AtomicInteger();
        class HandlingActor implements ActorRuntime.Behavior<Integer>,
                ActorRuntime.LocalUncaughtFailureHandler {
            @Override public void onMessage(Integer message, ActorRuntime.ActorContext<Integer> context) {
                throw new GuestPanic();
            }
            @Override public ActorRuntime.LocalFailureResolution onThrow(GuestFailureSignal failure) {
                localHookInvocations.incrementAndGet();
                return ActorRuntime.LocalFailureResolution.HANDLED;
            }
            @Override public ActorRuntime.LocalFailureResolution onRaise(GuestFailureSignal failure) {
                localHookInvocations.incrementAndGet();
                return ActorRuntime.LocalFailureResolution.HANDLED;
            }
        }
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawn(HandlingActor::new);
            ref.send(1);
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, localHookInvocations.get());
            assertTrue(ref.failure().isPresent());
            var event = runtime.pollSupervisionFailure().orElseThrow();
            assertEquals(SupervisorFailureLane.Kind.PANIC, event.kind());
            runtime.acknowledgeSupervisionFailure(event.actor());
        }
    }

    @Test
    void explicitEscalationFromOwnThrowDoesNotDisappear() throws Exception {
        class EscalatingActor implements ActorRuntime.Behavior<Integer>,
                ActorRuntime.LocalUncaughtFailureHandler {
            @Override public void onMessage(Integer message, ActorRuntime.ActorContext<Integer> context) {
                throw new GuestThrow();
            }
            @Override public ActorRuntime.LocalFailureResolution onThrow(GuestFailureSignal failure) {
                return ActorRuntime.LocalFailureResolution.ESCALATE;
            }
            @Override public ActorRuntime.LocalFailureResolution onRaise(GuestFailureSignal failure) {
                return ActorRuntime.LocalFailureResolution.ESCALATE;
            }
        }
        try (ActorRuntime runtime = new ActorRuntime()) {
            var ref = runtime.<Integer>spawn(EscalatingActor::new);
            ref.send(1);
            assertTrue(ref.awaitTermination(5, TimeUnit.SECONDS));
            var event = runtime.pollSupervisionFailure().orElseThrow();
            assertEquals(SupervisorFailureLane.Kind.THROW, event.kind());
            runtime.acknowledgeSupervisionFailure(event.actor());
        }
    }
}
