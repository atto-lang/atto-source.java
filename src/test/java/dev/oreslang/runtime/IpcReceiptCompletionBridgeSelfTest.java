package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Host-only completion bridge tests for the owner-bound receipt lifecycle. */
public final class IpcReceiptCompletionBridgeSelfTest {
    private IpcReceiptCompletionBridgeSelfTest() { }
    private static int checks;

    private record Bound(
            IpcReceiptCompletionBridge bridge,
            Object owner,
            IpcReceiptCorrelation.Envelope epoch,
            long generation) { }

    private static void check(boolean value) {
        ++checks;
        if (!value) throw new AssertionError("IPC receipt completion bridge invariant failed");
    }

    private static void denied(Runnable operation) {
        try {
            operation.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
            ++checks;
            return;
        }
        throw new AssertionError("invalid IPC bridge transition succeeded");
    }

    private static IpcReceiptCorrelation.Envelope env(long sequence, String session) {
        return new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", session,
                "a".repeat(64), 1, 2, 3, 4, sequence, sequence);
    }

    private static Bound bind(int capacity, long generation, String session) {
        var epoch = env(1, session);
        var bridge = new IpcReceiptCompletionBridge(capacity);
        Object owner = new Object();
        bridge.claimPhysicalTransport(owner, epoch, generation);
        return new Bound(bridge, owner, epoch, generation);
    }

    private static IpcReceiptCompletionBridge.VerifiedCompletion apply(
            Bound bound,
            IpcReceiptCorrelation.Envelope message,
            IpcReceiptCorrelation.Stage stage) {
        return bound.bridge().applyVerified(
                bound.owner(), message, bound.generation(), stage);
    }

    public static void main(String[] ignored) throws Exception {
        // Admission and dispatch commitment are one operation. Terminal
        // publication is two-phase so dependent callbacks never run under the
        // bridge or transport receive gate.
        {
            var bound = bind(2, 33, "atomic");
            var first = bound.epoch();
            var future = bound.bridge().admitAndCommit(
                    bound.owner(), first, bound.generation());
            check(bound.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(1, 1)));
            denied(() -> bound.bridge().admitAndCommit(
                    bound.owner(), first, bound.generation()));
            denied(() -> bound.bridge().admitAndCommit(
                    new Object(), first, bound.generation()));
            denied(() -> bound.bridge().admitAndCommit(
                    bound.owner(), first, bound.generation() + 1));

            var ack = apply(
                    bound, first, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            check(bound.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(1, 0)));
            check(!future.toCompletableFuture().isDone());
            ack.publish();
            check(!future.toCompletableFuture().isDone());
            denied(() -> apply(
                    bound, first, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED));

            var terminal = apply(
                    bound, first, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(bound.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            check(!future.toCompletableFuture().isDone());
            terminal.publish();
            check(future.toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            denied(terminal::publish);
            denied(() -> apply(
                    bound, first, IpcReceiptCorrelation.Stage.HANDLER_FAILED));
            check(bound.bridge().invariant());
        }

        // Dependent callbacks may synchronously reenter diagnostics, but
        // publication happens after the bridge gate has been released.
        {
            var bound = bind(1, 34, "callback");
            var future = bound.bridge().admitAndCommit(
                    bound.owner(), bound.epoch(), bound.generation());
            AtomicInteger callbacks = new AtomicInteger();
            future.thenAccept(stage -> {
                check(stage == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
                check(bound.bridge().counters().equals(
                        new IpcReceiptCorrelation.Counters(0, 0)));
                check(bound.bridge().invariant());
                callbacks.incrementAndGet();
            });
            apply(bound, bound.epoch(),
                    IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED).publish();
            apply(bound, bound.epoch(),
                    IpcReceiptCorrelation.Stage.HANDLER_COMMITTED).publish();
            check(callbacks.get() == 1);
        }

        // Physical loss after dispatch commitment is indeterminate and can
        // only be asserted by the exact bound owner/generation/epoch.
        {
            var bound = bind(1, 44, "loss");
            var future = bound.bridge().admitAndCommit(
                    bound.owner(), bound.epoch(), bound.generation());
            denied(() -> bound.bridge().transportLost(
                    new Object(), bound.epoch(), bound.generation()));
            denied(() -> bound.bridge().transportLost(
                    bound.owner(), bound.epoch(), bound.generation() + 1));
            var foreignEpoch = new IpcReceiptCorrelation.Envelope(
                    "tenant", "sender", "worker", "loss",
                    "a".repeat(64), 1, 2, 4, 5, 2, 2);
            denied(() -> bound.bridge().transportLost(
                    bound.owner(), foreignEpoch, bound.generation()));
            check(bound.bridge().transportLost(
                    bound.owner(), bound.epoch(), bound.generation()) == 1);
            check(future.toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(bound.bridge().transportLost(
                    bound.owner(), bound.epoch(), bound.generation()) == 0);
            check(bound.bridge().invariant());
        }

        // REMOTE_ACCEPTED releases transport credit but remains unresolved;
        // physical loss still produces OUTCOME_UNKNOWN.
        {
            var bound = bind(1, 45, "accepted-loss");
            var future = bound.bridge().admitAndCommit(
                    bound.owner(), bound.epoch(), bound.generation());
            apply(bound, bound.epoch(),
                    IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED).publish();
            check(bound.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(1, 0)));
            check(bound.bridge().transportLost(
                    bound.owner(), bound.epoch(), bound.generation()) == 1);
            check(future.toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            denied(() -> apply(
                    bound, bound.epoch(),
                    IpcReceiptCorrelation.Stage.HANDLER_COMMITTED));
            check(bound.bridge().invariant());
        }

        // Capacity failure after one committed admission cannot leak a second
        // host completion or mutate the existing receipt.
        {
            var bound = bind(1, 46, "capacity");
            var first = bound.epoch();
            var second = new IpcReceiptCorrelation.Envelope(
                    "tenant", "sender", "worker", "capacity",
                    "a".repeat(64), 1, 2, 3, 4, 2, 2);
            var firstFuture = bound.bridge().admitAndCommit(
                    bound.owner(), first, bound.generation());
            denied(() -> bound.bridge().admitAndCommit(
                    bound.owner(), second, bound.generation()));
            check(bound.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(1, 1)));
            check(bound.bridge().transportLost(
                    bound.owner(), first, bound.generation()) == 1);
            check(firstFuture.toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(bound.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
        }

        // Terminal authenticated receipt and physical loss race on one
        // correlation. Exactly one terminal outcome is published.
        AtomicInteger receiptWins = new AtomicInteger();
        AtomicInteger lossWins = new AtomicInteger();
        for (int trial = 0; trial < 512; ++trial) {
            var bound = bind(1, 1000L + trial, "terminal-race");
            var message = bound.epoch();
            var future = bound.bridge().admitAndCommit(
                    bound.owner(), message, bound.generation());
            apply(bound, message,
                    IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED).publish();

            CountDownLatch fire = new CountDownLatch(1);
            AtomicInteger committed = new AtomicInteger();
            AtomicInteger lost = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();

            Thread receipt = new Thread(() -> {
                try {
                    fire.await();
                    var terminal = apply(
                            bound, message,
                            IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
                    terminal.publish();
                    committed.incrementAndGet();
                } catch (IllegalArgumentException expectedLossWinner) {
                    // Transport loss removed the correlation first.
                } catch (Throwable bad) {
                    failure.compareAndSet(null, bad);
                }
            }, "ipc-bridge-terminal-race-receipt");

            Thread loss = new Thread(() -> {
                try {
                    fire.await();
                    lost.addAndGet(bound.bridge().transportLost(
                            bound.owner(), message, bound.generation()));
                } catch (Throwable bad) {
                    failure.compareAndSet(null, bad);
                }
            }, "ipc-bridge-terminal-race-loss");

            receipt.start();
            loss.start();
            fire.countDown();
            receipt.join();
            loss.join();

            check(failure.get() == null);
            check(committed.get() + lost.get() == 1);
            var stage = future.toCompletableFuture().join();
            if (stage == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED) {
                receiptWins.incrementAndGet();
                check(committed.get() == 1);
                check(lost.get() == 0);
            } else {
                lossWins.incrementAndGet();
                check(stage == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
                check(committed.get() == 0);
                check(lost.get() == 1);
            }
            check(bound.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            check(bound.bridge().invariant());
        }

        check(receiptWins.get() + lossWins.get() == 512);
        System.out.println("JAVA_IPC_COMPLETION_BRIDGE_PASS checks=" + checks
                + " races=512 receiptWins=" + receiptWins.get()
                + " lossWins=" + lossWins.get()
                + " owner_bound=true");
    }
}
