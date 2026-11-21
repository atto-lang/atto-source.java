package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Audits lifecycle side effects against concurrent supervisor shutdown. */
@Timeout(20)
final class SupervisorDecisionCommitFenceTest {
    private static SupervisorFailureControl.Notice notice(
            ActorRuntime.SupervisorId s, ActorRuntime.ActorGroupId g) {
        return new SupervisorFailureControl.Notice(
                s, g, ActorRuntime.ActorId.create(), Optional.empty(),
                SupervisorFailureControl.Kind.TERMINAL_FAILURE,
                "Terminal", "code-37");
    }


    @Test
    void duplicatedExecutorCallbacksCannotStartSamePolicyTwice() throws Exception {
        try (ExecutorService carriers = Executors.newFixedThreadPool(3)) {
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            // A broken enqueue-only scheduler replays each admitted task.
            java.util.concurrent.Executor replaying = turn -> {
                carriers.execute(turn);
                carriers.execute(turn);
            };
            AtomicInteger policies = new AtomicInteger();
            AtomicInteger effects = new AtomicInteger();
            java.util.concurrent.ConcurrentLinkedQueue<Long> turnKeys =
                    new java.util.concurrent.ConcurrentLinkedQueue<>();
            CountDownLatch completed = new CountDownLatch(2);
            SupervisorProcess process = new SupervisorProcess(
                    supervisor, group, replaying, 2,
                    n -> {
                        policies.incrementAndGet();
                        return CompletableFuture.completedFuture(SupervisorProcess.Directive.STOP);
                    },
                    resolution -> {
                        turnKeys.add(resolution.turnGeneration());
                        effects.incrementAndGet();
                        completed.countDown();
                    });
            // Reuse the identical immutable object to ensure continuation
            // identity comes from the turn generation, not notice identity.
            var sameNotice = notice(supervisor, group);
            process.accept(sameNotice);
            process.accept(sameNotice);
            assertTrue(completed.await(5, TimeUnit.SECONDS),
                    "valid CONTROL turns were not both committed");
            carriers.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertEquals(2, policies.get(),
                    "executor replay started the same logical policy twice");
            assertEquals(2, effects.get(),
                    "executor replay duplicated an authoritative side effect");
            assertEquals(java.util.List.of(1L, 2L), java.util.List.copyOf(turnKeys),
                    "separate CONTROL turns must have unique monotonic commit keys");
            assertEquals(2, process.resolved());
            assertEquals(0, process.queued());
            assertEquals(0, process.undelivered());
            assertFalse(process.callbackActive());
            assertTrue(process.failure().isEmpty(),
                    "executor replay poisoned otherwise completed supervisor");
            assertTrue(process.healthy(),
                    "all committed turns must leave a healthy supervisor");
            process.close();
        }
    }


    @Test
    void replayedCompletionAfterFinalTurnDoesNotPoisonHealthySupervisor() {
        var supervisor = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        java.util.ArrayDeque<Runnable> tasks = new java.util.ArrayDeque<>();
        java.util.concurrent.Executor replaying = turn -> {
            tasks.addLast(turn);
            tasks.addLast(turn);
        };
        CompletableFuture<SupervisorProcess.Directive> first = new CompletableFuture<>();
        CompletableFuture<SupervisorProcess.Directive> second = new CompletableFuture<>();
        AtomicInteger policies = new AtomicInteger();
        AtomicInteger committed = new AtomicInteger();
        var process = new SupervisorProcess(
                supervisor, group, replaying, 2,
                notice -> policies.incrementAndGet() == 1 ? first : second,
                result -> committed.incrementAndGet());
        var identical = notice(supervisor, group);
        process.accept(identical);
        process.accept(identical);

        tasks.removeFirst().run();
        tasks.removeFirst().run(); // duplicate begin(1)
        assertEquals(1, policies.get());
        first.complete(SupervisorProcess.Directive.STOP);
        tasks.removeFirst().run(); // finish(1), schedules begin(2)
        tasks.removeFirst().run(); // stale finish(1)
        tasks.removeFirst().run();
        tasks.removeFirst().run(); // duplicate begin(2)
        assertEquals(2, policies.get());
        second.complete(SupervisorProcess.Directive.STOP);
        tasks.removeFirst().run(); // finish(2)
        tasks.removeFirst().run(); // same generation, but already committed

        assertEquals(2, committed.get());
        assertEquals(2, process.resolved());
        assertEquals(0, process.undelivered());
        assertEquals(0, process.queued());
        assertFalse(process.callbackActive());
        assertTrue(process.failure().isEmpty(), "stale final completion poisoned supervisor");
        assertTrue(process.healthy(), "completed replay must not fail the CONTROL lane");
    }

    @Test
    void processAdmissionFailsWhenControlDispatcherRunsInline() {
        var supervisor = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        AtomicInteger guestPolicies = new AtomicInteger();
        SupervisorProcess process = new SupervisorProcess(
                supervisor, group, Runnable::run, 2,
                n -> {
                    guestPolicies.incrementAndGet();
                    return CompletableFuture.completedFuture(SupervisorProcess.Directive.STOP);
                },
                r -> fail("inline CONTROL dispatcher must not commit lifecycle effects"));
        assertThrows(IllegalStateException.class,
                () -> process.accept(notice(supervisor, group)));
        assertEquals(0, guestPolicies.get());
        assertEquals(1, process.admitted(), "the enqueued notice was counted");
        assertEquals(1, process.undelivered(), "failed dispatch lost accounting");
        assertEquals(0, process.queued(), "failed dispatch retained notice metadata");
        assertFalse(process.callbackActive());
        assertFalse(process.healthy());
    }

    @Test
    void closeWaitsForAdmittedLifecycleDecisionToComplete() throws Exception {
        try (ExecutorService carrier = Executors.newFixedThreadPool(2)) {
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            CountDownLatch committed = new CountDownLatch(1);
            CountDownLatch releaseCommit = new CountDownLatch(1);
            CountDownLatch closeReturned = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            AtomicBoolean sinkFailed = new AtomicBoolean();
            SupervisorProcess process = new SupervisorProcess(
                    supervisor, group, carrier, 4,
                    n -> CompletableFuture.completedFuture(
                            SupervisorProcess.Directive.STOP),
                    r -> {
                        calls.incrementAndGet();
                        committed.countDown();
                        try {
                            if (!releaseCommit.await(5, TimeUnit.SECONDS)) {
                                sinkFailed.set(true);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            sinkFailed.set(true);
                        }
                    });
            try {
                process.accept(notice(supervisor, group));
                assertTrue(committed.await(5, TimeUnit.SECONDS),
                        "decision was never admitted onto CONTROL executor");
                Thread closing = new Thread(() -> {
                    process.close();
                    closeReturned.countDown();
                }, "concurrent-supervisor-close");
                closing.start();
                assertFalse(closeReturned.await(100, TimeUnit.MILLISECONDS),
                        "close escaped while an admitted lifecycle effect ran");
                releaseCommit.countDown();
                assertTrue(closeReturned.await(5, TimeUnit.SECONDS));
                closing.join(1000);
                assertFalse(sinkFailed.get());
                assertEquals(1, calls.get(), "duplicate decision during close");
                assertEquals(1, process.resolved());
                assertEquals(0, process.undelivered(),
                        "already committed lifecycle effect was misclassified");
            } finally {
                releaseCommit.countDown();
                process.close();
            }
        }
    }

    @Test
    void lifecycleDecisionCanCloseItsOwnSupervisorWithoutLosingCommit() throws Exception {
        try (ExecutorService carrier = Executors.newFixedThreadPool(2)) {
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            java.util.concurrent.atomic.AtomicReference<SupervisorProcess> ref =
                    new java.util.concurrent.atomic.AtomicReference<>();
            CountDownLatch closedInSink = new CountDownLatch(1);
            AtomicInteger applied = new AtomicInteger();
            SupervisorProcess process = new SupervisorProcess(
                    supervisor, group, carrier, 2,
                    n -> CompletableFuture.completedFuture(SupervisorProcess.Directive.STOP),
                    result -> {
                        applied.incrementAndGet();
                        ref.get().close();
                        closedInSink.countDown();
                    });
            ref.set(process);
            process.accept(notice(supervisor, group));
            assertTrue(closedInSink.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (process.resolved() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(1, applied.get());
            assertEquals(1, process.resolved(),
                    "self-close lost an already-applied lifecycle effect");
            assertEquals(0, process.undelivered(),
                    "self-close incorrectly counted the applied decision as lost");
            assertFalse(process.healthy(), "closed supervisor must reject further events");
        }
    }


    @Test
    void selfCloseThenSinkExceptionFinishesShutdownWithoutFalseCommit() throws Exception {
        try (ExecutorService carrier = Executors.newFixedThreadPool(2)) {
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            java.util.concurrent.atomic.AtomicReference<SupervisorProcess> ref =
                    new java.util.concurrent.atomic.AtomicReference<>();
            CountDownLatch entered = new CountDownLatch(1);
            AtomicInteger attempts = new AtomicInteger();
            SupervisorProcess process = new SupervisorProcess(
                    supervisor, group, carrier, 2,
                    n -> CompletableFuture.completedFuture(SupervisorProcess.Directive.STOP),
                    result -> {
                        attempts.incrementAndGet();
                        ref.get().close();
                        entered.countDown();
                        throw new IllegalStateException("ambiguous host side effect");
                    });
            ref.set(process);
            process.accept(notice(supervisor, group));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (process.failure().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(process.failure().orElse("").contains("ambiguous host side effect"));
            assertEquals(1, attempts.get(), "a failed sink must not be retried");
            assertEquals(0, process.resolved(), "throwing sink cannot be reported committed");
            assertEquals(1, process.undelivered(), "unknown lifecycle effect must be accounted");
            assertEquals(0, process.queued(), "reentrant close must clear failed mailbox");
            assertFalse(process.callbackActive(), "failed close retained a callback lease");
            assertFalse(process.healthy());
            assertThrows(IllegalStateException.class,
                    () -> process.accept(notice(supervisor, group)));
            // The additional notice rejected after failure must be counted
            // independently; only close() itself is required to be idempotent.
            long accounted = process.undelivered();
            assertEquals(2, accounted, "rejected post-failure notice was not accounted");
            process.close();
            assertEquals(accounted, process.undelivered(),
                    "repeat close double-counted an already recorded failure");
        }
    }

    @Test
    void selfClosePreservesPendingNoticeLossAccounting() throws Exception {
        try (ExecutorService carrier = Executors.newFixedThreadPool(2)) {
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            CompletableFuture<SupervisorProcess.Directive> first = new CompletableFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<SupervisorProcess> ref =
                    new java.util.concurrent.atomic.AtomicReference<>();
            AtomicInteger effects = new AtomicInteger();
            SupervisorProcess process = new SupervisorProcess(
                    supervisor, group, carrier, 2,
                    n -> {
                        entered.countDown();
                        return first;
                    },
                    result -> {
                        effects.incrementAndGet();
                        ref.get().close();
                    });
            ref.set(process);
            process.accept(notice(supervisor, group));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            process.accept(notice(supervisor, group));
            first.complete(SupervisorProcess.Directive.STOP);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (process.resolved() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertEquals(1, effects.get());
            assertEquals(1, process.resolved());
            assertEquals(1, process.undelivered(), "pending second notice was lost silently");
            assertEquals(0, process.queued());
            assertFalse(process.healthy());
        }
    }


    @Test
    void duplicateCompletionCannotResolveTheNextTurnUsingSameNoticeObject() throws Exception {
        // A nonconforming CompletionStage can invoke its callback twice.
        // Reusing the *same immutable notice instance* on the next turn must
        // not make the stale callback indistinguishable from a fresh lease.
        try (ExecutorService carrier = Executors.newSingleThreadExecutor()) {
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            var sharedNotice = notice(supervisor, group);
            AtomicInteger policyCalls = new AtomicInteger();
            AtomicInteger effects = new AtomicInteger();
            SupervisorProcess process = new SupervisorProcess(
                    supervisor, group, carrier, 3,
                    n -> {
                        if (policyCalls.incrementAndGet() == 1) {
                            return new CompletableFuture<SupervisorProcess.Directive>() {
                                @Override
                                public CompletableFuture<SupervisorProcess.Directive> whenComplete(
                                        java.util.function.BiConsumer<? super SupervisorProcess.Directive,
                                                ? super Throwable> action) {
                                    action.accept(SupervisorProcess.Directive.STOP, null);
                                    action.accept(SupervisorProcess.Directive.STOP, null);
                                    return this;
                                }
                            };
                        }
                        return CompletableFuture.completedFuture(SupervisorProcess.Directive.STOP);
                    }, r -> effects.incrementAndGet());
            process.accept(sharedNotice);
            process.accept(sharedNotice);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (process.resolved() != 2 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            carrier.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertEquals(2, policyCalls.get(), "duplicate completion stole next turn");
            assertEquals(2, effects.get(), "duplicate stage completion replayed a host effect");
            assertEquals(2, process.resolved());
            assertEquals(0, process.undelivered());
            assertTrue(process.healthy());
            process.close();
        }
    }

    @Test
    void handlerExceptionClearsTerminalMailboxAndReleasesLease() throws Exception {
        try (ExecutorService carrier = Executors.newFixedThreadPool(2)) {
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            SupervisorProcess process = new SupervisorProcess(
                    supervisor, group, carrier, 2,
                    n -> { throw new IllegalStateException("failed policy"); },
                    r -> fail("failed policy must not apply a lifecycle effect"));
            process.accept(notice(supervisor, group));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (process.failure().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(process.failure().orElse("").contains("failed policy"));
            assertEquals(1, process.undelivered());
            assertEquals(0, process.queued(), "failure retained unprocessed CONTROL notice");
            assertFalse(process.callbackActive(), "failure retained logical lease");
            assertFalse(process.healthy());
            process.close();
            assertEquals(1, process.undelivered(), "close counted failed notice again");
        }
    }

    @Test
    void reentrantCloseRejectsNewAdmissionBeforeCommitReturns() throws Exception {
        try (ExecutorService carrier = Executors.newSingleThreadExecutor()) {
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            java.util.concurrent.atomic.AtomicReference<SupervisorProcess> ref =
                    new java.util.concurrent.atomic.AtomicReference<>();
            AtomicBoolean refused = new AtomicBoolean();
            SupervisorProcess process = new SupervisorProcess(
                    supervisor, group, carrier, 2,
                    n -> CompletableFuture.completedFuture(SupervisorProcess.Directive.STOP),
                    r -> {
                        ref.get().close();
                        try {
                            ref.get().accept(notice(supervisor, group));
                        } catch (IllegalStateException expected) {
                            refused.set(true);
                        }
                    });
            ref.set(process);
            process.accept(notice(supervisor, group));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (process.resolved() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(1);
            }
            assertTrue(refused.get(), "reentrant close admitted another CONTROL notice");
            assertEquals(1, process.resolved());
            assertEquals(1, process.undelivered(), "rejected notice was not accounted");
            assertFalse(process.healthy());
            assertEquals(0, process.queued());
        }
    }

    @Test
    void closedSupervisorRejectsLateFutureCompletionWithoutDecision() throws Exception {
        try (ExecutorService carrier = Executors.newFixedThreadPool(2)) {
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            CompletableFuture<SupervisorProcess.Directive> future =
                    new CompletableFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            AtomicInteger calls = new AtomicInteger();
            SupervisorProcess process = new SupervisorProcess(
                    supervisor, group, carrier, 3,
                    n -> {
                        entered.countDown();
                        return future;
                    },
                    r -> calls.incrementAndGet());
            process.accept(notice(supervisor, group));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            process.close();
            future.complete(SupervisorProcess.Directive.RESTART);
            carrier.submit(() -> { }).get(5, TimeUnit.SECONDS);
            assertEquals(0, calls.get());
            assertEquals(0, process.resolved());
            assertEquals(1, process.undelivered());
            assertFalse(process.healthy());
        }
    }
}
