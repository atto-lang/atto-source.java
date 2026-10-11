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

    private static final class FailingRestartCallbackParent extends Parent
            implements ActorRuntime.SupervisionHandler {
        final AtomicReference<ActorRuntime.ActorRef<?>> replacement = new AtomicReference<>();
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
                SupervisorFailureLane.Failure failed, ActorRuntime.ActorRef<?> next) {
            replacement.set(next);
            throw new IllegalStateException("restart callback rejected replacement");
        }
    }

    @Test
    void rejectedRestartCallbackStopsNewChildAndEscalatesWithoutParentFailure()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            FailingRestartCallbackParent handler = new FailingRestartCallbackParent();
            var supervisor = runtime.<Integer>spawnSharedTrusted(context -> handler);
            supervisor.send(0);
            assertTrue(handler.entered.await(5, TimeUnit.SECONDS));
            handler.child.get().send(1);
            handler.release.countDown();

            SupervisorFailureLane.Failure event = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline && event == null) {
                event = runtime.pollSupervisionFailure().orElse(null);
                if (event == null) Thread.sleep(5);
            }
            assertNotNull(event, "failed restart must escalate original child panic");
            assertEquals(SupervisorFailureLane.Kind.PANIC, event.kind());
            assertTrue(supervisor.isAlive(), "restart callback must not crash parent");
            ActorRuntime.ActorRef<?> replacement = handler.replacement.get();
            assertNotNull(replacement, "restart did admit a replacement");
            assertNotEquals(handler.child.get().id(), replacement.id());
            assertTrue(replacement.awaitTermination(5, TimeUnit.SECONDS),
                    "an orphan restart must be revoked after callback failure");
            assertTrue(replacement.failure().isEmpty(), "revoked restart exits gracefully");
            runtime.acknowledgeSupervisionFailure(event.actor());
            runtime.stop(supervisor);
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

    private static final class MiddleSupervisor
            implements ActorRuntime.Behavior<Integer> {
        @Override
        public void onMessage(Integer value, ActorRuntime.ActorContext<Integer> context) {
            var child = context.runtime().<Integer>spawn(
                    ActorRuntime.ActorKind.SHARED, context.policy(), ignored -> new Child());
            child.send(1);
        }
    }

    private static final class GrandparentSupervisor
            implements ActorRuntime.Behavior<Integer>, ActorRuntime.SupervisionHandler {
        final CountDownLatch observed = new CountDownLatch(1);
        final AtomicReference<SupervisorFailureLane.Failure> descendant =
                new AtomicReference<>();

        @Override
        public void onMessage(Integer value, ActorRuntime.ActorContext<Integer> context) {
            var middle = context.runtime().<Integer>spawn(
                    ActorRuntime.ActorKind.SHARED, context.policy(),
                    ignored -> new MiddleSupervisor());
            middle.send(0);
        }

        @Override
        public ActorRuntime.SupervisionDecision onPanic(SupervisorFailureLane.Failure failure) {
            assertTrue(ActorRuntime.inActorExecution(),
                    "grandparent must process the event on its own logical turn");
            assertEquals(SupervisorFailureLane.Kind.PANIC, failure.kind());
            descendant.set(failure);
            observed.countDown();
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

    @Test
    void unhandledChildPanicReachesGrandparentOnItsOwnLaneWithoutRootDuplicate()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            GrandparentSupervisor handler = new GrandparentSupervisor();
            var grandparent = runtime.<Integer>spawnSharedTrusted(context -> handler);
            grandparent.send(0);
            assertTrue(handler.observed.await(5, TimeUnit.SECONDS));
            assertEquals(SupervisorFailureLane.Kind.PANIC, handler.descendant.get().kind());
            // Grandparent acknowledged the original descendant event; neither
            // the intervening supervisor nor the root should report it twice.
            assertTrue(runtime.pollSupervisionFailure().isEmpty());
            assertTrue(grandparent.isAlive());
            runtime.stop(grandparent);
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
