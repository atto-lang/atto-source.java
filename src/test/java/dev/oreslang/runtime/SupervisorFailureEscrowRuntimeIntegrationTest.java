package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real ActorRuntime terminal and DATA admission boundaries; host-only policy. */
@Timeout(20)
final class SupervisorFailureEscrowRuntimeIntegrationTest {
    private static final AtomicReference<CountDownLatch> SIBLING_PROGRESS =
            new AtomicReference<>();

    @Test
    void childFailureReachesEscrowWhileSupervisorDataMailboxIsFull() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var processed = new java.util.concurrent.atomic.AtomicInteger();
        var defaults = IsolatePolicy.developer();
        var policy = new IsolatePolicy(defaults.capabilities(), defaults.maxHeapBytes(),
                2, defaults.maxWallTime());
        try (ActorRuntime runtime = new ActorRuntime()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            ActorRuntime.ActorRef<String> supervisor = runtime.spawnPrivateTrusted(
                    policy, ignored -> (message, turn) -> {
                        if (message.equals("hold")) {
                            entered.countDown();
                            if (!release.await(10, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("test did not release DATA turn");
                            }
                        } else {
                            processed.incrementAndGet();
                        }
                    });
            ActorRuntime.ActorRef<String> child = runtime.spawnPrivate(
                    ignored -> (message, turn) -> {
                        throw new IllegalStateException("child failed");
                    });
            long parentId = bridge.attach(supervisor, null);
            long childId = bridge.attach(child, supervisor);
            try {
                supervisor.send("hold");
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                supervisor.send("data1");
                supervisor.send("data2");
                assertThrows(ActorRuntime.ActorMailboxFullException.class,
                        () -> supervisor.send("overflow"));

                child.send("fail");
                assertThrows(ExecutionException.class, () -> child.done().get(5, TimeUnit.SECONDS));
                assertEquals(1, bridge.pendingCount());
                var delivery = bridge.deliver(childId);
                assertNotNull(delivery);
                assertEquals(parentId, delivery.controllerId());
                assertEquals(supervisor.generation(), delivery.controllerGeneration());
                assertEquals(childId, delivery.notice().failedId());
                assertEquals(child.generation(), delivery.notice().failedGeneration());
                assertEquals(0, processed.get());
                assertThrows(ActorRuntime.ActorMailboxFullException.class,
                        () -> supervisor.send("still-full"));
                var commits = new java.util.concurrent.atomic.AtomicInteger();
                assertTrue(bridge.commitAndAcknowledge(delivery, commits::incrementAndGet));
                assertFalse(bridge.commitAndAcknowledge(delivery, commits::incrementAndGet));
                assertEquals(1, commits.get());
                assertEquals(0, bridge.pendingCount());
                assertNull(bridge.deliver(childId));
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void failedSupervisorPreservesDeliveredChildFailureAndFencesDescendants()
            throws Exception {
        CountDownLatch siblingSignal = new CountDownLatch(1);
        SIBLING_PROGRESS.set(siblingSignal);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            ActorRuntime.ActorRef<String> supervisor = runtime.spawnPrivate(
                    ignored -> (message, turn) -> {
                        if ("fail".equals(message)) throw new IllegalStateException("supervisor failed");
                    });
            ActorRuntime.ActorRef<String> subordinate = runtime.spawnPrivate(
                    ignored -> (message, turn) -> {
                        if ("fail".equals(message)) throw new IllegalStateException("child failed");
                    });
            ActorRuntime.ActorRef<String> descendant = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            ActorRuntime.ActorRef<String> sibling = runtime.spawnPrivate(
                    ignored -> (message, turn) -> SIBLING_PROGRESS.get().countDown());

            long supervisorId = bridge.attach(supervisor, null);
            long subordinateId = bridge.attach(subordinate, supervisor);
            bridge.attach(descendant, subordinate);
            bridge.attach(sibling, null);

            supervisor.getReady().get(5, TimeUnit.SECONDS);
            subordinate.getReady().get(5, TimeUnit.SECONDS);
            sibling.getReady().get(5, TimeUnit.SECONDS);

            subordinate.send("fail");
            assertThrows(ExecutionException.class,
                    () -> subordinate.done().get(5, TimeUnit.SECONDS));
            var beforeParentCrash = bridge.deliver(subordinateId);
            assertNotNull(beforeParentCrash);
            assertEquals(supervisorId, beforeParentCrash.controllerId());

            supervisor.send("fail");
            assertThrows(ExecutionException.class,
                    () -> supervisor.done().get(5, TimeUnit.SECONDS));
            assertEquals(2, bridge.pendingCount(),
                    "both real runtime failures remain in CONTROL escrow");
            assertFalse(bridge.commitAndAcknowledge(beforeParentCrash, () -> { }),
                    "a dead supervisor cannot acknowledge an old delivery");

            assertThrows(ActorRuntime.ActorSealedException.class,
                    () -> descendant.send("must-reject"));
            assertThrows(ActorRuntime.ActorSealedException.class,
                    () -> descendant.getReady());

            sibling.send("healthy");
            assertTrue(siblingSignal.await(5, TimeUnit.SECONDS),
                    "independent sibling must still execute");
            assertTrue(sibling.isAlive());

            var ancestor = bridge.deliver(subordinateId);
            assertNotNull(ancestor);
            assertEquals(1L, ancestor.controllerId(), "root guardian receives takeover");
            assertNotSame(beforeParentCrash, ancestor);
            assertThrows(IllegalStateException.class,
                    () -> bridge.commitAndAcknowledge(ancestor, () -> {
                        throw new IllegalStateException("policy commit failed");
                    }));
            assertEquals(2, bridge.pendingCount(),
                    "failed policy action cannot silently acknowledge CONTROL");
            assertTrue(bridge.commitAndAcknowledge(ancestor, () -> { }));
            assertFalse(bridge.commitAndAcknowledge(ancestor, () -> { }),
                    "duplicate ACK must be inert");
            assertTrue(bridge.commitAndAcknowledge(bridge.deliver(supervisorId), () -> { }));
            assertEquals(0, bridge.pendingCount());
        } finally {
            SIBLING_PROGRESS.compareAndSet(siblingSignal, null);
        }
    }

    @Test
    void bindingMustPrecedeActivationAndUseExactOwningRuntime() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ActorRuntime foreignRuntime = new ActorRuntime()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            ActorRuntime.ActorRef<String> foreign = foreignRuntime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            assertThrows(SecurityException.class, () -> bridge.attach(foreign, null));

            ActorRuntime.ActorRef<String> activated = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            activated.getReady().get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class,
                    () -> bridge.attach(activated, null));

            ActorRuntime.ActorRef<String> fresh = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            bridge.attach(fresh, null);
            assertThrows(IllegalStateException.class, () -> bridge.attach(fresh, null));
            assertEquals(0, bridge.pendingCount());
        }
    }

    @Test
    void normalStopDoesNotCreateFailureNotice() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivate(
                    ignored -> (message, turn) -> turn.self().stop());
            long nodeId = bridge.attach(actor, null);
            actor.send("finish");
            actor.done().get(5, TimeUnit.SECONDS);
            assertEquals(0, bridge.pendingCount());
            assertFalse(bridge.snapshot().get(nodeId).alive());
            assertFalse(bridge.snapshot().get(nodeId).pending());
        }
    }
}
