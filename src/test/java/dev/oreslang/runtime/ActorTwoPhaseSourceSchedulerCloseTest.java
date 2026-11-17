package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** A source scheduler close must never call Future observers under actor lifecycleLock. */
@Timeout(20)
final class ActorTwoPhaseSourceSchedulerCloseTest {
    @Test
    void stoppedActorCanBeInspectedWhileSourceCancelSubscriberBlocks() throws Exception {
        CountDownLatch sourceAwaitEntered = new CountDownLatch(1);
        CountDownLatch cancellationObserved = new CountDownLatch(1);
        CountDownLatch releaseSubscriber = new CountDownLatch(1);
        OresFuture<Integer> borrowedProducer = new OresFuture<>();
        AtomicReference<Throwable> stopFailure = new AtomicReference<>();
        Thread firstStop = null;
        Thread concurrentStop = null;

        try (ActorRuntime runtime = new ActorRuntime()) {
            var actor = runtime.<Integer>spawnShared(() -> (message, context) -> {
                OresFuture<Integer> work = context.runtime().startActorTask(resume -> {
                    if (resume.initial()) {
                        sourceAwaitEntered.countDown();
                        return OresScheduler.await(borrowedProducer);
                    }
                    return OresScheduler.done(7);
                });
                work.whenCompleteRuntime((value, failure) -> {
                    cancellationObserved.countDown();
                    boolean released = false;
                    while (!released) {
                        try {
                            released = releaseSubscriber.await(25, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ignored) {
                            // An interrupt is not a physical completion receipt.
                        }
                    }
                });
            });
            actor.send(1);
            assertTrue(sourceAwaitEntered.await(4, TimeUnit.SECONDS));
            firstStop = new Thread(() -> {
                try {
                    actor.stop();
                } catch (Throwable error) {
                    if (!isStopDeadline(error)) stopFailure.set(error);
                }
            }, "source-close-stop-request");
            firstStop.start();

            try {
                assertTrue(cancellationObserved.await(4, TimeUnit.SECONDS),
                        "scheduler close must publish cancelled ordinary Task");
                assertFalse(actor.done().isDone(),
                        "actor get_done must wait for scheduler shutdown callbacks");
                concurrentStop = new Thread(() -> {
                    try {
                        actor.stop();
                    } catch (Throwable error) {
                        if (!isStopDeadline(error)) stopFailure.set(error);
                    }
                }, "source-close-concurrent-stop");
                concurrentStop.start();
                concurrentStop.join(1500);
                assertFalse(concurrentStop.isAlive(),
                        "source scheduler callback must not retain actor lifecycle monitor");
                assertNull(stopFailure.get());
                assertFalse(actor.done().isDone(),
                        "physical source-scheduler close receipt must still be pending");
            } finally {
                releaseSubscriber.countDown();
                if (firstStop != null) firstStop.join(3000);
                if (concurrentStop != null) concurrentStop.join(3000);
            }
            assertNull(stopFailure.get());
            assertFalse(firstStop.isAlive());
            assertFalse(concurrentStop.isAlive());
            actor.done().get(4, TimeUnit.SECONDS);
            assertFalse(borrowedProducer.isCancelled(),
                    "cancelled Task cannot cancel a borrowed producer");
        } finally {
            releaseSubscriber.countDown();
        }
    }

    /**
     * A host stop() is a bounded synchronization point: while a cancellation
     * subscriber is deliberately blocked, it returns with the documented
     * finalize-deadline error. Any other failure is a real defect.
     */
    private static boolean isStopDeadline(Throwable error) {
        return error instanceof IllegalStateException
                && String.valueOf(error.getMessage()).endsWith("did not finalize within the stop deadline");
    }
}
