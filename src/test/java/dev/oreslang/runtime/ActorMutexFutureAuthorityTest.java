package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actor authority regression for adapting a physical SharedMutex GuardFuture
 * into the scheduler-owned OresFuture. Host completion callbacks are allowed
 * to enqueue only; actor guest work must execute on the receiving actor turn.
 */
final class ActorMutexFutureAuthorityTest {

    @Test
    void mutexHandoffResumesWaitingActorOnlyThroughItsOwnMailboxTurn() throws Exception {
        var mutex = OresMutex.shared(new int[]{0});
        var ownerReady = new CountDownLatch(1);
        var waiterQueued = new CountDownLatch(1);
        var resumed = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var ownerGuard = new AtomicReference<OresMutex.Guard<int[]>>();
        var waitingGuard = new AtomicReference<OresMutex.Guard<int[]>>();
        var waitingDomain = new AtomicReference<Object>();
        var ownerDomain = new AtomicReference<Object>();
        var callbackDomain = new AtomicReference<Object>();
        var waiterRef = new AtomicReference<ActorRuntime.ActorRef<String>>();

        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(),
                new ActorRuntime.DispatcherConfig(2, 4, 32))) {
            var waiter = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    if ("queue".equals(message)) {
                        waitingDomain.set(ActorRuntime.currentExecutionDomain());
                        var stage = mutex.lockAsync();
                        OresFuture<OresMutex.Guard<int[]>> awaited = OresFuture.from(stage);
                        // Runtime-only plumbing; never execute B's guest logic here.
                        awaited.whenCompleteRuntime((guard, problem) -> {
                            callbackDomain.set(ActorRuntime.currentExecutionDomain());
                            if (problem != null) {
                                failure.compareAndSet(null, problem);
                                resumed.countDown();
                                return;
                            }
                            waitingGuard.set(guard);
                            waiterRef.get().send("resume");
                        });
                        waiterQueued.countDown();
                    } else if ("resume".equals(message)) {
                        assertEquals(waitingDomain.get(), ActorRuntime.currentExecutionDomain(),
                                "actor B resumed with another actor's execution authority");
                        assertNotEquals(ownerDomain.get(), ActorRuntime.currentExecutionDomain(),
                                "actor B guard work ran in releasing actor A's domain");
                        var guard = waitingGuard.getAndSet(null);
                        assertNotNull(guard);
                        guard.value()[0] = 71;
                        guard.release();
                        resumed.countDown();
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                    waiterQueued.countDown();
                    resumed.countDown();
                }
            });
            waiterRef.set(waiter);

            var owner = runtime.<String>spawnShared(() -> (message, context) -> {
                try {
                    if ("lock".equals(message)) {
                        ownerDomain.set(ActorRuntime.currentExecutionDomain());
                        ownerGuard.set(mutex.lockAsync().join());
                        ownerReady.countDown();
                    } else if ("release".equals(message)) {
                        ownerGuard.getAndSet(null).release();
                    }
                } catch (Throwable problem) {
                    failure.compareAndSet(null, problem);
                    ownerReady.countDown();
                    resumed.countDown();
                }
            });

            owner.send("lock");
            assertTrue(ownerReady.await(3, TimeUnit.SECONDS), "actor A never took mutex");
            assertNull(failure.get());
            waiter.send("queue");
            assertTrue(waiterQueued.await(3, TimeUnit.SECONDS), "actor B never queued mutex");
            assertNull(failure.get());
            owner.send("release");
            assertTrue(resumed.await(4, TimeUnit.SECONDS), "actor B did not resume from mailbox");
            assertNull(failure.get(), String.valueOf(failure.get()));
            assertNotNull(callbackDomain.get(), "runtime completion plumbing was never notified");
            var observed = mutex.lockFor(Duration.ofSeconds(2)).orElseThrow();
            try {
                assertEquals(71, observed.value()[0]);
            } finally {
                observed.release();
            }
        }
    }
}
