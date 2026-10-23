package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executable host-scheduler Task cancellation races, not guest source
 * task/suspends lowering or an actor-affine generation unload proof.
 */
@Timeout(40)
final class OresTaskCancellationRaceRuntimeTest {

    @Test
    void cancelVersusProducerCompletionRetiresEveryPhysicalFrameExactlyOnce()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(2);
             OresTaskScope scope = OresTaskScope.compilerOwned()) {
            final int rounds = 128;
            AtomicInteger totalReleaseCalls = new AtomicInteger();
            for (int round = 0; round < rounds; round++) {
                final OresFuture<Integer> sharedProducer = new OresFuture<>();
                final CountDownLatch firstTurnEntered = new CountDownLatch(1);
                final CountDownLatch startRace = new CountDownLatch(1);
                final AtomicInteger resumes = new AtomicInteger();
                final AtomicInteger releaseCalls = new AtomicInteger();
                final AtomicReference<Throwable> raceFailure = new AtomicReference<>();
                OresTask<Integer> child = scope.startCompilerFrame(scheduler, resume -> {
                    if (resume.initial()) {
                        firstTurnEntered.countDown();
                        return new OresScheduler.Await<Integer>(sharedProducer);
                    }
                    if (resume.failure() != null)
                        throw new IllegalStateException("unexpected producer failure",
                                resume.failure());
                    resumes.incrementAndGet();
                    return new OresScheduler.Done<>(((Integer) resume.value()) + 1);
                }, () -> {
                    releaseCalls.incrementAndGet();
                    totalReleaseCalls.incrementAndGet();
                });
                assertTrue(firstTurnEntered.await(5, TimeUnit.SECONDS));
                Thread cancel = Thread.ofVirtual().start(() -> {
                    try {
                        assertTrue(startRace.await(5, TimeUnit.SECONDS));
                        child.cancel();
                    } catch (Throwable failure) {
                        raceFailure.compareAndSet(null, failure);
                    }
                });
                Thread settle = Thread.ofVirtual().start(() -> {
                    try {
                        assertTrue(startRace.await(5, TimeUnit.SECONDS));
                        assertTrue(sharedProducer.completeFromRuntime(40));
                    } catch (Throwable failure) {
                        raceFailure.compareAndSet(null, failure);
                    }
                });
                startRace.countDown();
                cancel.join(5_000);
                settle.join(5_000);
                assertFalse(cancel.isAlive());
                assertFalse(settle.isAlive());
                assertNull(raceFailure.get());
                assertNull(AsyncRuntime.await(child.getDone()),
                        "cleanup and code-image pin retirement must finish");
                assertTrue(child.isQuiescent());
                assertTrue(child.isDone());
                assertEquals(1, releaseCalls.get(), "physical code pin released once");
                assertFalse(sharedProducer.isCancelled(),
                        "cancelling one awaiting Task never cancels shared producer");
                assertFalse(sharedProducer.completeFromRuntime(42));
                int observedResumes = resumes.get();
                assertTrue(observedResumes <= 1,
                        "one settled producer cannot resume Task more than once");
                assertEquals(observedResumes, resumes.get(),
                        "late producer re-completion cannot resurrect a retired Task");
            }
            assertEquals(rounds, totalReleaseCalls.get());
            assertTrue(scope.cancel());
            assertFalse(scope.cancel());
            assertEquals(rounds, totalReleaseCalls.get(),
                    "scope cancellation may not release retired pins twice");
        }
    }

    @Test
    void cancelledAwaiterDetachesFromPendingProducerAndNeverResumes()
            throws Exception {
        try (OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope scope = OresTaskScope.compilerOwned()) {
            OresFuture<Integer> unresolved = new OresFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            AtomicInteger resumed = new AtomicInteger();
            AtomicInteger released = new AtomicInteger();
            OresTask<Integer> worker = scope.startCompilerFrame(scheduler, resume -> {
                if (resume.initial()) {
                    entered.countDown();
                    return new OresScheduler.Await<Integer>(unresolved);
                }
                resumed.incrementAndGet();
                return new OresScheduler.Done<>(6);
            }, released::incrementAndGet);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(worker.cancel());
            assertNull(AsyncRuntime.await(worker.getDone()));
            assertEquals(1, released.get());
            assertEquals(0, resumed.get());
            assertEquals(0, unresolved.pendingRuntimeWaiterCount(),
                    "cancel must detach suspended Task's producer registration");
            assertTrue(unresolved.completeFromRuntime(5));
            assertEquals(0, resumed.get());
            assertEquals(1, released.get());
        }
    }
}
