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
            process.close();
            assertEquals(1, process.undelivered(), "repeat close double-counted failure");
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
