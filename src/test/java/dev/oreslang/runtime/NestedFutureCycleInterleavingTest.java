package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Controlled scheduler interleavings for pending Future dependency accounting.
 *
 * <p>Coordination uses latches rather than sleeps or assumptions about carrier
 * scheduling order. Timeout bounds are test deadlock guards, not race timing.</p>
 */
final class NestedFutureCycleInterleavingTest {
    private static final long TIMEOUT_SECONDS = 5;

    @Test
    void simultaneousCrossSchedulerCycleRegistrationFailsWithoutRetainedEdges()
            throws Exception {
        CountDownLatch bothInsideFirstTurn = new CountDownLatch(2);
        CountDownLatch releaseBoth = new CountDownLatch(1);
        AtomicReferenceArray<OresFuture<Integer>> owners = new AtomicReferenceArray<>(2);

        try (OresScheduler first = new OresScheduler(1);
             OresScheduler second = new OresScheduler(1)) {
            OresScheduler[] schedulers = { first, second };

            for (int i = 0; i < schedulers.length; i++) {
                final int index = i;
                owners.set(i, schedulers[i].start(resume -> {
                    if (!resume.initial()) {
                        if (resume.failure() != null) {
                            throw new IllegalStateException(
                                    "cyclic upstream dependency failed", resume.failure());
                        }
                        return OresScheduler.done((Integer) resume.value());
                    }
                    bothInsideFirstTurn.countDown();
                    if (!releaseBoth.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test did not release first turns");
                    }
                    return OresScheduler.await(owners.get(1 - index));
                }));
            }

            assertTrue(bothInsideFirstTurn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "both scheduler turns must reach the barrier");
            releaseBoth.countDown();

            ExecutionException a = assertThrows(ExecutionException.class,
                    () -> owners.get(0).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            ExecutionException b = assertThrows(ExecutionException.class,
                    () -> owners.get(1).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(containsCycleDiagnostic(a) || containsCycleDiagnostic(b),
                    "one of the tasks must detect the actual dependency cycle");
            for (int i = 0; i < 2; i++) {
                assertFalse(OresScheduler.hasAwaitDependency(owners.get(i)),
                        "terminal tasks cannot retain global await graph edges");
                assertEquals(0, owners.get(i).pendingRuntimeWaiterCount(),
                        "terminal tasks cannot retain runtime observers");
            }
        } finally {
            releaseBoth.countDown();
        }
    }

    @Test
    void cancellationWhileGuestTurnRunsCannotInstallLateAwaitEdge()
            throws Exception {
        CountDownLatch insideTurn = new CountDownLatch(1);
        CountDownLatch releaseTurn = new CountDownLatch(1);
        OresFuture<Integer> pendingProducer = new OresFuture<>();

        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> task = scheduler.start(resume -> {
                insideTurn.countDown();
                if (!releaseTurn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test did not release guest turn");
                }
                return OresScheduler.await(pendingProducer);
            });

            assertTrue(insideTurn.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    "cancellation must occur while the task owns a guest turn");
            assertTrue(task.cancel(false));
            assertTrue(task.isCancelled());
            releaseTurn.countDown();

            // Closing the owned scheduler waits for its carrier to unwind,
            // so these checks do not depend on arbitrary sleep intervals.
            scheduler.close();
            assertFalse(OresScheduler.hasAwaitDependency(task));
            assertEquals(0, pendingProducer.pendingRuntimeWaiterCount());
            assertFalse(pendingProducer.isCancelled(),
                    "cancelling a consumer cannot cancel an aliased producer");
        } finally {
            releaseTurn.countDown();
        }
    }

    @Test
    void simultaneousFourSchedulerCycleCleansEveryDependency() throws Exception {
        final int length = 4;
        CountDownLatch allEntered = new CountDownLatch(length);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReferenceArray<OresFuture<Integer>> owners =
                new AtomicReferenceArray<>(length);
        OresScheduler[] schedulers = new OresScheduler[length];
        try {
            for (int i = 0; i < length; i++) {
                schedulers[i] = new OresScheduler(1);
            }
            for (int i = 0; i < length; i++) {
                final int index = i;
                owners.set(i, schedulers[i].start(resume -> {
                    if (resume.initial()) {
                        allEntered.countDown();
                        if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("four-way cycle barrier expired");
                        }
                        return OresScheduler.await(owners.get((index + 1) % length));
                    }
                    if (resume.failure() != null) {
                        throw new IllegalStateException("upstream cycle failed",
                                resume.failure());
                    }
                    return OresScheduler.done((Integer) resume.value());
                }));
            }
            assertTrue(allEntered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            release.countDown();
            int actualCycleDetections = 0;
            for (int i = 0; i < length; i++) {
                final int index = i;
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> owners.get(index).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                if (containsCycleDiagnostic(failure)) actualCycleDetections++;
            }
            assertTrue(actualCycleDetections >= 1,
                    "the actual dependency cycle must be diagnosed");
            for (int i = 0; i < length; i++) {
                assertFalse(OresScheduler.hasAwaitDependency(owners.get(i)),
                        "terminated four-way cycle retained a graph edge");
                assertEquals(0, owners.get(i).pendingRuntimeWaiterCount(),
                        "terminated four-way cycle retained a waiter");
            }
        } finally {
            release.countDown();
            for (OresScheduler scheduler : schedulers) {
                if (scheduler != null) scheduler.close();
            }
        }
    }

    @Test
    void cancellingSharedConsumerDetachesWithoutRevokingOtherConsumer()
            throws Exception {
        OresFuture<Integer> producer = new OresFuture<>();
        try (ControlledScheduler firstScheduler = new ControlledScheduler();
             ControlledScheduler secondScheduler = new ControlledScheduler()) {
            OresFuture<Integer> first = firstScheduler.start(resume -> {
                if (resume.initial()) return OresScheduler.await(producer);
                if (resume.failure() != null) throw new IllegalStateException(resume.failure());
                return OresScheduler.done((Integer) resume.value());
            });
            OresFuture<Integer> second = secondScheduler.start(resume -> {
                if (resume.initial()) return OresScheduler.await(producer);
                if (resume.failure() != null) throw new IllegalStateException(resume.failure());
                return OresScheduler.done((Integer) resume.value());
            });
            // Each FIFO carrier fence runs after its task's first turn has
            // installed the await registration. No polling or timing guess.
            firstScheduler.awaitInitialTurn();
            secondScheduler.awaitInitialTurn();
            assertEquals(2, producer.pendingRuntimeWaiterCount());
            assertTrue(OresScheduler.hasAwaitDependency(first));
            assertTrue(OresScheduler.hasAwaitDependency(second));
            assertTrue(first.cancel(false));
            assertFalse(OresScheduler.hasAwaitDependency(first));
            assertEquals(1, producer.pendingRuntimeWaiterCount(),
                    "cancelling one consumer must only release its own observer");
            assertFalse(producer.isCancelled());
            assertTrue(producer.completeFromRuntime(37));
            assertEquals(37, second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertTrue(first.isCancelled());
            assertFalse(OresScheduler.hasAwaitDependency(second));
            assertEquals(0, producer.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void producerSettlementRacingConsumerCancellationNeverResurrectsObserver()
            throws Exception {
        try (ControlledScheduler scheduler = new ControlledScheduler()) {
            for (int trial = 0; trial < 32; trial++) {
                OresFuture<Integer> producer = new OresFuture<>();
                OresFuture<Integer> consumer = scheduler.start(resume -> {
                    if (resume.initial()) return OresScheduler.await(producer);
                    if (resume.failure() != null) {
                        throw new IllegalStateException("producer failed", resume.failure());
                    }
                    return OresScheduler.done((Integer) resume.value());
                });
                scheduler.awaitInitialTurn();
                assertEquals(1, producer.pendingRuntimeWaiterCount());

                CountDownLatch race = new CountDownLatch(1);
                CountDownLatch bothFinished = new CountDownLatch(2);
                java.util.concurrent.atomic.AtomicBoolean sourceSettled =
                        new java.util.concurrent.atomic.AtomicBoolean();
                Thread producerThread = Thread.ofPlatform().start(() -> {
                    try {
                        if (!race.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return;
                        sourceSettled.set(producer.completeFromRuntime(81));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        bothFinished.countDown();
                    }
                });
                Thread cancellingThread = Thread.ofPlatform().start(() -> {
                    try {
                        if (!race.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return;
                        consumer.cancel(false);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        bothFinished.countDown();
                    }
                });
                race.countDown();
                assertTrue(bothFinished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "racing host threads must both finish");
                producerThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
                cancellingThread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
                assertFalse(producerThread.isAlive());
                assertFalse(cancellingThread.isAlive());
                assertTrue(sourceSettled.get(),
                        "the independent producer must settle exactly once");
                try {
                    assertEquals(81, consumer.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
                    assertFalse(consumer.isCancelled());
                } catch (java.util.concurrent.CancellationException cancelled) {
                    assertTrue(consumer.isCancelled());
                }
                assertFalse(producer.isCancelled(),
                        "consumer cancellation must not reach the producer");
                assertEquals(0, producer.pendingRuntimeWaiterCount(),
                        "race must not retain a producer observer");
                assertFalse(OresScheduler.hasAwaitDependency(consumer),
                        "race must not retain an await graph edge");
            }
        }
    }

    /**
     * One FIFO carrier per scheduler. Submitting a fence after start() is a
     * deterministic proof that the prior first turn has returned from armAwait,
     * unlike sleeping/spinning until a queue count happens to become visible.
     */
    private static final class ControlledScheduler implements AutoCloseable {
        private final ExecutorService carrier = Executors.newSingleThreadExecutor();
        private final OresScheduler scheduler =
                OresScheduler.runtimeOwned("nested-future-interleaving", 1, carrier);

        <T> OresFuture<T> start(OresScheduler.Task<T> task) {
            return scheduler.start(task);
        }

        void awaitInitialTurn() throws Exception {
            carrier.submit(() -> { }).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            try {
                scheduler.close();
            } finally {
                carrier.shutdownNow();
                assertTrue(carrier.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        "controlled test carrier must terminate");
            }
        }
    }

    private static boolean containsCycleDiagnostic(Throwable failure) {
        for (Throwable cursor = failure; cursor != null; cursor = cursor.getCause()) {
            if (String.valueOf(cursor.getMessage()).contains(
                    "cyclic Future await dependency detected")) {
                return true;
            }
        }
        return false;
    }
}
