package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class ActorGroupWorkerTransferTest {
    private static volatile CountDownLatch enteredTurn;
    private static volatile CountDownLatch allowExit;

    @Test
    void releasedWorkerCanBeAdoptedOnceByAnotherSupervisor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup original = runtime.createActorGroup();
            ActorRuntime.ActorGroup next = runtime.createActorGroup();
            ActorRuntime.ActorRef<Integer> worker = original.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    context -> (message, actorContext) -> { });
            worker.ready().get(5, TimeUnit.SECONDS);
            assertThrows(IllegalStateException.class, () -> next.manageWorker(worker));
            assertTrue(original.releaseWorker(worker));
            assertFalse(original.releaseWorker(worker));
            assertEquals(0, original.workerCount());
            assertSame(worker, next.manageWorker(worker));
            original.close();
            assertTrue(worker.isAlive(), "old owner must not stop released worker");
            next.close();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationAdmissionFencesOwnershipTransferBeforeActorTurnCompletes() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch exit = new CountDownLatch(1);
            enteredTurn = entered;
            allowExit = exit;
            ActorRuntime.ActorRef<Integer> worker = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                    context -> (message, actorContext) -> {
                        ActorGroupWorkerTransferTest.enteredTurn.countDown();
                        try {
                            ActorGroupWorkerTransferTest.allowExit.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    });
            worker.send(1);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                group.cancelWorkers();
                assertThrows(IllegalStateException.class, () -> group.releaseWorker(worker),
                        "worker must retain stop-admitted lifecycle owner");
            } finally {
                exit.countDown();
            }
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            group.close();
        }
    }

    @Test
    @Timeout(35)
    void workerReleaseAndGroupCloseLinearizeAtOneOwner() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            for (int i = 0; i < 12; i++) {
                var owner = runtime.createActorGroup();
                var successor = runtime.createActorGroup();
                ActorRuntime.ActorRef<Integer> worker = owner.spawnWorker(
                        ActorRuntime.ActorKind.PRIVATE, IsolatePolicy.developer(),
                        factory -> (message, ctx) -> { });
                worker.ready().get(5, TimeUnit.SECONDS);
                CountDownLatch start = new CountDownLatch(1);
                var released = new java.util.concurrent.atomic.AtomicReference<Boolean>();
                var unexpected = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                Thread transfer = Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        released.set(owner.releaseWorker(worker));
                    } catch (IllegalStateException closedFirst) {
                        if (owner.closed()) released.set(false);
                        else unexpected.set(closedFirst);
                    } catch (Throwable error) {
                        unexpected.set(error);
                    }
                });
                Thread shutdown = Thread.ofVirtual().start(() -> {
                    try {
                        start.await();
                        owner.close();
                    } catch (Throwable error) {
                        unexpected.set(error);
                    }
                });
                start.countDown();
                transfer.join(5000);
                shutdown.join(5000);
                assertFalse(transfer.isAlive(), "release must finish");
                assertFalse(shutdown.isAlive(), "close must finish");
                assertNull(unexpected.get(), "race must not corrupt owner state");
                assertNotNull(released.get(), "release must have an outcome");
                assertEquals(0, owner.workerCount());
                if (released.get()) {
                    assertTrue(worker.isAlive(), "old owner cannot cancel released worker");
                    assertSame(worker, successor.manageWorker(worker));
                    successor.close();
                } else {
                    assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
                    assertThrows(IllegalStateException.class,
                            () -> successor.manageWorker(worker));
                    successor.close();
                }
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

}
