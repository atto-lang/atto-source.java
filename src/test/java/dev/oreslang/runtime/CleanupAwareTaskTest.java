package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Cancellation request != guest cleanup completion != physical retirement. */
@Timeout(20)
final class CleanupAwareTaskTest {
    @Test void suspendedSourceRunsCleanupBeforeCancellationIsPublished() throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        OresFuture<Void> cleanup = new OresFuture<>();
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        AtomicInteger stages = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> task = scheduler.start(new OresScheduler.CleanupAwareTask<>() {
                @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                    if (stages.getAndIncrement() == 0) {
                        assertTrue(resume.initial());
                        waiting.countDown();
                        return OresScheduler.await(source);
                    }
                    if (stages.get() == 2) {
                        assertInstanceOf(CancellationException.class, resume.failure());
                        assertFalse(resume.initial());
                        cleanupEntered.countDown();
                        return OresScheduler.await(cleanup);
                    }
                    assertNull(resume.failure());
                    return OresScheduler.done(42);
                }
            });
            assertTrue(waiting.await(3, TimeUnit.SECONDS));
            assertTrue(task.cancel(false));
            assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS));
            assertFalse(task.isDone(), "cancelled result must wait for defer cleanup");
            assertFalse(source.isCancelled(), "source Future is borrowed");
            assertEquals(0, source.pendingRuntimeWaiterCount());
            assertFalse(task.cancel(false), "second cancellation must not restart cleanup");
            cleanup.completeFromRuntime(null);
            assertThrows(CancellationException.class,
                    () -> task.get(3, TimeUnit.SECONDS));
            assertTrue(task.isCancelled());
            assertEquals(3, stages.get());
            assertEquals(0, cleanup.pendingRuntimeWaiterCount());
        }
    }

    @Test void runningTaskCannotEnterCleanupOnExternalCarrier() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        CountDownLatch cleanup = new CountDownLatch(1);
        AtomicInteger resumes = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(2)) {
            OresFuture<Integer> future = scheduler.start(new OresScheduler.CleanupAwareTask<>() {
                @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                    if (resumes.getAndIncrement() == 0) {
                        entered.countDown();
                        try {
                            assertTrue(unblock.await(4, TimeUnit.SECONDS));
                        } catch (InterruptedException ex) {
                            throw new IllegalStateException(ex);
                        }
                        return OresScheduler.cooperate();
                    }
                    assertInstanceOf(CancellationException.class, resume.failure());
                    cleanup.countDown();
                    return OresScheduler.done(77);
                }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            try {
                assertTrue(future.cancel(false));
                assertFalse(future.isDone());
                assertEquals(1L, cleanup.getCount(),
                        "cleanup must wait for physically running turn");
            } finally {
                unblock.countDown();
            }
            assertTrue(cleanup.await(4, TimeUnit.SECONDS));
            assertThrows(CancellationException.class,
                    () -> future.get(4, TimeUnit.SECONDS));
            assertEquals(2, resumes.get());
        }
    }

    @Test void cleanupCanSuspendSeveralTimesWithoutRepeatedCancellation() throws Exception {
        OresFuture<Integer> input = new OresFuture<>();
        OresFuture<Integer> cleanupA = new OresFuture<>();
        OresFuture<Integer> cleanupB = new OresFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger state = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> task = scheduler.start(new OresScheduler.CleanupAwareTask<>() {
                @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume r) {
                    return switch (state.getAndIncrement()) {
                        case 0 -> { started.countDown(); yield OresScheduler.await(input); }
                        case 1 -> {
                            assertInstanceOf(CancellationException.class, r.failure());
                            yield OresScheduler.await(cleanupA);
                        }
                        case 2 -> {
                            assertEquals(20, r.value());
                            assertNull(r.failure());
                            yield OresScheduler.await(cleanupB);
                        }
                        case 3 -> {
                            assertEquals(30, r.value());
                            assertNull(r.failure());
                            yield OresScheduler.done(99);
                        }
                        default -> throw new AssertionError("duplicate cleanup resume");
                    };
                }
            });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTrue(task.cancel(false));
            assertFalse(task.isDone());
            cleanupA.completeFromRuntime(20);
            cleanupB.completeFromRuntime(30);
            assertThrows(CancellationException.class,
                    () -> task.get(4, TimeUnit.SECONDS));
            assertEquals(4, state.get());
        }
    }

    @Test void cancellationBeforeFirstExecutionNeverRunsGuestTask() throws Exception {
        CountDownLatch busy = new CountDownLatch(1);
        CountDownLatch resumeBusy = new CountDownLatch(1);
        AtomicBoolean started = new AtomicBoolean();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Void> blocker = scheduler.start(resume -> {
                busy.countDown();
                try { assertTrue(resumeBusy.await(4, TimeUnit.SECONDS)); }
                catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                return OresScheduler.done(null);
            });
            assertTrue(busy.await(3, TimeUnit.SECONDS));
            OresFuture<Integer> future = scheduler.start(
                    (OresScheduler.CleanupAwareTask<Integer>) resume -> {
                        started.set(true);
                        return OresScheduler.done(1);
                    });
            assertTrue(future.cancel(false));
            resumeBusy.countDown();
            assertThrows(CancellationException.class,
                    () -> future.get(4, TimeUnit.SECONDS));
            assertNull(blocker.get(4, TimeUnit.SECONDS));
            assertFalse(started.get());
        }
    }

    @Test void fatalCleanupErrorCannotBeHiddenByCancellation() throws Exception {
        OresFuture<Integer> waiting = new OresFuture<>();
        CountDownLatch entered = new CountDownLatch(1);
        AssertionError fatal = new AssertionError("panic during cleanup");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> future = scheduler.start(new OresScheduler.CleanupAwareTask<>() {
                private int phase;
                @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume r) {
                    if (phase++ == 0) {
                        entered.countDown();
                        return OresScheduler.await(waiting);
                    }
                    assertInstanceOf(CancellationException.class, r.failure());
                    throw fatal;
                }
            });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertTrue(future.cancel(false));
            var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> future.get(4, TimeUnit.SECONDS));
            assertSame(fatal, failed.getCause());
            assertFalse(future.isCancelled());
        }
    }

    @Test void guestThrownCancellationExceptionWithoutOwnerRequestIsFailure() throws Exception {
        CancellationException thrown = new CancellationException("guest thrown");
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> future = scheduler.start(
                    (OresScheduler.CleanupAwareTask<Integer>) r -> { throw thrown; });
            var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> future.get(3, TimeUnit.SECONDS));
            assertSame(thrown, failed.getCause());
            assertFalse(future.isCancelled());
        }
    }

    @Test void schedulerCloseDuringSuspendedCleanupMustNotIssueQuiescenceReceipt()
            throws Exception {
        OresFuture<Integer> source = new OresFuture<>();
        OresFuture<Void> neverFinishingCleanup = new OresFuture<>();
        CountDownLatch sourceEntered = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        AtomicInteger cleanRetirements = new AtomicInteger();
        OresScheduler scheduler = new OresScheduler(1);
        try {
            OresFuture<Integer> future = scheduler.start(
                    new OresScheduler.CleanupAwareTask<Integer>() {
                        private int phase;
                        @Override public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume r) {
                            if (phase++ == 0) {
                                sourceEntered.countDown();
                                return OresScheduler.await(source);
                            }
                            assertInstanceOf(CancellationException.class, r.failure());
                            cleanupEntered.countDown();
                            return OresScheduler.await(neverFinishingCleanup);
                        }
                    }, cleanRetirements::incrementAndGet);
            assertTrue(sourceEntered.await(3, TimeUnit.SECONDS));
            assertTrue(future.cancel(false));
            assertTrue(cleanupEntered.await(3, TimeUnit.SECONDS));
            assertFalse(future.isDone());
            scheduler.close();
            assertTrue(scheduler.hasIncompleteCleanup(),
                    "an incomplete source unwind must veto actor memory reclamation");
            var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> future.get(4, TimeUnit.SECONDS));
            assertInstanceOf(OresScheduler.CleanupIncompleteError.class, failed.getCause());
            assertEquals(0, cleanRetirements.get(),
                    "shutdown cannot equate stopped carrier with completed lexical cleanup");
            assertFalse(source.isCancelled());
            assertFalse(neverFinishingCleanup.isCancelled());
            assertEquals(0, neverFinishingCleanup.pendingRuntimeWaiterCount());
        } finally {
            scheduler.close();
        }
    }

    @Test void untrustedActorQuotaAdapterPreservesCleanupAwareCancellation()
            throws Exception {
        OresFuture<Integer> producer = new OresFuture<>();
        OresFuture<Integer> cleanupProducer = new OresFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch cleanupEntered = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<OresFuture<Integer>> ref =
                new java.util.concurrent.atomic.AtomicReference<>();
        // Untrusted actor factories must be stateless; host fixtures enter
        // through a static probe, never through captured lambda state.
        quotaProbe = new QuotaProbe(producer, cleanupProducer, started, cleanupEntered, ref);
        try (ActorRuntime runtime = new ActorRuntime(
                IsolatePolicy.developer(), new ActorRuntime.DispatcherConfig(1, 2, 64, 16))) {
            var actor = runtime.<String>spawnUntrusted(
                    context -> (message, turn) -> {
                        QuotaProbe probe = quotaProbe;
                        OresFuture<Integer> future =
                                turn.runtime().startActorTask(quotaTask(probe));
                        probe.ref().set(future);
                    });
            actor.send("run");
            assertTrue(started.await(4, TimeUnit.SECONDS));
            assertTrue(ref.get().cancel(false));
            assertTrue(cleanupEntered.await(4, TimeUnit.SECONDS),
                    "untrusted quota wrapper must preserve CleanupAwareTask");
            assertFalse(ref.get().isDone());
            cleanupProducer.completeFromRuntime(4);
            assertThrows(CancellationException.class,
                    () -> ref.get().get(4, TimeUnit.SECONDS));
            actor.stop();
            assertTrue(actor.awaitTermination(4, TimeUnit.SECONDS));
            assertFalse(producer.isCancelled());
        } finally {
            quotaProbe = null;
        }
    }

    private record QuotaProbe(
            OresFuture<Integer> producer,
            OresFuture<Integer> cleanupProducer,
            CountDownLatch started,
            CountDownLatch cleanupEntered,
            java.util.concurrent.atomic.AtomicReference<OresFuture<Integer>> ref) { }

    /** Static so the untrusted factory lambda captures no test instance. */
    private static OresScheduler.CleanupAwareTask<Integer> quotaTask(QuotaProbe probe) {
        return new OresScheduler.CleanupAwareTask<Integer>() {
            private int phase;
            @Override public OresScheduler.Step<Integer> resume(
                    OresScheduler.Resume resumed) {
                assertEquals(ActorRuntime.ActorKind.UNTRUSTED,
                        ActorRuntime.currentActorKind());
                switch (phase++) {
                    case 0:
                        probe.started().countDown();
                        return OresScheduler.await(probe.producer());
                    case 1:
                        // Injected cancellation starts the cleanup lane.
                        assertInstanceOf(CancellationException.class, resumed.failure());
                        probe.cleanupEntered().countDown();
                        return OresScheduler.await(probe.cleanupProducer());
                    default:
                        // The cleanup's own await resumes normally.
                        assertEquals(Integer.valueOf(4), resumed.value());
                        assertTrue(OresScheduler.cancellationCleanupTurn());
                        return OresScheduler.done(null);
                }
            }
        };
    }

    private static volatile QuotaProbe quotaProbe;

    @Test void endlesslyCooperatingCleanupIsQuarantinedAfterFiniteBudget()
            throws Exception {
        OresFuture<Integer> producer = new OresFuture<>();
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger cleanupTurns = new AtomicInteger();
        AtomicInteger releaseReceipts = new AtomicInteger();
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> task = scheduler.start(
                    new OresScheduler.CleanupAwareTask<Integer>() {
                        private boolean initial = true;
                        @Override public OresScheduler.Step<Integer> resume(
                                OresScheduler.Resume r) {
                            if (initial) {
                                initial = false;
                                started.countDown();
                                return OresScheduler.await(producer);
                            }
                            cleanupTurns.incrementAndGet();
                            return OresScheduler.cooperate();
                        }
                    }, releaseReceipts::incrementAndGet);
            assertTrue(started.await(4, TimeUnit.SECONDS));
            assertTrue(task.cancel(false));
            var failed = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> task.get(12, TimeUnit.SECONDS));
            assertInstanceOf(OresScheduler.CleanupIncompleteError.class, failed.getCause());
            assertTrue(scheduler.hasIncompleteCleanup());
            assertEquals(0, releaseReceipts.get(),
                    "a runaway defer cannot produce a safe reclamation receipt");
            assertTrue(cleanupTurns.get() > 0);
            assertFalse(producer.isCancelled());
        }
    }
}
