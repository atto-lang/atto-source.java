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
    void throwingErrorObserverDoesNotStrandAdmission() throws Exception {
        ActorPhysicalTurnGate gate = new ActorPhysicalTurnGate(1, 1);
        gate.dispatch(() -> { },
                ignored -> { throw new RejectedExecutionException("underlying pool closed"); },
                failure -> { throw new AssertionError("observer failed too"); });
        assertEquals(0, gate.queued());
        try (var workers = Executors.newSingleThreadExecutor()) {
            CountDownLatch finished = new CountDownLatch(1);
            gate.dispatch(finished::countDown, workers::execute,
                    failure -> fail("a released permit must remain usable"));
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
        gate.close();
    }

    @Test
    void inlineCallbackFailureMustNotDoubleReleaseThePermit() throws Exception {
        ActorPhysicalTurnGate gate = new ActorPhysicalTurnGate(1, 1);
        AtomicInteger rejected = new AtomicInteger();
        assertThrows(IllegalArgumentException.class,
                () -> gate.dispatch(
                        () -> { throw new IllegalArgumentException("guest turn threw"); },
                        Runnable::run, error -> rejected.incrementAndGet()));
        assertEquals(0, rejected.get(), "inline guest failure was not admission failure");
        try (var workers = Executors.newSingleThreadExecutor()) {
            CountDownLatch finished = new CountDownLatch(1);
            gate.dispatch(finished::countDown, workers::execute,
                    error -> fail(error));
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
        gate.close();
    }

    @Test
    void executorEnqueuesThenThrowsCannotRunStaleActorOrReleaseNextPermit() throws Exception {
        ActorPhysicalTurnGate gate = new ActorPhysicalTurnGate(1, 3);
        java.util.concurrent.atomic.AtomicReference<Runnable> late =
                new java.util.concurrent.atomic.AtomicReference<>();
        AtomicInteger staleRuns = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        gate.dispatch(staleRuns::incrementAndGet, runnable -> {
            late.set(runnable);
            throw new RejectedExecutionException("queued but rejected");
        }, failure -> rejected.incrementAndGet());
        assertEquals(1, rejected.get());
        assertEquals(0, staleRuns.get());

        CountDownLatch occupied = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        try (var workers = Executors.newSingleThreadExecutor()) {
            gate.dispatch(() -> {
                occupied.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completed.countDown();
                }
            }, workers::execute, failure -> fail(failure));
            assertTrue(occupied.await(5, TimeUnit.SECONDS));
            late.get().run();
            assertEquals(0, staleRuns.get());
            assertEquals(1, gate.peak());
            assertEquals(0, gate.queued());
            release.countDown();
            assertTrue(completed.await(5, TimeUnit.SECONDS));
        }
        gate.close();
    }

    @Test
    void transferInProgressCannotStartGuestWorkAfterGateCloses() throws Exception {
        ActorPhysicalTurnGate gate = new ActorPhysicalTurnGate(1, 8);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1);
        CountDownLatch beforeSecondSubmission = new CountDownLatch(1);
        CountDownLatch allowSecondSubmission = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        AtomicInteger executedAfterClose = new AtomicInteger();
        AtomicInteger rejectedOnClose = new AtomicInteger();
        try (var workers = Executors.newSingleThreadExecutor()) {
            gate.dispatch(() -> {
                started.countDown();
                try {
                    assertTrue(finishFirst.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }, workers::execute, failure -> fail(failure));
            assertTrue(started.await(5, TimeUnit.SECONDS));

            gate.dispatch(() -> {
                executedAfterClose.incrementAndGet();
                finished.countDown();
            }, command -> {
                beforeSecondSubmission.countDown();
                try {
                    assertTrue(allowSecondSubmission.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                workers.execute(command);
            }, failure -> {
                assertInstanceOf(RejectedExecutionException.class, failure);
                rejectedOnClose.incrementAndGet();
                finished.countDown();
            });

            finishFirst.countDown();
            assertTrue(beforeSecondSubmission.await(5, TimeUnit.SECONDS));
            gate.close();
            allowSecondSubmission.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        } finally {
            gate.close();
            finishFirst.countDown();
            allowSecondSubmission.countDown();
        }
        assertEquals(0, executedAfterClose.get(),
                "queued callback may not enter guest code after gate closure");
        assertEquals(1, rejectedOnClose.get(),
                "transferred-but-not-started callback must be rejected exactly once");
    }

    @Test
    void pinnedTurnWaitsForPhysicalCompletionEvenAfterCancellationAndGateClose() throws Exception {
        ActorPhysicalTurnGate gate = new ActorPhysicalTurnGate(1, 2);
        var region = new ReusableLongStateRegion(1);
        region.insert(17);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch releasePhysicalTurn = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            gate.dispatchPinned(region, () -> {
                entered.countDown();
                try {
                    assertTrue(releasePhysicalTurn.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }, worker::execute, error -> fail(error));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, region.activePins());
            region.beginRetire();
            gate.close();
            assertFalse(region.tryClose(), "STOP is not a physical completion");
            releasePhysicalTurn.countDown();
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(0, region.activePins());
            assertTrue(region.tryClose());
        } finally {
            releasePhysicalTurn.countDown();
            gate.close();
        }
    }

    @Test
    void pinnedPendingTurnIsReleasedOnClosureWithoutGuestExecution() throws Exception {
        ActorPhysicalTurnGate gate = new ActorPhysicalTurnGate(1, 2);
        var region = new ReusableLongStateRegion(2);
        region.insert(1);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        try (var workers = Executors.newSingleThreadExecutor()) {
            gate.dispatchPinned(region, () -> {
                firstEntered.countDown();
                try {
                    assertTrue(finishFirst.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }, workers::execute, failure -> fail(failure));
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            gate.dispatchPinned(region, unexpected::incrementAndGet,
                    workers::execute, failure -> rejected.incrementAndGet());
            assertEquals(2, region.activePins());
            region.beginRetire();
            assertFalse(region.tryClose());
            gate.close();
            assertEquals(1, rejected.get());
            assertEquals(1, region.activePins());
            assertEquals(0, unexpected.get());
            assertFalse(region.tryClose());
            finishFirst.countDown();
            workers.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(0, region.activePins());
            assertTrue(region.tryClose());
        } finally {
            finishFirst.countDown();
            gate.close();
        }
    }

    @Test
    void pinnedSubmissionRejectsAndInlineGuestFailureDoNotLeakOrDoubleRelease() {
        ActorPhysicalTurnGate gate = new ActorPhysicalTurnGate(1, 1);
        try (var region = new ReusableLongStateRegion(1)) {
            AtomicInteger rejected = new AtomicInteger();
            assertThrows(IllegalArgumentException.class,
                    () -> gate.dispatchPinned(region,
                            () -> { throw new IllegalArgumentException("guest"); },
                            Runnable::run, ignored -> rejected.incrementAndGet()));
            assertEquals(0, rejected.get());
            assertEquals(0, region.activePins());

            java.util.concurrent.atomic.AtomicReference<Runnable> late =
                    new java.util.concurrent.atomic.AtomicReference<>();
            gate.dispatchPinned(region, () -> fail("rejected runnable executed"),
                    runnable -> {
                        late.set(runnable);
                        throw new RejectedExecutionException("executor rejected after enqueue");
                    }, ignored -> rejected.incrementAndGet());
            assertEquals(1, rejected.get());
            assertEquals(0, region.activePins());
            late.get().run(); // stale replay cannot release someone else's pin
            assertEquals(0, region.activePins());

            gate.close();
            assertThrows(RejectedExecutionException.class,
                    () -> gate.dispatchPinned(region, () -> fail("closed"), Runnable::run,
                            ignored -> fail("must reject before invocation")));
            assertEquals(0, region.activePins());
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
