package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
final class ActorPhysicalTurnGateTest {
    @Test
    void saturatedGateDoesNotOccupyAnotherWorker() throws Exception {
        ActorPhysicalTurnGate gate = new ActorPhysicalTurnGate(1, 4);
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch completed = new CountDownLatch(2);
            AtomicInteger active = new AtomicInteger();
            AtomicInteger maximum = new AtomicInteger();
            java.util.function.Consumer<Runnable> submit = pool::execute;
            java.util.function.Consumer<Throwable> rejected = failure -> {
                throw new AssertionError("unexpected admission rejection", failure);
            };

            gate.dispatch(() -> {
                int n = active.incrementAndGet();
                maximum.accumulateAndGet(n, Math::max);
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("test timed out");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    active.decrementAndGet();
                    completed.countDown();
                }
            }, submit, rejected);
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            gate.dispatch(() -> {
                int n = active.incrementAndGet();
                maximum.accumulateAndGet(n, Math::max);
                active.decrementAndGet();
                completed.countDown();
            }, submit, rejected);
            assertEquals(1, gate.queued());
            assertEquals(1, gate.peak());
            release.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertEquals(1, maximum.get());
            assertEquals(0, gate.queued());
        } finally {
            gate.close();
        }
    }

    @Test
    void closeRevokesPendingWithoutExecutingGuestTask() throws Exception {
        ActorPhysicalTurnGate gate = new ActorPhysicalTurnGate(1, 1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch rejected = new CountDownLatch(1);
            AtomicInteger executed = new AtomicInteger();
            gate.dispatch(() -> {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, pool::execute, failure -> fail(failure));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            gate.dispatch(executed::incrementAndGet, pool::execute,
                    failure -> {
                        assertInstanceOf(RejectedExecutionException.class, failure);
                        rejected.countDown();
                    });
            assertThrows(RejectedExecutionException.class, () ->
                    gate.dispatch(() -> {}, pool::execute, failure -> fail(failure)));
            gate.close();
            assertTrue(rejected.await(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals(0, executed.get());
        } finally {
            gate.close();
        }
    }

    @Test
    void actorRuntimeGateSerializesDifferentSchedulerDomains() throws Exception {
        String key = "ores.runtime.actor.max-active-turns";
        String prior = System.getProperty(key);
        System.setProperty(key, "1");
        try {
            var config = new ActorRuntime.DispatcherConfig(2, 2, 1, 32);
            try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
                CountDownLatch sharedEntered = new CountDownLatch(1);
                CountDownLatch release = new CountDownLatch(1);
                CountDownLatch privateEntered = new CountDownLatch(1);
                ActorRuntime.ActorRef<Integer> shared = runtime.spawnSharedTrusted(
                        factory -> (mail, turn) -> {
                            sharedEntered.countDown();
                            try {
                                release.await(5, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });
                ActorRuntime.ActorRef<Integer> local = runtime.spawnPrivateTrusted(
                        factory -> (mail, turn) -> privateEntered.countDown());
                shared.send(1);
                assertTrue(sharedEntered.await(5, TimeUnit.SECONDS));
                local.send(1);
                assertFalse(privateEntered.await(75, TimeUnit.MILLISECONDS),
                        "separate domains must not over-admit a second turn");
                release.countDown();
                assertTrue(privateEntered.await(5, TimeUnit.SECONDS));
            }
        } finally {
            if (prior == null) System.clearProperty(key);
            else System.setProperty(key, prior);
        }
    }
}
