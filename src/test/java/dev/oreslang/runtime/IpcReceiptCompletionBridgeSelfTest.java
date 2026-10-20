package dev.oreslang.runtime;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Host completion protocol self-test; not Oreslang guest Future lowering. */
public final class IpcReceiptCompletionBridgeSelfTest {
    private IpcReceiptCompletionBridgeSelfTest() { }
    private static int checks;

    private record BoundBridge(
            IpcReceiptCompletionBridge bridge,
            Object owner,
            IpcReceiptCorrelation.Envelope epoch,
            long generation) {
        CompletionStage<IpcReceiptCorrelation.Stage> admit(
                IpcReceiptCorrelation.Envelope message) {
            return bridge.admitAndCommit(owner, message, generation);
        }

        void accept(
                IpcReceiptCorrelation.Envelope message,
                IpcReceiptCorrelation.Stage stage) {
            bridge.acceptVerified(owner, message, generation, stage);
        }

        int lost() {
            return bridge.transportLost(owner, epoch, generation);
        }
    }

    private static IpcReceiptCorrelation.Envelope env(long seq, String session) {
        return new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", session, "a".repeat(64),
                1, 2, 3, 4, seq, seq);
    }

    private static BoundBridge bound(
            int capacity,
            IpcReceiptCorrelation.Envelope epoch,
            long generation) {
        var bridge = new IpcReceiptCompletionBridge(capacity);
        Object owner = new Object();
        bridge.claimPhysicalTransport(owner, epoch, generation);
        return new BoundBridge(bridge, owner, epoch, generation);
    }

    private static void check(boolean truth) {
        ++checks;
        if (!truth) {
            throw new AssertionError("host completion correlation violated");
        }
    }

    private static void denied(Runnable attempt) {
        try {
            attempt.run();
        } catch (IllegalArgumentException | IllegalStateException expected) {
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
        // The bridge is host-only from initial physical binding onward.
        {
            var bridge = new IpcReceiptCompletionBridge(2);
            var epoch = env(1, "carrier-bridge");
            Object owner = new Object();
            RuntimeBlockingGuard.enterActorCarrier();
            try {
                denied(() -> bridge.claimPhysicalTransport(owner, epoch, 77));
                denied(bridge::counters);
                denied(bridge::invariant);
            } finally {
                RuntimeBlockingGuard.exitActorCarrier();
            }

            bridge.claimPhysicalTransport(owner, epoch, 77);
            RuntimeBlockingGuard.enterActorCarrier();
            try {
                denied(() -> bridge.admitAndCommit(owner, epoch, 77));
                denied(() -> bridge.acceptVerified(
                        owner, epoch, 77,
                        IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED));
                denied(() -> bridge.transportLost(owner, epoch, 77));
                denied(bridge::counters);
                denied(bridge::invariant);
            } finally {
                RuntimeBlockingGuard.exitActorCarrier();
            }
            check(bridge.counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            check(bridge.invariant());
        }

        // Physical binding is irreversible and exact over owner identity,
        // logical epoch and physical generation.
        {
            var epoch = env(1, "bound");
            var bridge = new IpcReceiptCompletionBridge(2);
            Object owner = new Object();
            Object foreignOwner = new Object();
            bridge.claimPhysicalTransport(owner, epoch, 33);
            denied(() -> bridge.claimPhysicalTransport(
                    foreignOwner, epoch, 34));

            var wrongEpoch = new IpcReceiptCorrelation.Envelope(
                    "tenant", "sender", "worker", "bound", "a".repeat(64),
                    1, 2, 4, 5, 1, 1);
            denied(() -> bridge.admitAndCommit(foreignOwner, epoch, 33));
            denied(() -> bridge.admitAndCommit(owner, epoch, 32));
            denied(() -> bridge.admitAndCommit(owner, wrongEpoch, 33));

            var future = bridge.admitAndCommit(owner, epoch, 33);
            denied(() -> bridge.admitAndCommit(owner, epoch, 33));
            denied(() -> bridge.acceptVerified(
                    foreignOwner, epoch, 33,
                    IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED));
            denied(() -> bridge.acceptVerified(
                    owner, epoch, 32,
                    IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED));
            denied(() -> bridge.acceptVerified(
                    owner, wrongEpoch, 33,
                    IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED));
            denied(() -> bridge.acceptVerified(
                    owner, epoch, 33,
                    IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN));
            check(!future.toCompletableFuture().isDone());

            bridge.acceptVerified(
                    owner, epoch, 33,
                    IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            bridge.acceptVerified(
                    owner, epoch, 33,
                    IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(result(future)
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(bridge.invariant());
        }

        // Bounded admission is still enforced after atomic dispatch commitment.
        {
            var firstMessage = env(1, "capacity");
            var secondMessage = env(2, "capacity");
            var transport = bound(1, firstMessage, 40);
            var first = transport.admit(firstMessage);
            denied(() -> transport.admit(secondMessage));
            check(!first.toCompletableFuture().isDone());
            check(transport.lost() == 1);
            check(result(first) == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(transport.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            check(transport.bridge().invariant());
        }

        // Completion callbacks execute outside the bridge gate and may safely
        // re-enter host diagnostics from another thread.
        {
            var message = env(1, "terminal-callback");
            var transport = bound(1, message, 50);
            var future = transport.admit(message);
            AtomicInteger observed = new AtomicInteger();
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            future.thenAccept(stage -> {
                CountDownLatch probeDone = new CountDownLatch(1);
                Thread probe = new Thread(() -> {
                    try {
                        if (stage == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED
                                && transport.bridge().invariant()) {
                            observed.incrementAndGet();
                        }
                    } catch (Throwable failure) {
                        callbackFailure.compareAndSet(null, failure);
                    } finally {
                        probeDone.countDown();
                    }
                }, "ipc-completion-reentry-probe");
                probe.start();
                try {
                    if (!probeDone.await(2, TimeUnit.SECONDS)) {
                        callbackFailure.compareAndSet(
                                null,
                                new AssertionError(
                                        "terminal callback held receipt bridge gate"));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    callbackFailure.compareAndSet(null, interrupted);
                }
            });

            transport.accept(
                    message, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            transport.accept(
                    message, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(result(future)
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(callbackFailure.get() == null);
            check(observed.get() == 1);
            check(transport.bridge().invariant());
        }

        // Transport loss after atomic dispatch commitment is always uncertain;
        // there is no longer an exposed LOCAL_ADMITTED/pre-dispatch bridge state.
        {
            var message = env(1, "loss");
            var transport = bound(1, message, 60);
            var future = transport.admit(message);
            denied(() -> transport.bridge().transportLost(
                    new Object(), message, 60));
            denied(() -> transport.bridge().transportLost(
                    transport.owner(), message, 61));
            check(transport.lost() == 1);
            check(result(future) == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            denied(() -> transport.admit(env(2, "loss")));
            check(transport.lost() == 0);
            check(transport.bridge().invariant());
            try {
                transport.accept(message, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
                throw new AssertionError("ACK accepted after physical transport loss");
            } catch (IllegalStateException expectedLoss) {
                check("IPC physical transport already lost".equals(
                        expectedLoss.getMessage()));
            }
        }

        // Textual session reuse is not authority. A route/capability upgrade
        // requires a fresh bridge/owner; the old physical bridge rejects it.
        {
            var previous = env(1, "reused");
            var latest = new IpcReceiptCorrelation.Envelope(
                    "tenant", "sender", "worker", "reused", "a".repeat(64),
                    1, 2, 4, 5, 2, 2);

            var oldTransport = bound(1, previous, 70);
            var oldFuture = oldTransport.admit(previous);
            denied(() -> oldTransport.admit(latest));
            check(oldTransport.lost() == 1);
            check(result(oldFuture)
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);

            var newTransport = bound(1, latest, 70);
            var newFuture = newTransport.admit(latest);
            newTransport.accept(
                    latest, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            newTransport.accept(
                    latest, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(result(newFuture)
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(oldTransport.bridge().invariant());
            check(newTransport.bridge().invariant());
        }

        // ACK and physical loss race: loss is authoritative terminal state.
        for (int trial = 0; trial < 512; ++trial) {
            var message = env(1, "ack-loss-" + trial);
            var transport = bound(1, message, trial + 100L);
            var future = transport.admit(message);
            CountDownLatch fire = new CountDownLatch(1);
            AtomicInteger losses = new AtomicInteger();
            AtomicReference<Throwable> problem = new AtomicReference<>();

            Thread ack = new Thread(() -> {
                try {
                    fire.await();
                    transport.accept(
                            message, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
                } catch (IllegalStateException expectedLoss) {
                    if (!"IPC physical transport already lost".equals(
                            expectedLoss.getMessage())) {
                        problem.compareAndSet(null, expectedLoss);
                    }
                } catch (Throwable failure) {
                    problem.compareAndSet(null, failure);
                }
            }, "ipc-ack-racer");
            Thread loss = new Thread(() -> {
                try {
                    fire.await();
                    losses.addAndGet(transport.lost());
                } catch (Throwable failure) {
                    problem.compareAndSet(null, failure);
                }
            }, "ipc-loss-racer");

            ack.start();
            loss.start();
            fire.countDown();
            ack.join();
            loss.join();

            check(problem.get() == null);
            check(losses.get() == 1);
            check(result(future)
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(transport.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            check(transport.bridge().invariant());
        }

        // Terminal receipt and physical loss race: exactly one terminal result
        // wins, and the completion table/receipt table remain in parity.
        for (int trial = 0; trial < 512; ++trial) {
            var message = env(1, "terminal-loss-" + trial);
            var transport = bound(1, message, trial + 1000L);
            var future = transport.admit(message);
            transport.accept(
                    message, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);

            CountDownLatch fire = new CountDownLatch(1);
            AtomicInteger terminalWins = new AtomicInteger();
            AtomicInteger lossWins = new AtomicInteger();
            AtomicReference<Throwable> problem = new AtomicReference<>();

            Thread terminal = new Thread(() -> {
                try {
                    fire.await();
                    transport.accept(
                            message,
                            IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
                    terminalWins.incrementAndGet();
                } catch (IllegalStateException expectedLoss) {
                    if (!"IPC physical transport already lost".equals(
                            expectedLoss.getMessage())) {
                        problem.compareAndSet(null, expectedLoss);
                    }
                } catch (Throwable failure) {
                    problem.compareAndSet(null, failure);
                }
            }, "ipc-terminal-racer");
            Thread loss = new Thread(() -> {
                try {
                    fire.await();
                    if (transport.lost() == 1) {
                        lossWins.incrementAndGet();
                    }
                } catch (Throwable failure) {
                    problem.compareAndSet(null, failure);
                }
            }, "ipc-terminal-loss-racer");

            terminal.start();
            loss.start();
            fire.countDown();
            terminal.join();
            loss.join();

            check(problem.get() == null);
            check(terminalWins.get() + lossWins.get() == 1);
            var outcome = result(future);
            check(outcome == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED
                    || outcome == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check((outcome == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED)
                    == (terminalWins.get() == 1));
            check(transport.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            check(transport.bridge().invariant());
        }

        System.out.println("JAVA_IPC_HOST_COMPLETIONS_PASS assertions=" + checks
                + " races=1024");
    }
}
