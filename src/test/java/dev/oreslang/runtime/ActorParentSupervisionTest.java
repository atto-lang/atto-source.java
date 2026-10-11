package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

final class ActorParentSupervisionTest {
    private static final class GuestPanic extends GuestFailureSignal {
        GuestPanic() { super(SupervisorFailureLane.Kind.PANIC, "child panic"); }
    }

    private static final class Child implements ActorRuntime.Behavior<Integer> {
        @Override public void onMessage(Integer value, ActorRuntime.ActorContext<Integer> context) {
            throw new GuestPanic();
        }
    }

    private static class Parent implements ActorRuntime.Behavior<Integer> {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger normalDelivered = new AtomicInteger();
        final AtomicReference<ActorRuntime.ActorRef<Integer>> child = new AtomicReference<>();

        @Override public void onMessage(Integer value, ActorRuntime.ActorContext<Integer> context)
                throws Exception {
            if (value == 0) {
                var spawned = context.runtime().<Integer>spawn(
                        ActorRuntime.ActorKind.SHARED, context.policy(), ctx -> new Child());
                child.set(spawned);
                entered.countDown();
                // Hold parent logical lane while child fails, so the next
                // parent turn must choose urgent CONTROL over queued user mail.
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return;
            }
            normalDelivered.incrementAndGet();
        }
    }

    private static final class SupervisedParent extends Parent
            implements ActorRuntime.SupervisionHandler {
        final AtomicReference<ActorRuntime.ActorId> parentIdentity = new AtomicReference<>();
        final AtomicReference<SupervisorFailureLane.Failure> handled = new AtomicReference<>();
        final CountDownLatch notification = new CountDownLatch(1);
        final AtomicInteger messagesBeforeControl = new AtomicInteger(-1);
        @Override public ActorRuntime.SupervisionDecision onPanic(SupervisorFailureLane.Failure f) {
            parentIdentity.set(ActorRuntime.currentActorId().orElseThrow());
            messagesBeforeControl.set(normalDelivered.get());
            handled.set(f);
            notification.countDown();
            return ActorRuntime.SupervisionDecision.ACKNOWLEDGE;
        }
        @Override public ActorRuntime.SupervisionDecision onThrow(SupervisorFailureLane.Failure f) {
            return ActorRuntime.SupervisionDecision.ESCALATE;
        }
        @Override public ActorRuntime.SupervisionDecision onRaise(SupervisorFailureLane.Failure f) {
            return ActorRuntime.SupervisionDecision.ESCALATE;
        }
        @Override public ActorRuntime.SupervisionDecision onCancel(SupervisorFailureLane.Failure f) {
            return ActorRuntime.SupervisionDecision.ESCALATE;
        }
        @Override public ActorRuntime.SupervisionDecision onHostFailure(SupervisorFailureLane.Failure f) {
            return ActorRuntime.SupervisionDecision.ESCALATE;
        }
    }

    private static final class RestartingParent extends Parent
            implements ActorRuntime.SupervisionHandler {
        final AtomicInteger restarted = new AtomicInteger();
        final CountDownLatch budgetSpent = new CountDownLatch(3);
        @Override public ActorRuntime.SupervisionDecision onPanic(SupervisorFailureLane.Failure f) {
            return ActorRuntime.SupervisionDecision.RESTART;
        }
        @Override public ActorRuntime.SupervisionDecision onThrow(SupervisorFailureLane.Failure f) {
            return ActorRuntime.SupervisionDecision.ESCALATE;
        }
        @Override public ActorRuntime.SupervisionDecision onRaise(SupervisorFailureLane.Failure f) {
            return ActorRuntime.SupervisionDecision.ESCALATE;
        }
        @Override public ActorRuntime.SupervisionDecision onCancel(SupervisorFailureLane.Failure f) {
            return ActorRuntime.SupervisionDecision.ESCALATE;
        }
        @Override public ActorRuntime.SupervisionDecision onHostFailure(SupervisorFailureLane.Failure f) {
            return ActorRuntime.SupervisionDecision.ESCALATE;
        }
        @Override public void onRestarted(
                SupervisorFailureLane.Failure previous, ActorRuntime.ActorRef<?> replacement) {
            assertNotEquals(previous.actor().id(), replacement.id().value().toString(),
                    "restarted child needs fresh identity and actor-owned memory");
            restarted.incrementAndGet();
            budgetSpent.countDown();
            @SuppressWarnings("unchecked")
            ActorRuntime.ActorRef<Integer> next = (ActorRuntime.ActorRef<Integer>) replacement;
            next.send(1);
        }
    }

    @Test
    void repeatedGuestPanicsExhaustBoundedRestartAndEscalateWithoutKillingParent()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            RestartingParent behavior = new RestartingParent();
            var supervisor = runtime.<Integer>spawnSharedTrusted(ctx -> behavior);
            supervisor.send(0);
            assertTrue(behavior.entered.await(5, TimeUnit.SECONDS));
            behavior.child.get().send(1);
            behavior.release.countDown();

            SupervisorFailureLane.Failure root = null;
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < end && root == null) {
                root = runtime.pollSupervisionFailure().orElse(null);
                if (root == null) Thread.sleep(5);
            }
            assertNotNull(root, "fourth panic must exhaust the three-attempt budget");
            assertEquals(SupervisorFailureLane.Kind.PANIC, root.kind());
            assertEquals(3, behavior.restarted.get());
            assertTrue(behavior.budgetSpent.await(1, TimeUnit.SECONDS));
            assertTrue(supervisor.isAlive());
            runtime.acknowledgeSupervisionFailure(root.actor());
            runtime.stop(supervisor);
        }
    }

    @Test
    void parentControlPreemptsQueuedUserMailAndAckPreventsRootDuplicate() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            SupervisedParent behavior = new SupervisedParent();
            var supervisor = runtime.<Integer>spawnSharedTrusted(ctx -> behavior);
            supervisor.send(0);
            assertTrue(behavior.entered.await(5, TimeUnit.SECONDS));
            var child = behavior.child.get();
            assertNotNull(child);
            child.send(1);
            assertTrue(child.awaitTermination(5, TimeUnit.SECONDS));
            for (int i = 0; i < 25; i++) supervisor.send(1);
            try {
                assertEquals(0, behavior.normalDelivered.get());
            } finally {
                behavior.release.countDown();
            }
            assertTrue(behavior.notification.await(5, TimeUnit.SECONDS));
            assertEquals(supervisor.id(), behavior.parentIdentity.get());
            assertEquals(SupervisorFailureLane.Kind.PANIC, behavior.handled.get().kind());
            assertEquals(supervisor.id().value().toString(), behavior.handled.get().parentId());
            // Root has an unused reserved slot, but no spurious event.
            assertTrue(runtime.pollSupervisionFailure().isEmpty());
            assertEquals(0, behavior.messagesBeforeControl.get());
            runtime.stop(supervisor);
        }
    }

    @Test
    void missingParentHandlerEscalatesExactChildFaultToRoot() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Parent behavior = new Parent();
            var supervisor = runtime.<Integer>spawnSharedTrusted(ctx -> behavior);
            supervisor.send(0);
            assertTrue(behavior.entered.await(5, TimeUnit.SECONDS));
            var child = behavior.child.get();
            child.send(1);
            assertTrue(child.awaitTermination(5, TimeUnit.SECONDS));
            behavior.release.countDown();
            SupervisorFailureLane.Failure escalated = null;
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < end && escalated == null) {
                escalated = runtime.pollSupervisionFailure().orElse(null);
                if (escalated == null) Thread.sleep(5);
            }
            assertNotNull(escalated);
            assertEquals(SupervisorFailureLane.Kind.PANIC, escalated.kind());
            assertEquals(child.id().value().toString(), escalated.actor().id());
            runtime.acknowledgeSupervisionFailure(escalated.actor());
            runtime.stop(supervisor);
        }
    }
}
