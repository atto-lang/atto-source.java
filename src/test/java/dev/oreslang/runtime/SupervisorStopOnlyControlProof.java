package dev.oreslang.runtime;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Focused executable test of the production CONTROL process. This exercises
 * the real host supervisor lane; it is NOT full source guest lowering.
 *
 * Public main permits the same cases to run in a private exact-blob source
 * mirror without duplicating the assertions or carrying the entire Graal
 * compiler dependency tree.
 */
public final class SupervisorStopOnlyControlProof {
    private SupervisorStopOnlyControlProof() { }

    private static void check(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private static SupervisorFailureControl.Notice failure(
            ActorRuntime.SupervisorId supervisor, ActorRuntime.ActorGroupId group) {
        return new SupervisorFailureControl.Notice(
                supervisor, group, ActorRuntime.ActorId.create(), Optional.empty(),
                SupervisorFailureControl.Kind.TERMINAL_FAILURE,
                "panic", "controlled test failure");
    }

    private static void await(CountDownLatch latch, String reason) throws Exception {
        check(latch.await(6, TimeUnit.SECONDS), reason);
    }

    /**
     * Replayed scheduler tasks must not grant an extra decision. The same
     * immutable notice can be delivered as two independent CONTROL turns,
     * each with its own monotonic turn generation.
     */
    private static void replayAndGenerationFence() throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(3)) {
            Executor replay = task -> {
                workers.execute(task);
                workers.execute(task);
            };
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            var notice = failure(supervisor, group);
            var decisions = new AtomicInteger();
            var policies = new AtomicInteger();
            var generations = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
            var complete = new CountDownLatch(2);
            var process = new SupervisorProcess(supervisor, group, replay, 2,
                    f -> {
                        policies.incrementAndGet();
                        return CompletableFuture.completedFuture(SupervisorProcess.Directive.STOP);
                    },
                    resolution -> {
                        decisions.incrementAndGet();
                        generations.add(resolution.turnGeneration());
                        complete.countDown();
                    });
            process.accept(notice);
            process.accept(notice);
            await(complete, "two CONTROL decisions did not finish");
            workers.submit(() -> { }).get(6, TimeUnit.SECONDS);
            check(decisions.get() == 2, "scheduler replay duplicated STOP effect");
            check(policies.get() == 2, "scheduler replay duplicated policy callback");
            check(java.util.List.copyOf(generations).equals(java.util.List.of(1L, 2L)),
                    "wrong monotonic STOP turn identity");
            check(process.resolved() == 2 && process.admitted() == 2,
                    "incorrect successful CONTROL accounting");
            check(process.undelivered() == 0 && process.healthy(),
                    "successful STOP cannot be marked lost");
            process.close();
        }
    }

    /** Async host STOP is not committed until the actual receipt arrives. */
    private static void awaitHostStopAndRejectForeignControl() throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            var ack = new CompletableFuture<Void>();
            var effectStarted = new CountDownLatch(1);
            var committed = new CountDownLatch(1);
            var effects = new AtomicInteger();
            var commits = new AtomicInteger();
            var completingThread = new AtomicReference<Thread>();
            var committingThread = new AtomicReference<Thread>();
            var process = new SupervisorProcess(supervisor, group, workers, 1,
                    f -> CompletableFuture.completedFuture(SupervisorProcess.Directive.STOP),
                    resolution -> {
                        effects.incrementAndGet();
                        effectStarted.countDown();
                        return ack;
                    },
                    resolution -> {
                        committingThread.set(Thread.currentThread());
                        commits.incrementAndGet();
                        committed.countDown();
                    },
                    resolution -> { throw new AssertionError("successful host STOP marked uncertain"); });
            process.accept(failure(supervisor, group));
            await(effectStarted, "host STOP effect did not start");
            check(process.resolved() == 0 && process.callbackActive(),
                    "pending host acknowledgement incorrectly released CONTROL lease");
            check(commits.get() == 0, "STOP committed before actual host ack");
            boolean refused = false;
            try {
                process.accept(failure(ActorRuntime.SupervisorId.create(), group));
            } catch (SecurityException expected) {
                refused = true;
            }
            check(refused, "foreign supervisor authority was accepted");
            refused = false;
            try {
                process.accept(failure(supervisor, group));
            } catch (IllegalStateException expected) {
                refused = true;
            }
            check(refused, "bounded CONTROL mailbox admitted excess notice");
            Thread foreign = new Thread(() -> {
                completingThread.set(Thread.currentThread());
                ack.complete(null);
            }, "foreign-host-ack");
            foreign.start();
            foreign.join(6000);
            check(!foreign.isAlive(), "foreign acknowledgment stalled");
            await(committed, "CONTROL continuation did not commit host receipt");
            check(committingThread.get() != completingThread.get(),
                    "foreign completion thread executed CONTROL finalization");
            check(effects.get() == 1 && commits.get() == 1 && process.resolved() == 1,
                    "duplicate or missing authoritative host STOP");
            check(process.undelivered() == 1 && !process.healthy(),
                    "rejected capacity must be observable and fail closed");
            process.close();
        }
    }

    /** A failed/ambiguous host effect is quarantined, never retried. */
    private static void failedHostStopIsNotRetried() throws Exception {
        try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            var ack = new CompletableFuture<Void>();
            var invoked = new CountDownLatch(1);
            var classified = new CountDownLatch(1);
            var effects = new AtomicInteger();
            var successes = new AtomicInteger();
            var failures = new AtomicInteger();
            var process = new SupervisorProcess(supervisor, group, workers, 2,
                    f -> CompletableFuture.completedFuture(SupervisorProcess.Directive.STOP),
                    resolution -> {
                        effects.incrementAndGet();
                        invoked.countDown();
                        return ack;
                    },
                    resolution -> successes.incrementAndGet(),
                    resolution -> {
                        failures.incrementAndGet();
                        classified.countDown();
                    });
            process.accept(failure(supervisor, group));
            await(invoked, "host STOP did not start");
            ack.completeExceptionally(new IllegalStateException("ambiguous host STOP"));
            await(classified, "failed host STOP not classified on CONTROL");
            workers.submit(() -> { }).get(6, TimeUnit.SECONDS);
            check(effects.get() == 1 && failures.get() == 1 && successes.get() == 0,
                    "ambiguous STOP was retried or acknowledged");
            check(process.resolved() == 0 && process.undelivered() == 1,
                    "ambiguous host STOP must not be reported resolved");
            check(!process.healthy() && process.failure().isPresent(),
                    "ambiguous STOP must fail the supervisor");
            process.close();
        }
    }

    public static void runAll() throws Exception {
        replayAndGenerationFence();
        awaitHostStopAndRejectForeignControl();
        failedHostStopIsNotRetried();
    }

    public static void main(String[] args) throws Exception {
        runAll();
        System.out.println("JAVA_SUPERVISOR_CONTROL=PASS cases=3");
    }
}
