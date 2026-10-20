package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression for #1566: actor memory and done() must not retire when a source
 * Task Future settles but its scheduler's quiescence callback has not fired.
 */
@Timeout(20)
final class ActorSourceTaskMemoryQuiescenceTest {
    @Test
    void sharedActorWaitsForPostCarrierRetirementEvenAfterTaskFutureSettles() throws Exception {
        verifyActorRetainsMemoryUntilSourceTaskRetires(false);
    }

    @Test
    void untrustedQuotaWrapperPreservesNestedRetirementHook() throws Exception {
        verifyActorRetainsMemoryUntilSourceTaskRetires(true);
    }

    private static void verifyActorRetainsMemoryUntilSourceTaskRetires(boolean untrusted)
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 2, 8, 64))) {
            CountDownLatch retirementHeld = new CountDownLatch(1);
            AtomicReference<Runnable> retire = new AtomicReference<>();
            AtomicReference<OresFuture<Void>> sourceTask = new AtomicReference<>();

            ActorRuntime.BehaviorFactory<String> factory =
                    factoryContext -> (message, context) -> {
                        OresScheduler.Task<Void> child = new OresScheduler.Task<>() {
                            @Override
                            public OresScheduler.Step<Void> resume(OresScheduler.Resume resume) {
                                return OresScheduler.done(null);
                            }

                            @Override
                            public void afterCarrierQuiescent(Runnable completion) {
                                assertTrue(retire.compareAndSet(null, completion));
                                retirementHeld.countDown();
                            }
                        };
                        OresFuture<Void> future = runtime.startActorTask(child);
                        sourceTask.set(future); // startActorTask now registers actor ownership itself
                    };
            ActorRuntime.ActorRef<String> actor = untrusted
                    ? runtime.spawnUntrusted(factory)
                    : runtime.spawnShared(factory);
            actor.send("start");
            try {
                assertTrue(retirementHeld.await(5, TimeUnit.SECONDS),
                        "scheduler never entered post-carrier retirement hook");
                // The terminal result is logically visible, but the scheduler's
                // deferred physical-quiescence callback still owns actor memory.
                OresFuture<Void> taskFuture = sourceTask.get();
                assertNotNull(taskFuture, "actor must have published child Future");
                CountDownLatch terminal = new CountDownLatch(1);
                taskFuture.whenCompleteRuntime((ignored, failure) -> terminal.countDown());
                assertTrue(terminal.await(5, TimeUnit.SECONDS),
                        "terminal Future notification must not depend on resource retirement");
                assertTrue(taskFuture.isDone());
                actor.stop();
                assertFalse(actor.done().isDone(),
                        "actor done published before source Task post-carrier retirement");
                assertFalse(actor.awaitTermination(100, TimeUnit.MILLISECONDS),
                        "actor must retain its arena while a source Task lease is live");
                assertEquals(1, runtime.actorCount());
            } finally {
                Runnable release = retire.get();
                if (release != null) release.run();
            }
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS),
                    "actor should finalize after source Task's retirement callback");
            assertEquals(0, runtime.actorCount());
        }
    }
    @Test
    void stoppedActorRejectsNewSourceTaskWithoutLeakingLifetimeLease() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch refused = new CountDownLatch(1);
            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(
                    () -> (message, context) -> {
                        context.self().stop();
                        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                                () -> runtime.startActorTask(
                                        resume -> OresScheduler.done(null)));
                        refused.countDown();
                    });
            actor.send("stop");
            assertTrue(refused.await(5, TimeUnit.SECONDS));
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, runtime.actorCount());
        }
    }

    @Test
    void actorStopCancelsSuspendedSourceTaskWithoutExplicitFutureOwnership() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch admitted = new CountDownLatch(1);
            AtomicReference<OresFuture<Void>> source = new AtomicReference<>();
            ActorRuntime.ActorRef<String> actor = runtime.spawnShared(
                    () -> (message, context) -> {
                        // Deliberately do not call ownCurrentActorFuture().
                        OresFuture<Void> future = runtime.startActorTask(
                                resume -> OresScheduler.await(new OresFuture<>()));
                        source.set(future);
                        admitted.countDown();
                    });
            actor.send("launch");
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(source.get().isCancelled(),
                    "actor-owned source Task must be revoked on actor shutdown");
            assertEquals(0, runtime.actorCount());
        }
    }

}
