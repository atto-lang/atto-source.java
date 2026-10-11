package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual actor mailboxes + scheduler source CONTROL cancellation handshake.
 * Completion of source cleanup must precede physical get_done().
 */
@Timeout(30)
final class ActorSourceCleanupDrainTest {
    private static final class Gates {
        final OresFuture<Void> userAwait = new OresFuture<>();
        final OresFuture<Void> cleanupAwait = new OresFuture<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch cleanupEntered = new CountDownLatch(1);
        final AtomicInteger userMessages = new AtomicInteger();
        final AtomicInteger cleanupStarts = new AtomicInteger();
        final AtomicInteger cleanupCompletes = new AtomicInteger();
        final AtomicReference<Object> executionDomain = new AtomicReference<>();
        final AtomicReference<OresFuture<Integer>> task = new AtomicReference<>();
    }

    private static ActorRuntime.ActorRef<String> spawnWorker(ActorRuntime runtime, Gates gates) {
        return runtime.spawnShared(() -> (message, context) -> {
            gates.userMessages.incrementAndGet();
            OresFuture<Integer> running = context.runtime().startActorTask(
                    new OresScheduler.CancellationAwareTask<>() {
                        private volatile boolean cancellationRequested;
                        private volatile boolean cleaning;
                        private int pc;

                        @Override
                        public void cancellationRequested(OresScheduler.TaskCancelledException reason) {
                            cancellationRequested = true;
                        }

                        @Override
                        public boolean cleanupInProgress() {
                            return cleaning;
                        }

                        @Override
                        public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                            assertSame(context.self().id(),
                                    context.runtime().currentActorId().orElseThrow());
                            Object domain = OresScheduler.currentTaskDomain();
                            assertNotNull(domain);
                            Object first = gates.executionDomain.getAndSet(domain);
                            if (first != null) assertSame(first, domain);

                            if (pc++ == 0) {
                                assertTrue(resume.initial());
                                gates.entered.countDown();
                                return OresScheduler.await(gates.userAwait);
                            }
                            if (pc == 2) {
                                assertTrue(cancellationRequested);
                                assertInstanceOf(OresScheduler.TaskCancelledException.class,
                                        resume.failure());
                                cleaning = true;
                                gates.cleanupStarts.incrementAndGet();
                                gates.cleanupEntered.countDown();
                                context.runtime().schedulerSafepoint();
                                return OresScheduler.await(gates.cleanupAwait);
                            }
                            if (pc == 3) {
                                assertTrue(cleaning);
                                assertNull(resume.failure(),
                                        "source cleanup Future must complete, not receive cancellation twice");
                                context.runtime().schedulerSafepoint();
                                gates.cleanupCompletes.incrementAndGet();
                                cleaning = false;
                                return OresScheduler.done(21);
                            }
                            throw new AssertionError("source continuation replay: " + pc);
                        }
                    });
            gates.task.set(context.runtime().ownCurrentActorFuture(running));
        });
    }

    @Test
    void cancellationDrainsOnOwningActorLaneBeforePhysicalDone() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Gates gates = new Gates();
            ActorRuntime.ActorRef<String> actor = spawnWorker(runtime, gates);
            actor.send("run");
            assertTrue(gates.entered.await(3, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            assertTrue(gates.cleanupEntered.await(3, TimeUnit.SECONDS));
            assertEquals(1, gates.cleanupStarts.get());
            assertEquals(0, gates.cleanupCompletes.get());
            assertFalse(actor.get_done().isDone(),
                    "physical actor done cannot publish while cleanup Future is outstanding");
            assertFalse(actor.awaitTermination(10, TimeUnit.MILLISECONDS));
            assertFalse(gates.userAwait.isDone(),
                    "abandoned upstream await must be detached, not cancelled");

            gates.cleanupAwait.completeFromRuntime(null);
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(gates.task.get().isCancelled());
            assertEquals(1, gates.cleanupStarts.get());
            assertEquals(1, gates.cleanupCompletes.get());
            assertEquals(1, gates.userMessages.get());
            assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                    actor.failure().orElseThrow());
        }
    }

    @Test
    void gracefulStopAlsoPreservesOutstandingSourceCleanup() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Gates gates = new Gates();
            ActorRuntime.ActorRef<String> actor = spawnWorker(runtime, gates);
            actor.send("run");
            assertTrue(gates.entered.await(3, TimeUnit.SECONDS));
            // Host stop() is synchronous. Issue it from another host thread
            // so this test can release the intentionally suspended cleanup.
            AtomicReference<Throwable> stopFailure = new AtomicReference<>();
            Thread stopper = Thread.ofPlatform().start(() -> {
                try {
                    actor.stop();
                } catch (Throwable failure) {
                    stopFailure.set(failure);
                }
            });
            assertTrue(gates.cleanupEntered.await(3, TimeUnit.SECONDS));
            assertFalse(actor.get_done().isDone());

            gates.cleanupAwait.completeFromRuntime(null);
            stopper.join(3000);
            assertFalse(stopper.isAlive(), "host stop should finish after source cleanup");
            assertNull(stopFailure.get(), "host stop must observe drained cleanup");
            assertTrue(actor.awaitTermination(3, TimeUnit.SECONDS));
            assertTrue(actor.failure().isEmpty());
            assertTrue(gates.task.get().isCancelled());
            assertEquals(1, gates.cleanupCompletes.get());
        }
    }
}
