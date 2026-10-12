package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

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
                    permit, ActorRuntime.ActorKind.PRIVATE,
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
    void groupCloseCancelsUnpublishedPhysicalActorBeforeItCanInitialize() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            ActorRuntime.ActorRef<Integer> failed = runtime.spawnPrivate(
                    (ActorRuntime.BehaviorFactory<Integer>) context ->
                            (value, turn) -> { throw new IllegalStateException("planned"); });
            group.manageWorker(failed);
            var first = group.supervisedWorkerSnapshot(failed);
            failed.send(1);
            assertTrue(failed.awaitTermination(5, TimeUnit.SECONDS));
            waitForFinalized(group, first.child());
            var permit = group.reserveFinalizedRestart(first.child(), failed.id(), 1);
            java.util.concurrent.atomic.AtomicInteger initialized =
                    new java.util.concurrent.atomic.AtomicInteger();
            ActorRuntime.ActorRef<Integer> hidden = group.spawnUnpublishedRestartCandidate(
                    permit, ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    context -> {
                        initialized.incrementAndGet();
                        return (value, turn) -> {};
                    });
            assertThrows(IllegalStateException.class, hidden::ready);
            assertEquals(0, initialized.get(), "hidden actor initialized prematurely");
            assertThrows(IllegalStateException.class, () -> hidden.send(10));
            group.close();
            assertTrue(hidden.awaitTermination(5, TimeUnit.SECONDS),
                    "group close leaked a pending physical RESTART candidate");
            assertEquals(0, initialized.get(),
                    "a cancelled hidden actor ran its guest initializer");
            assertFalse(runtime.isAlive(hidden));
            assertEquals(SupervisorChildRegistry.Phase.FINALIZED,
                    group.abortCleanedUnpublishedRestart(permit, hidden).phase(),
                    "post-close cleanup retains exact reservation ownership");
        }
    }

    @RepeatedTest(20)
    void concurrentStopAndCommitHaveExactlyOnePublicationOrder() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var group = runtime.createActorGroup();
            ActorRuntime.ActorRef<Integer> failed = runtime.spawnPrivate(
                    (ActorRuntime.BehaviorFactory<Integer>) context ->
                            (value, turn) -> { throw new IllegalStateException("planned"); });
            group.manageWorker(failed);
            var original = group.supervisedWorkerSnapshot(failed);
            failed.send(1);
            assertTrue(failed.awaitTermination(5, TimeUnit.SECONDS));
            waitForFinalized(group, original.child());
            var permit = group.reserveFinalizedRestart(original.child(), failed.id(), 1);
            ActorRuntime.ActorRef<Integer> candidate = group.spawnUnpublishedRestartCandidate(
                    permit, ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    context -> (value, turn) -> {});
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch bothReady = new CountDownLatch(2);
            AtomicReference<SupervisorChildRegistry.Snapshot> committed =
                    new AtomicReference<>();
            AtomicReference<Throwable> admissionFailure = new AtomicReference<>();
            AtomicReference<Throwable> physicalFailure = new AtomicReference<>();
            Thread publisher = new Thread(() -> {
                bothReady.countDown();
                try {
                    start.await();
                    committed.set(group.commitUnpublishedRestart(permit, candidate));
                } catch (Throwable failure) {
                    admissionFailure.set(failure);
                }
            }, "restart-publisher");
            Thread stopper = new Thread(() -> {
                bothReady.countDown();
                try {
                    start.await();
                    candidate.stop();
                } catch (Throwable failure) {
                    physicalFailure.set(failure);
                }
            }, "restart-physical-stopper");
            publisher.start();
            stopper.start();
            assertTrue(bothReady.await(5, TimeUnit.SECONDS));
            start.countDown();
            publisher.join(5000);
            stopper.join(5000);
            assertFalse(publisher.isAlive(), "RESTART publication deadlocked with STOP");
            assertFalse(stopper.isAlive(), "physical STOP deadlocked with publication");
            assertNull(physicalFailure.get());
            assertTrue(candidate.awaitTermination(5, TimeUnit.SECONDS));
            if (committed.get() != null) {
                assertNull(admissionFailure.get());
                assertEquals(candidate.id(), committed.get().actor());
                assertEquals(2, committed.get().incarnation());
            } else {
                assertInstanceOf(IllegalStateException.class, admissionFailure.get());
                assertEquals(SupervisorChildRegistry.Phase.FINALIZED,
                        group.abortCleanedUnpublishedRestart(permit, candidate).phase());
            }
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
                    permit, ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    context -> (value, turn) -> {});
            next.stop();
            assertTrue(next.awaitTermination(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class,
                    () -> group.commitUnpublishedRestart(permit, next));
            assertEquals(SupervisorChildRegistry.Phase.RESTARTING,
                    group.childSnapshot(old.child()).phase(),
                    "failed admission must not fabricate a committed replacement");
            var restored = group.abortCleanedUnpublishedRestart(permit, next);
            assertEquals(SupervisorChildRegistry.Phase.FINALIZED, restored.phase());
            assertEquals(old.child(), restored.child());
            assertThrows(IllegalStateException.class,
                    () -> group.abortCleanedUnpublishedRestart(permit, next),
                    "physically completed cleanup cannot replay a consumed permit");
            group.close();
        }
    }
}
