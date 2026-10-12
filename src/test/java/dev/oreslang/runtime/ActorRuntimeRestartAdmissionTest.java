package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual ActorCell and ActorGroup lifecycle admission, not a synthetic
 * BooleanSupplier/identity-registry test. Guest RESTART lowering stays off.
 */
@Timeout(30)
final class ActorRuntimeRestartAdmissionTest {
    private static void waitForFinalized(
            ActorRuntime.ActorGroup group,
            SupervisorChildRegistry.ChildId child) throws InterruptedException {
        long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < limit) {
            if (group.childSnapshot(child).phase() == SupervisorChildRegistry.Phase.FINALIZED)
                return;
            Thread.sleep(5);
        }
        fail("failed actor never reached physical finalization and FINALIZED registry state");
    }

    @Test
    void hiddenPhysicalCandidateCannotInitializeOrReceiveUntilCommit() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            ActorRuntime.ActorRef<Integer> failed = runtime.spawnPrivate(
                    (ActorRuntime.BehaviorFactory<Integer>) context ->
                            (value, turn) -> { throw new IllegalStateException("planned failure"); });
            group.manageWorker(failed);
            var original = group.supervisedWorkerSnapshot(failed);
            failed.send(1);
            assertTrue(failed.awaitTermination(5, TimeUnit.SECONDS));
            waitForFinalized(group, original.child());

            var permit = group.reserveFinalizedRestart(
                    original.child(), failed.id(), original.incarnation());
            ActorRuntime.ActorRef<Integer> next = group.spawnUnpublishedRestartCandidate(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    context -> (value, turn) -> turn.self().stop());

            assertThrows(IllegalStateException.class, next::ready);
            assertThrows(IllegalStateException.class, () -> next.send(123));
            assertThrows(IllegalStateException.class, () -> group.manageWorker(next));
            assertEquals(SupervisorChildRegistry.Phase.RESTARTING,
                    group.childSnapshot(original.child()).phase());

            var committed = group.commitUnpublishedRestart(permit, next);
            assertEquals(original.child(), committed.child());
            assertEquals(original.incarnation() + 1, committed.incarnation());
            assertEquals(next.id(), committed.actor());
            assertEquals(SupervisorChildRegistry.Phase.ACTIVE, committed.phase());
            assertEquals(committed, group.supervisedWorkerSnapshot(next));

            assertThrows(IllegalStateException.class,
                    () -> group.commitUnpublishedRestart(permit, next),
                    "a committed generation cannot reuse the ticket");
            next.send(7);
            assertTrue(next.awaitTermination(5, TimeUnit.SECONDS));
            group.close();
        }
    }

    @Test
    void physicalStopBeforePublicationCannotCreateLogicalReplacement() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            ActorRuntime.ActorRef<Integer> failed = runtime.spawnPrivate(
                    (ActorRuntime.BehaviorFactory<Integer>) context ->
                            (value, turn) -> { throw new IllegalStateException("planned failure"); });
            group.manageWorker(failed);
            var old = group.supervisedWorkerSnapshot(failed);
            failed.send(1);
            assertTrue(failed.awaitTermination(5, TimeUnit.SECONDS));
            waitForFinalized(group, old.child());

            var permit = group.reserveFinalizedRestart(old.child(), failed.id(), 1);
            ActorRuntime.ActorRef<Integer> next = group.spawnUnpublishedRestartCandidate(
                    ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    context -> (value, turn) -> {});
            next.stop();
            assertTrue(next.awaitTermination(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,
                    () -> group.commitUnpublishedRestart(permit, next));
            assertEquals(SupervisorChildRegistry.Phase.RESTARTING,
                    group.childSnapshot(old.child()).phase(),
                    "failed admission must not fabricate a committed replacement");
            group.close();
        }
    }
}
