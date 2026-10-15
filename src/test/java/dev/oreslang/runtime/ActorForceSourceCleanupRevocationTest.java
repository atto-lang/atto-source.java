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
 * Force revocation is deliberately not lexical cancellation. Once the host
 * confirms physical revocation, no guest finally/defer is allowed to resume.
 * A failed revoker, conversely, must preserve the existing structured cleanup.
 */
@Timeout(20)
final class ActorForceSourceCleanupRevocationTest {
    @Test
    void successfulForceRevocationClosesParkedCleanupWithoutRunningMoreGuestCode()
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
}
