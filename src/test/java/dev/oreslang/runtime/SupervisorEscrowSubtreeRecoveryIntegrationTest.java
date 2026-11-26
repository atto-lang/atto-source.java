package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real ActorRuntime dispatch and termination tests. Native/FFI physical
 * verification is deliberately NOT implied by the Java done() barrier.
 */
@Timeout(25)
final class SupervisorEscrowSubtreeRecoveryIntegrationTest {
    private static final AtomicReference<CountDownLatch> SIBLING_PROGRESS =
            new AtomicReference<>();

    @Test
    void failedSupervisorAutomaticallyCancelsNestedPolicyChildrenButNotSibling()
            throws Exception {
        CountDownLatch siblingSignal = new CountDownLatch(1);
        SIBLING_PROGRESS.set(siblingSignal);
        try (ActorRuntime runtime = new ActorRuntime();
             var control = Executors.newSingleThreadExecutor()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(
                    runtime, bridge, control, ignored -> false);
            ActorRuntime.ActorRef<String> supervisor = runtime.spawnPrivate(
                    ignored -> (message, turn) -> {
                        if ("panic".equals(message)) throw new IllegalStateException("policy crash");
                    });
            ActorRuntime.ActorRef<String> childSupervisor = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            ActorRuntime.ActorRef<String> leaf = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            ActorRuntime.ActorRef<String> sibling = runtime.spawnPrivate(
                    ignored -> (message, turn) -> SIBLING_PROGRESS.get().countDown());

            long root = recovery.attach(supervisor, null);
            recovery.attach(childSupervisor, supervisor);
            recovery.attach(leaf, childSupervisor);
            recovery.attach(sibling, null);

            supervisor.getReady().get(5, TimeUnit.SECONDS);
            childSupervisor.getReady().get(5, TimeUnit.SECONDS);
            leaf.getReady().get(5, TimeUnit.SECONDS);
            sibling.getReady().get(5, TimeUnit.SECONDS);
            supervisor.send("panic");

            assertThrows(ExecutionException.class,
                    () -> supervisor.done().get(5, TimeUnit.SECONDS));
            assertTrue(childSupervisor.awaitTermination(5, TimeUnit.SECONDS),
                    "nested supervisor should be cancelled automatically");
            assertTrue(leaf.awaitTermination(5, TimeUnit.SECONDS),
                    "nested leaf should be cancelled automatically");
            assertNull(recovery.controlFault(), "CONTROL scheduler must remain healthy");
            assertEquals(1, bridge.pendingCount(), "failed supervisor notice is retained");
            assertEquals(SupervisorEscrowSubtreeRecoveryV1.RestartBlocker.PENDING_FAILURE,
                    recovery.restartBlocker(root));
            assertThrows(IllegalStateException.class, () -> recovery.reserveRestart(root));
            assertTrue(bridge.commitAndAcknowledge(bridge.deliver(root), () -> { }));
            assertEquals(
                    SupervisorEscrowSubtreeRecoveryV1.RestartBlocker.PHYSICAL_RELEASE_UNVERIFIED,
                    recovery.restartBlocker(root),
                    "a Java actor done barrier cannot forge native/FFI release");

            sibling.send("still-running");
            assertTrue(siblingSignal.await(5, TimeUnit.SECONDS));
            assertTrue(sibling.isAlive());
        } finally {
            SIBLING_PROGRESS.compareAndSet(siblingSignal, null);
        }
    }

    @Test
    void testOnlyPhysicalOraclePermitsNewGenerationAndRejectsReplayedPermit()
            throws Exception {
        // This synthetic TRUE verifier is deliberately test-only. Production
        // integration must use an authenticated native/FFI release receipt.
        try (ActorRuntime runtime = new ActorRuntime();
             var control = Executors.newSingleThreadExecutor()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(
                    runtime, bridge, control, ref -> true);
            ActorRuntime.ActorRef<String> first = runtime.spawnPrivate(
                    ignored -> (message, turn) -> {
                        if ("fail".equals(message)) throw new IllegalStateException("first");
                    });
            long oldNode = recovery.attach(first, null);
            first.send("fail");
            assertThrows(ExecutionException.class,
                    () -> first.done().get(5, TimeUnit.SECONDS));
            assertEquals(1, bridge.pendingCount());
            assertTrue(bridge.commitAndAcknowledge(bridge.deliver(oldNode), () -> { }));
            assertEquals(SupervisorEscrowSubtreeRecoveryV1.RestartBlocker.READY,
                    recovery.restartBlocker(oldNode));

            var permit = recovery.reserveRestart(oldNode);
            assertThrows(IllegalStateException.class, () -> recovery.reserveRestart(oldNode));
            ActorRuntime.ActorRef<String> replacement = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            long replacementNode = recovery.attachReplacement(permit, replacement, null);
            assertTrue(replacement.generation() > first.generation());
            assertTrue(replacementNode != oldNode);
            assertEquals(SupervisorEscrowSubtreeRecoveryV1.RestartBlocker.ALREADY_REPLACED,
                    recovery.restartBlocker(oldNode));
            assertThrows(IllegalStateException.class,
                    () -> recovery.attachReplacement(permit, replacement, null));
            assertFalse(first.isAlive(), "old generation must stay stopped");
            replacement.send("healthy");
            replacement.getReady().get(5, TimeUnit.SECONDS);
            assertTrue(replacement.isAlive());
            assertEquals(replacement.generation(),
                    bridge.snapshot().get(replacementNode).generation());
        }
    }

    @Test
    void testOnlyPhysicalOracleRebuildsNestedTreeTopDown()
            throws Exception {
        // The verifier is synthetic: proves epoch mechanics, NOT FFI cleanup.
        try (ActorRuntime runtime = new ActorRuntime();
             var control = Executors.newSingleThreadExecutor()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(
                    runtime, bridge, control, ref -> true);
            ActorRuntime.ActorRef<String> oldS = runtime.spawnPrivate(
                    ignored -> (message, turn) -> {
                        if ("crash".equals(message)) throw new IllegalStateException("S");
                    });
            ActorRuntime.ActorRef<String> oldT = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            ActorRuntime.ActorRef<String> oldA = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            long oldSId = recovery.attach(oldS, null);
            long oldTId = recovery.attach(oldT, oldS);
            long oldAId = recovery.attach(oldA, oldT);
            oldS.getReady().get(5, TimeUnit.SECONDS);
            oldT.getReady().get(5, TimeUnit.SECONDS);
            oldA.getReady().get(5, TimeUnit.SECONDS);
            oldS.send("crash");
            assertThrows(ExecutionException.class,
                    () -> oldS.done().get(5, TimeUnit.SECONDS));
            assertTrue(oldT.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(oldA.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(bridge.commitAndAcknowledge(bridge.deliver(oldSId), () -> { }));

            ActorRuntime.ActorRef<String> newS = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            long newSId = recovery.attachReplacement(
                    recovery.reserveRestart(oldSId), newS, null);
            assertNotEquals(oldSId, newSId);
            ActorRuntime.ActorRef<String> newT = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            long newTId = recovery.attachReplacement(
                    recovery.reserveRestart(oldTId), newT, newS);
            assertNotEquals(oldTId, newTId);
            ActorRuntime.ActorRef<String> newA = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            long newAId = recovery.attachReplacement(
                    recovery.reserveRestart(oldAId), newA, newT);
            assertNotEquals(oldAId, newAId);
            assertTrue(newS.generation() > oldS.generation());
            assertTrue(newT.generation() > oldT.generation());
            assertTrue(newA.generation() > oldA.generation());
            assertEquals(newSId, bridge.snapshot().get(newTId).parentId());
            assertEquals(newTId, bridge.snapshot().get(newAId).parentId());
            newS.getReady().get(5, TimeUnit.SECONDS);
            newT.getReady().get(5, TimeUnit.SECONDS);
            newA.getReady().get(5, TimeUnit.SECONDS);
            newA.send("hello");
            assertTrue(newA.isAlive());
            assertFalse(oldA.isAlive());
            assertEquals(SupervisorEscrowSubtreeRecoveryV1.RestartBlocker.ALREADY_REPLACED,
                    recovery.restartBlocker(oldAId));
        }
    }

    @Test
    void concurrentHostAttachAndSupervisorCrashNeverLeaveAnUntrackedControlNode()
            throws Exception {
        for (int round = 0; round < 8; round++) {
            try (ActorRuntime runtime = new ActorRuntime();
                 var control = Executors.newSingleThreadExecutor();
                 var racers = Executors.newFixedThreadPool(2)) {
                var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
                var recovery = new SupervisorEscrowSubtreeRecoveryV1(
                        runtime, bridge, control, ignored -> false);
                ActorRuntime.ActorRef<String> supervisor = runtime.spawnPrivate(
                        ignored -> (message, turn) -> {
                            if ("crash".equals(message)) {
                                throw new IllegalStateException("injected supervisor crash");
                            }
                        });
                ActorRuntime.ActorRef<String> child = runtime.spawnPrivate(
                        ignored -> (message, turn) -> { });
                recovery.attach(supervisor, null);
                supervisor.getReady().get(5, TimeUnit.SECONDS);
                var start = new CountDownLatch(1);
                Future<Long> joining = racers.submit(() -> {
                    start.await();
                    return recovery.attach(child, supervisor);
                });
                Future<?> crashing = racers.submit(() -> {
                    start.await();
                    supervisor.send("crash");
                    return null;
                });
                start.countDown();
                crashing.get(5, TimeUnit.SECONDS);
                long attached = -1L;
                try {
                    attached = joining.get(5, TimeUnit.SECONDS);
                } catch (ExecutionException rejected) {
                    assertInstanceOf(IllegalStateException.class, rejected.getCause(),
                            "only a fenced supervisor may reject host child attachment");
                }
                assertThrows(ExecutionException.class,
                        () -> supervisor.done().get(5, TimeUnit.SECONDS));
                if (attached > 0) {
                    assertTrue(child.awaitTermination(5, TimeUnit.SECONDS),
                            "a published CONTROL child must be retired after supervisor failure");
                    assertNotNull(bridge.snapshot().get(attached));
                    assertEquals(3, bridge.snapshot().size());
                } else {
                    assertEquals(2, bridge.snapshot().size(),
                            "rejected attachment must not strand a CONTROL ledger entry");
                    runtime.cancel(child);
                    assertTrue(child.awaitTermination(5, TimeUnit.SECONDS));
                }
                assertEquals(1, bridge.pendingCount());
                assertNull(recovery.controlFault(),
                        "coordinator must not observe a ledger node without an ActorRef");
            }
        }
    }

    @Test
    void prestartedEmergencyCarrierCancelsDescendantAndPreservesSibling() throws Exception {
        CountDownLatch siblingProgress = new CountDownLatch(1);
        try (ActorRuntime runtime = new ActorRuntime();
             var control = new SupervisorEmergencyControlV1()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(
                    runtime, bridge, control, ignored -> false);
            ActorRuntime.ActorRef<String> supervisor = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { throw new IllegalStateException("injected"); });
            ActorRuntime.ActorRef<String> child = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            ActorRuntime.ActorRef<String> sibling = runtime.spawnPrivate(
                    ignored -> (message, turn) -> siblingProgress.countDown());
            long root = recovery.attach(supervisor, null);
            recovery.attach(child, supervisor);
            recovery.attach(sibling, null);
            supervisor.getReady().get(5, TimeUnit.SECONDS);
            supervisor.send("panic");
            assertThrows(ExecutionException.class,
                    () -> supervisor.done().get(5, TimeUnit.SECONDS));
            assertTrue(child.awaitTermination(5, TimeUnit.SECONDS));
            sibling.send("healthy");
            assertTrue(siblingProgress.await(5, TimeUnit.SECONDS));
            assertNull(recovery.controlFault());
            assertEquals(1, bridge.pendingCount());
            assertTrue(bridge.commitAndAcknowledge(bridge.deliver(root), () -> { }));
            assertEquals(SupervisorEscrowSubtreeRecoveryV1.RestartBlocker.PHYSICAL_RELEASE_UNVERIFIED,
                    recovery.restartBlocker(root));
        }
    }

    @Test
    void closedEmergencyCarrierCannotAuthorizeRestart() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var control = new SupervisorEmergencyControlV1()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(runtime, bridge, control, ignored -> true);
            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { throw new IllegalStateException("injected"); });
            long node = recovery.attach(actor, null);
            actor.getReady().get(5, TimeUnit.SECONDS);
            actor.send("fail");
            assertThrows(ExecutionException.class, () -> actor.done().get(5, TimeUnit.SECONDS));
            assertTrue(bridge.commitAndAcknowledge(bridge.deliver(node), () -> { }));
            assertTrue(control.closeWithin(5, TimeUnit.SECONDS));
            assertEquals(SupervisorEscrowSubtreeRecoveryV1.RestartBlocker.CONTROL_FAILED,
                    recovery.restartBlocker(node));
            assertThrows(IllegalStateException.class, () -> recovery.reserveRestart(node));
        }
    }

    @Test
    void inlineControlExecutorFailsClosedInsteadOfRunningUnderActorLock()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(
                    runtime, bridge, Runnable::run, ignored -> false);
            ActorRuntime.ActorRef<String> supervisor = runtime.spawnPrivate(
                    ignored -> (message, turn) -> {
                        if ("fail".equals(message)) throw new IllegalStateException("fault");
                    });
            ActorRuntime.ActorRef<String> child = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            long id = recovery.attach(supervisor, null);
            recovery.attach(child, supervisor);
            supervisor.send("fail");
            assertThrows(ExecutionException.class,
                    () -> supervisor.done().get(5, TimeUnit.SECONDS));
            assertNotNull(recovery.controlFault(),
                    "inline host callback must poison CONTROL authority");
            assertEquals(SupervisorEscrowSubtreeRecoveryV1.RestartBlocker.CONTROL_FAILED,
                    recovery.restartBlocker(id));
            assertEquals(1, bridge.pendingCount());
        }
    }
}
