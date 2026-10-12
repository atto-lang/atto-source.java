package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A physical replacement must belong to exactly one supervisor reservation,
 * including after closure has dropped strong references to pending workers.
 */
@Timeout(30)
final class ActorRuntimeRestartPermitBindingTest {
    private static void finalized(
            ActorRuntime.ActorGroup group, SupervisorChildRegistry.ChildId child)
            throws InterruptedException {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < limit) {
            if (group.childSnapshot(child).phase() == SupervisorChildRegistry.Phase.FINALIZED)
                return;
            Thread.sleep(5);
        }
        fail("supervised actor never reached FINALIZED");
    }

    @Test
    void rejectsCrossChildCommitAndCleanupAfterClose() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            ActorRuntime.BehaviorFactory<Integer> failure =
                    context -> (message, turn) -> { throw new IllegalStateException("planned"); };
            var first = runtime.spawnPrivate(failure);
            var second = runtime.spawnPrivate(failure);
            group.manageWorker(first);
            group.manageWorker(second);
            var a = group.supervisedWorkerSnapshot(first);
            var b = group.supervisedWorkerSnapshot(second);
            first.send(1);
            second.send(1);
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(second.awaitTermination(5, TimeUnit.SECONDS));
            finalized(group, a.child());
            finalized(group, b.child());
            var permitA = group.reserveFinalizedRestart(a.child(), first.id(), 1);
            var permitB = group.reserveFinalizedRestart(b.child(), second.id(), 1);
            var candidateA = group.spawnUnpublishedRestartCandidate(
                    permitA, ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(), context -> (message, turn) -> {});
            assertThrows(IllegalStateException.class,
                    () -> group.spawnUnpublishedRestartCandidate(
                            permitA, ActorRuntime.ActorKind.PRIVATE,
                            IsolatePolicy.developer(), context -> (message, turn) -> {}));
            assertThrows(IllegalStateException.class,
                    () -> group.commitUnpublishedRestart(permitB, candidateA));
            candidateA.stop();
            assertTrue(candidateA.awaitTermination(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,
                    () -> group.abortCleanedUnpublishedRestart(permitB, candidateA));

            var unrelated = runtime.spawnPrivate(
                    (ActorRuntime.BehaviorFactory<Integer>) context ->
                            (message, turn) -> {});
            unrelated.stop();
            assertTrue(unrelated.awaitTermination(5, TimeUnit.SECONDS));
            group.close();
            assertThrows(IllegalStateException.class,
                    () -> group.abortCleanedUnpublishedRestart(permitA, unrelated),
                    "closing a group must not allow unrelated physical DONE");
            var restored = group.abortCleanedUnpublishedRestart(permitA, candidateA);
            assertEquals(SupervisorChildRegistry.Phase.FINALIZED, restored.phase());
            assertEquals(a.child(), restored.child());
            assertThrows(IllegalStateException.class,
                    () -> group.abortCleanedUnpublishedRestart(permitA, candidateA));
            assertEquals(SupervisorChildRegistry.Phase.RESTARTING,
                    group.childSnapshot(permitB.child()).phase());
        }
    }

    @Test
    void foreignAndClosedReservationsCannotSpawnCandidates() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            var otherGroup = runtime.createActorGroup();
            var failed = runtime.spawnPrivate(
                    (ActorRuntime.BehaviorFactory<Integer>) context ->
                            (value, turn) -> { throw new IllegalStateException("failure"); });
            otherGroup.manageWorker(failed);
            var child = otherGroup.supervisedWorkerSnapshot(failed);
            failed.send(1);
            assertTrue(failed.awaitTermination(5, TimeUnit.SECONDS));
            finalized(otherGroup, child.child());
            var ticket = otherGroup.reserveFinalizedRestart(child.child(), failed.id(), 1);
            assertThrows(SecurityException.class,
                    () -> group.spawnUnpublishedRestartCandidate(ticket,
                            ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                            context -> (value, turn) -> {}));
            otherGroup.close();
            assertThrows(IllegalStateException.class,
                    () -> otherGroup.spawnUnpublishedRestartCandidate(ticket,
                            ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                            context -> (value, turn) -> {}));
            group.close();
        }
    }
}
