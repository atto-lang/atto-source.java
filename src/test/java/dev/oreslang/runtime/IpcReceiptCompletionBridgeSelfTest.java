package dev.oreslang.runtime;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Host completion protocol self-test; not Oreslang guest Future lowering. */
public final class IpcReceiptCompletionBridgeSelfTest {
    private IpcReceiptCompletionBridgeSelfTest() { }
    private static int checks;

    private static IpcReceiptCorrelation.Envelope env(long seq, String session) {
        return new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", session, "a".repeat(64),
                1, 2, 3, 4, seq, seq);
    }
    private static void check(boolean truth) {
        ++checks;
        if (!truth) throw new AssertionError("host completion correlation violated");
    }
    private static void denied(Runnable attempt) {
        try { attempt.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) {
            ++checks;
            return;
        }
        throw new AssertionError("invalid receipt transition succeeded");
    }
    private static IpcReceiptCorrelation.Stage result(
            CompletionStage<IpcReceiptCorrelation.Stage> stage) {
        return stage.toCompletableFuture().join();
    }

    public static void main(String[] args) throws InterruptedException {
        var gate = new IpcReceiptCompletionBridge(3);
        var one = env(1, "old");
        var two = env(2, "new");
        var first = gate.admit(one);
        var second = gate.admit(two);
        check(!first.toCompletableFuture().isDone());
        gate.acceptVerified(one, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        check(!first.toCompletableFuture().isDone());
        gate.acceptVerified(two, IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE);
        check(result(second) == IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE);
        check(gate.transportLost("old") == 1);
        check(result(first) == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(gate.transportLost("old") == 0);
        check(gate.invariant());
        denied(() -> gate.acceptVerified(one, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED));

        var onTerminal = new IpcReceiptCompletionBridge(1);
        var three = env(1, "session");
        var completion = onTerminal.admit(three);
        AtomicInteger observed = new AtomicInteger();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        completion.thenAccept(state -> {
            // If CompletableFuture.complete() runs inside the correlation
            // monitor, the probe thread cannot re-enter before the callback
            // returns. Ensure a separate carrier actually observes readiness.
            CountDownLatch probeDone = new CountDownLatch(1);
            Thread probe = new Thread(() -> {
                try {
                    if (state == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED
                            && onTerminal.invariant()) observed.incrementAndGet();
                } catch (Throwable error) {
                    callbackFailure.compareAndSet(null, error);
                } finally {
                    probeDone.countDown();
                }
            });
            probe.start();
            try {
                if (!probeDone.await(2, TimeUnit.SECONDS)) {
                    callbackFailure.compareAndSet(null,
                            new AssertionError("callback blocked on correlation lock"));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                callbackFailure.compareAndSet(null, interrupted);
            }
        });
        onTerminal.acceptVerified(three, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        onTerminal.acceptVerified(three, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
        check(result(completion) == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
        check(callbackFailure.get() == null);
        check(observed.get() == 1);
        check(onTerminal.invariant());

        for (int trial = 0; trial < 512; ++trial) {
            var racing = new IpcReceiptCompletionBridge(1);
            var message = env(1, "session");
            var future = racing.admit(message);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger lost = new AtomicInteger();
            AtomicReference<Throwable> problem = new AtomicReference<>();
            Thread ack = new Thread(() -> {
                try {
                    start.await();
                    racing.acceptVerified(message, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
                } catch (IllegalArgumentException expectedStale) {
                    // EOF won the race.
                } catch (Throwable e) { problem.compareAndSet(null, e); }
            });
            Thread eof = new Thread(() -> {
                try {
                    start.await();
                    lost.addAndGet(racing.transportLost("session"));
                } catch (Throwable e) { problem.compareAndSet(null, e); }
            });
            ack.start(); eof.start(); start.countDown();
            ack.join(); eof.join();
            check(problem.get() == null);
            check(lost.get() == 1);
            check(result(future) == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(racing.invariant());
            check(racing.counters().pending() == 0);
        }
        System.out.println("JAVA_IPC_HOST_COMPLETIONS_PASS assertions=" + checks
                + " races=512");
    }
}
