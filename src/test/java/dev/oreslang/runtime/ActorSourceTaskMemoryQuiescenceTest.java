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
                    () -> (message, context) -> {
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
                        sourceTask.set(context.runtime().ownCurrentActorFuture(future));
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
                assertTrue(sourceTask.get().isDone());
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
}
