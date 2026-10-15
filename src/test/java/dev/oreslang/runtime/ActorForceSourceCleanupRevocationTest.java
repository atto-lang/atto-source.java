package dev.oreslang.runtime;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Force revocation is deliberately not lexical cancellation.
 *
 * <p>These are lifecycle unit tests with a simulated host revoker. The fake
 * success acknowledgement verifies actor bookkeeping on a QUIESCENT scheduler
 * lane; it cannot prove that arbitrary live guest execution was physically
 * stopped. That guarantee requires a real isolated VM/process revoker in
 * backend integration tests. A failed revoker must preserve structured cleanup.
 */
@Timeout(20)
final class ActorForceSourceCleanupRevocationTest {
    @Test
    void simulatedSuccessfulRevocationClosesParkedCleanupWithoutReenteringGuest()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 2, 64, 16))) {
            OresFuture<Integer> originalProducer = new OresFuture<>();
            OresFuture<Integer> cleanupProducer = new OresFuture<>();
            CountDownLatch taskEntered = new CountDownLatch(1);
            CountDownLatch cleanupEntered = new CountDownLatch(1);
            AtomicInteger cleanupCompleted = new AtomicInteger();
            AtomicInteger userMail = new AtomicInteger();
            AtomicReference<OresFuture<Void>> taskRef = new AtomicReference<>();

            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                userMail.incrementAndGet();
                OresFuture<Void> task = context.runtime().startActorTask(
                        new OresScheduler.CleanupAwareTask<Void>() {
                            private int pc;
                            @Override public OresScheduler.Step<Void> resume(
                                    OresScheduler.Resume resume) {
                                assertTrue(ActorRuntime.inActorExecution());
                                if (pc++ == 0) {
                                    taskEntered.countDown();
                                    return OresScheduler.await(originalProducer);
                                }
                                if (pc == 2) {
                                    assertInstanceOf(CancellationException.class,
                                            resume.failure());
                                    cleanupEntered.countDown();
                                    return OresScheduler.await(cleanupProducer);
                                }
                                cleanupCompleted.incrementAndGet();
                                return OresScheduler.done(null);
                            }
                        });
                taskRef.set(task);
            });

            actor.send("start");
            assertTrue(taskEntered.await(5, TimeUnit.SECONDS));
            assertNotNull(taskRef.get());
            assertTrue(actor.cancel());
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
            // The latch is signalled before armAwait registers its waiter.
            // Wait for that distinct scheduler phase rather than racing it.
            long registrationDeadline =
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (cleanupProducer.pendingRuntimeWaiterCount() == 0
                    && System.nanoTime() < registrationDeadline) {
                Thread.sleep(1);
            }
            assertEquals(1, cleanupProducer.pendingRuntimeWaiterCount());

            // No guest carrier is active: this is deliberately only a mock
            // of an isolation manager acknowledging the revoked domain.
            runtime.setForceCancellationTreeHook(request -> {
                assertEquals(actor.id(), request.rootActorId());
                assertEquals(1, request.targets().size());
                return true;
            });
            assertTrue(actor.forceCancel());
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                    actor.failure().orElseThrow());
            assertThrows(CancellationException.class,
                    () -> taskRef.get().get(5, TimeUnit.SECONDS));
            assertEquals(0, cleanupProducer.pendingRuntimeWaiterCount());
            assertEquals(0, originalProducer.pendingRuntimeWaiterCount());

            // Foreign producers can still settle, but the revoked guest
            // frame must never be entered again.
            originalProducer.completeFromRuntime(11);
            cleanupProducer.completeFromRuntime(22);
            assertEquals(0, cleanupCompleted.get());
            assertEquals(1, userMail.get());
        }
    }

    @Test
    void failedForceRevocationPreservesPreviouslyStartedStructuredCleanup()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 2, 64, 16))) {
            OresFuture<Integer> originalProducer = new OresFuture<>();
            OresFuture<Integer> cleanupProducer = new OresFuture<>();
            CountDownLatch taskEntered = new CountDownLatch(1);
            CountDownLatch cleanupEntered = new CountDownLatch(1);
            CountDownLatch cleanupCompleted = new CountDownLatch(1);
            AtomicInteger cleanupRuns = new AtomicInteger();
            AtomicReference<OresFuture<Void>> taskRef = new AtomicReference<>();

            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                taskRef.set(context.runtime().startActorTask(
                        new OresScheduler.CleanupAwareTask<Void>() {
                            private int pc;
                            @Override public OresScheduler.Step<Void> resume(
                                    OresScheduler.Resume resume) {
                                assertTrue(ActorRuntime.inActorExecution());
                                if (pc++ == 0) {
                                    taskEntered.countDown();
                                    return OresScheduler.await(originalProducer);
                                }
                                if (pc == 2) {
                                    assertInstanceOf(CancellationException.class,
                                            resume.failure());
                                    cleanupEntered.countDown();
                                    return OresScheduler.await(cleanupProducer);
                                }
                                assertEquals(42, resume.value());
                                cleanupRuns.incrementAndGet();
                                cleanupCompleted.countDown();
                                return OresScheduler.done(null);
                            }
                        }));
            });

            actor.send("start");
            assertTrue(taskEntered.await(5, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));
            runtime.setForceCancellationTreeHook(request -> false);

            assertThrows(IllegalStateException.class, actor::forceCancel);
            assertFalse(actor.awaitTermination(50, TimeUnit.MILLISECONDS),
                    "failed revocation cannot skip already-owned lexical cleanup");

            cleanupProducer.completeFromRuntime(42);
            assertTrue(cleanupCompleted.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, cleanupRuns.get());
            assertEquals(0, originalProducer.pendingRuntimeWaiterCount());
            assertEquals(0, cleanupProducer.pendingRuntimeWaiterCount());
            assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                    actor.failure().orElseThrow());
            assertThrows(CancellationException.class,
                    () -> taskRef.get().get(5, TimeUnit.SECONDS));
        }
    }
    @Test
    void deniedRevocationDuringActiveTurnPreservesRegisteredFinalCleanup()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 2, 64, 16))) {
            CountDownLatch cleanupRegistered = new CountDownLatch(1);
            CountDownLatch releaseCarrier = new CountDownLatch(1);
            CountDownLatch revokerEntered = new CountDownLatch(1);
            CountDownLatch releaseRevoker = new CountDownLatch(1);
            AtomicInteger finalCleanup = new AtomicInteger();

            var actor = runtime.<String>spawnSharedTrusted(
                    context -> (message, turn) -> {
                        turn.runtime().endCurrentActorWithCleanup(
                                finalCleanup::incrementAndGet);
                        cleanupRegistered.countDown();
                        try {
                            assertTrue(releaseCarrier.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    });
            actor.send("end");
            assertTrue(cleanupRegistered.await(5, TimeUnit.SECONDS));

            // A live JVM carrier has NOT been physically revoked. An honest
            // host isolation hook cannot acknowledge successful revocation
            // here. While the attempt is fenced, the guest is still in its
            // prior turn, so the previously registered cleanup stays pending.
            runtime.setForceCancellationTreeHook(request -> {
                revokerEntered.countDown();
                try {
                    assertTrue(releaseRevoker.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return false;
            });
            AtomicReference<Throwable> observed = new AtomicReference<>();
            Thread canceller = Thread.ofPlatform().start(() -> {
                try {
                    actor.forceCancel();
                    observed.set(new AssertionError(
                            "revoker returned false but kill was accepted"));
                } catch (IllegalStateException expected) {
                    // A denied force revocation must be side-effect free.
                } catch (Throwable unexpected) {
                    observed.set(unexpected);
                }
            });
            try {
                assertTrue(revokerEntered.await(5, TimeUnit.SECONDS));
                assertEquals(0, finalCleanup.get());
                assertFalse(actor.awaitTermination(50, TimeUnit.MILLISECONDS));
                releaseRevoker.countDown();
                canceller.join(5_000);
                assertFalse(canceller.isAlive(), "denied revocation must return");
                if (observed.get() != null) throw new AssertionError(observed.get());
            } finally {
                releaseRevoker.countDown();
                releaseCarrier.countDown();
            }

            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, finalCleanup.get(),
                    "ordinary actor final cleanup must survive a denied revocation");
            assertTrue(actor.failure().isEmpty(),
                    "denied force kill must not replace graceful self.end");
        }
    }
    @Test
    void deniedForceRevocationReplaysQueuedCleanupOnlyAfterFenceClears()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(1, 2, 64, 16))) {
            OresFuture<Integer> abandonedProducer = new OresFuture<>();
            OresFuture<Integer> cleanupProducer = new OresFuture<>();
            CountDownLatch taskEntered = new CountDownLatch(1);
            CountDownLatch cleanupEntered = new CountDownLatch(1);
            CountDownLatch cleanupFinished = new CountDownLatch(1);
            CountDownLatch revokerEntered = new CountDownLatch(1);
            CountDownLatch releaseRevoker = new CountDownLatch(1);
            AtomicInteger cleanupResumes = new AtomicInteger();
            AtomicReference<OresFuture<Void>> taskRef = new AtomicReference<>();

            var actor = runtime.<String>spawnShared(() -> (message, context) -> {
                taskRef.set(context.runtime().startActorTask(
                        new OresScheduler.CleanupAwareTask<Void>() {
                            private int pc;
                            @Override public OresScheduler.Step<Void> resume(
                                    OresScheduler.Resume resume) {
                                assertTrue(ActorRuntime.inActorExecution());
                                if (pc++ == 0) {
                                    taskEntered.countDown();
                                    return OresScheduler.await(abandonedProducer);
                                }
                                if (pc == 2) {
                                    assertInstanceOf(CancellationException.class,
                                            resume.failure());
                                    cleanupEntered.countDown();
                                    return OresScheduler.await(cleanupProducer);
                                }
                                assertEquals(91, resume.value());
                                cleanupResumes.incrementAndGet();
                                cleanupFinished.countDown();
                                return OresScheduler.done(null);
                            }
                        }));
            });

            actor.send("start");
            assertTrue(taskEntered.await(5, TimeUnit.SECONDS));
            assertTrue(actor.cancel());
            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS));

            // Do not race the source task's armAwait registration.
            long registeredBy = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (cleanupProducer.pendingRuntimeWaiterCount() == 0
                    && System.nanoTime() < registeredBy) {
                Thread.sleep(1);
            }
            assertEquals(1, cleanupProducer.pendingRuntimeWaiterCount());

            runtime.setForceCancellationTreeHook(request -> {
                revokerEntered.countDown();
                try {
                    assertTrue(releaseRevoker.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return false;
            });
            AtomicReference<Throwable> killFailure = new AtomicReference<>();
            Thread revoker = Thread.ofPlatform().start(() -> {
                try {
                    actor.forceCancel();
                    killFailure.set(new AssertionError(
                            "force revoker returned false, but kill succeeded"));
                } catch (IllegalStateException expected) {
                    // Failed revocation must restore the suspended STOP lane.
                } catch (Throwable unexpected) {
                    killFailure.set(unexpected);
                }
            });
            try {
                assertTrue(revokerEntered.await(5, TimeUnit.SECONDS));
                cleanupProducer.completeFromRuntime(91);
                assertEquals(0, cleanupResumes.get(),
                        "guest cleanup must not run while the host revoker is fenced");
                assertFalse(taskRef.get().isDone(),
                        "a queued cleanup turn must not settle before it runs");
                assertFalse(actor.awaitTermination(50, TimeUnit.MILLISECONDS));
            } finally {
                releaseRevoker.countDown();
            }
            revoker.join(5_000);
            assertFalse(revoker.isAlive());
            if (killFailure.get() != null) throw new AssertionError(killFailure.get());

            assertTrue(cleanupFinished.await(5, TimeUnit.SECONDS),
                    "failed revocation must restore already-queued source cleanup");
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, cleanupResumes.get());
            assertEquals(0, abandonedProducer.pendingRuntimeWaiterCount());
            assertEquals(0, cleanupProducer.pendingRuntimeWaiterCount());
            assertInstanceOf(ActorRuntime.ActorCancelledException.class,
                    actor.failure().orElseThrow());
            assertThrows(CancellationException.class,
                    () -> taskRef.get().get(5, TimeUnit.SECONDS));
        }
    }

}
