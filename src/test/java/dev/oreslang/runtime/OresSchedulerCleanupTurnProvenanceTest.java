package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class OresSchedulerCleanupTurnProvenanceTest {
    @Test
    void onlySchedulerMintedLiveCancellationDispatchHasCleanupAuthority() throws Exception {
        ArrayDeque<Runnable> actorLane = new ArrayDeque<>();
        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "owner", 1, actorLane::add);
             OresScheduler impostor = OresScheduler.runtimeOwned(
                "other", 1, command -> { throw new AssertionError("no work"); })) {
            OresFuture<Integer> gate = new OresFuture<>();
            OresFuture<Integer> cleanup = new OresFuture<>();
            AtomicInteger turns = new AtomicInteger();
            OresFuture<Integer> result = scheduler.start(
                    (OresScheduler.CleanupAwareTask<Integer>) event -> {
                        int pc = turns.getAndIncrement();
                        if (pc == 0) return OresScheduler.await(gate);
                        if (pc == 1) {
                            assertInstanceOf(CancellationException.class, event.failure());
                            return OresScheduler.await(cleanup);
                        }
                        return OresScheduler.done(99);
                    });

            Runnable initial = actorLane.removeFirst();
            assertInstanceOf(OresScheduler.CleanupTurn.class, initial);
            var initialTurn = (OresScheduler.CleanupTurn) initial;
            assertTrue(initialTurn.ownedBy(scheduler));
            assertFalse(initialTurn.authorizedBy(scheduler));
            assertFalse(initialTurn.eligibleForStoppedCleanup(scheduler),
                    "queued initial task has no lexical stack to unwind");
            assertFalse(initialTurn.authorizedBy(impostor));
            initial.run();
            assertEquals(1, turns.get());

            assertTrue(result.cancel(false));
            Runnable resumed = actorLane.removeFirst();
            assertInstanceOf(OresScheduler.CleanupTurn.class, resumed);
            var cleanupTurn = (OresScheduler.CleanupTurn) resumed;
            assertTrue(cleanupTurn.ownedBy(scheduler));
            assertTrue(cleanupTurn.eligibleForStoppedCleanup(scheduler),
                    "suspended source task is a legitimate STOP cleanup candidate");
            assertFalse(cleanupTurn.eligibleForStoppedCleanup(impostor));
            assertTrue(cleanupTurn.authorizedBy(scheduler));
            assertFalse(cleanupTurn.authorizedBy(impostor),
                    "ownership is exact scheduler identity, not matching names");
            assertFalse(initialTurn.authorizedBy(impostor));
            resumed.run();
            assertFalse(result.isDone(), "cleanup still awaits the second producer");
            assertEquals(2, turns.get());

            cleanup.completeFromRuntime(1);
            Runnable finish = actorLane.removeFirst();
            assertTrue(((OresScheduler.CleanupTurn) finish).authorizedBy(scheduler));
            finish.run();
            assertThrows(CancellationException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertFalse(cleanupTurn.authorizedBy(scheduler),
                    "stale retained envelope loses authorization after terminal publication");
            assertEquals(3, turns.get());
            assertEquals(0, gate.pendingRuntimeWaiterCount());
        }
    }

    @Test
    void taskCancelledBeforeFirstGuestTurnNeverMintsCleanupAuthorization() throws Exception {
        ArrayDeque<Runnable> actorLane = new ArrayDeque<>();
        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "owner", 1, actorLane::add)) {
            AtomicInteger guestTurns = new AtomicInteger();
            OresFuture<Integer> result = scheduler.start(
                    (OresScheduler.CleanupAwareTask<Integer>) event -> {
                        guestTurns.incrementAndGet();
                        return OresScheduler.done(9);
                    });
            var initial = (OresScheduler.CleanupTurn) actorLane.removeFirst();
            assertTrue(result.cancel(false));
            assertFalse(initial.authorizedBy(scheduler));
            initial.run();
            assertThrows(CancellationException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertEquals(0, guestTurns.get());
        }
    }
    @Test
    void activeGuestTurnDoesNotGainCleanupOutputAuthorityBeforeCancellationInjection()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean beforeRequest = new AtomicBoolean(true);
        AtomicBoolean afterRequestButBeforeUnwind = new AtomicBoolean(true);
        AtomicBoolean terminalResumeAuthorized = new AtomicBoolean(false);
        try (OresScheduler scheduler = new OresScheduler(1)) {
            OresFuture<Integer> source = new OresFuture<>();
            OresFuture<Integer> result = scheduler.start(
                    (OresScheduler.CleanupAwareTask<Integer>) event -> {
                        if (event.initial()) {
                            beforeRequest.set(OresScheduler.currentTaskInCleanup());
                            entered.countDown();
                            if (!release.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("source turn did not release");
                            }
                            afterRequestButBeforeUnwind.set(
                                    OresScheduler.currentTaskInCleanup());
                            return OresScheduler.await(source);
                        }
                        assertInstanceOf(CancellationException.class, event.failure());
                        terminalResumeAuthorized.set(
                                OresScheduler.currentTaskInCleanup());
                        return OresScheduler.done(1);
                    });
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(result.cancel(false));
                assertFalse(result.isDone());
            } finally {
                release.countDown();
            }
            assertThrows(CancellationException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertFalse(beforeRequest.get(), "initial guest execution is not cleanup");
            assertFalse(afterRequestButBeforeUnwind.get(),
                    "a racing STOP cannot authorize ordinary still-running guest code");
            assertTrue(terminalResumeAuthorized.get(),
                    "authenticated cancellation resume can run lexical cleanup");
            assertFalse(source.isCancelled(), "cancellation does not take producer ownership");
        }
    }

    @Test
    void queuedProducerSuccessIsReplacedByAuthenticatedCancellationBeforeResume()
            throws Exception {
        ArrayDeque<Runnable> actorLane = new ArrayDeque<>();
        try (OresScheduler scheduler = OresScheduler.runtimeOwned(
                "owner", 1, actorLane::add)) {
            OresFuture<Integer> producer = new OresFuture<>();
            AtomicInteger guestTurns = new AtomicInteger();
            AtomicBoolean observedCleanupAuthority = new AtomicBoolean();
            OresFuture<Integer> result = scheduler.start(
                    (OresScheduler.CleanupAwareTask<Integer>) event -> {
                        int turn = guestTurns.incrementAndGet();
                        if (turn == 1) {
                            assertTrue(event.initial());
                            return OresScheduler.await(producer);
                        }
                        assertEquals(2, turn, "no duplicate resume may enter guest code");
                        assertInstanceOf(CancellationException.class, event.failure(),
                                "STOP cancellation must supersede the queued producer result");
                        observedCleanupAuthority.set(OresScheduler.currentTaskInCleanup());
                        return OresScheduler.done(20);
                    });

            var initial = (OresScheduler.CleanupTurn) actorLane.removeFirst();
            initial.run();
            producer.completeFromRuntime(7);
            assertEquals(1, actorLane.size(),
                    "settled producer queues one continuation, never runs it inline");

            var queued = (OresScheduler.CleanupTurn) actorLane.removeFirst();
            assertTrue(queued.eligibleForStoppedCleanup(scheduler));
            assertFalse(queued.authorizedBy(scheduler),
                    "producer completion alone cannot mint STOP authority");
            assertTrue(result.cancel(false));
            assertTrue(queued.authorizedBy(scheduler),
                    "cancelled live task may resume on its owned cleanup lane");
            assertFalse(result.isDone(), "terminal publication waits for guest unwind");

            queued.run();
            assertEquals(2, guestTurns.get());
            assertTrue(observedCleanupAuthority.get());
            assertThrows(CancellationException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertFalse(producer.isCancelled(),
                    "STOP must not steal an independently owned producer");
            assertFalse(queued.eligibleForStoppedCleanup(scheduler),
                    "a terminal task cannot consume post-STOP continuation slots");
            assertFalse(queued.authorizedBy(scheduler),
                    "terminal publication revokes all stale cancellation envelopes");
            assertTrue(actorLane.isEmpty(), "no continuation may be dispatched twice");
        }
    }

}
