package dev.oreslang.runtime;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Semaphore;
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

    private static Semaphore globalBudgetForTest() {
        try {
            var field = IpcReceiptCompletionBridge.class.getDeclaredField(
                    "globalCompletionBudget");
            field.setAccessible(true);
            return (Semaphore) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    /**
     * Wait for ALL prior asynchronous completion dependents to release their
     * process-wide reservations before comparing absolute permit counts.
     * A completed CompletableFuture is not proof its dependents have returned.
     * A timed atomic multi-permit acquire avoids poll/sleep test races under
     * Native Image, while also detecting a genuine leaked permit.
     */
    private static void awaitGlobalBudgetQuiescence() throws InterruptedException {
        Semaphore budget = globalBudgetForTest();
        final int allPermits = 16_384;
        if (!budget.tryAcquire(allPermits, 5, TimeUnit.SECONDS)) {
            throw new AssertionError(
                    "IPC terminal completion permits did not quiesce: available="
                    + budget.availablePermits());
        }
        budget.release(allPermits);
    }

    private static Object removeCompletionForTest(
            IpcReceiptCompletionBridge bridge, long sequence) {
        try {
            var field = IpcReceiptCompletionBridge.class.getDeclaredField("completions");
            field.setAccessible(true);
            Object map = field.get(bridge);
            Object removed = java.util.Map.class
                    .getMethod("remove", Object.class)
                    .invoke(map, Long.valueOf(sequence));
            if (removed == null) {
                throw new AssertionError("test corruption found no completion");
            }
            return removed;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void restoreCompletionForTest(
            IpcReceiptCompletionBridge bridge, long sequence, Object completion) {
        try {
            var field = IpcReceiptCompletionBridge.class.getDeclaredField("completions");
            field.setAccessible(true);
            Object map = field.get(bridge);
            Object previous = java.util.Map.class
                    .getMethod("put", Object.class, Object.class)
                    .invoke(map, Long.valueOf(sequence), completion);
            if (previous != null) {
                throw new AssertionError("test restore replaced a live completion");
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
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
            CountDownLatch callbackDone = new CountDownLatch(1);
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
                } finally {
                    callbackDone.countDown();
                }
            });

            transport.accept(
                    message, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            transport.accept(
                    message, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(result(future)
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(callbackDone.await(3, TimeUnit.SECONDS));
            check(callbackFailure.get() == null);
            check(observed.get() == 1);
            check(transport.bridge().invariant());
        }

        // Global (not merely per-bridge) admission slots remain held while
        // user dependents block, are refunded after delivery, and are not
        // leaked by a rejected duplicate/over-capacity admission.
        {
            awaitGlobalBudgetQuiescence();
            Semaphore budget = globalBudgetForTest();
            int before = budget.availablePermits();
            var firstMessage = env(1, "global-terminal-budget");
            var secondMessage = env(2, "global-terminal-budget");
            var transport = bound(1, firstMessage, 51);
            var first = transport.admit(firstMessage);
            check(budget.availablePermits() == before - 1);
            denied(() -> transport.admit(firstMessage));
            check(budget.availablePermits() == before - 1);
            denied(() -> transport.admit(secondMessage));
            check(budget.availablePermits() == before - 1);
            CountDownLatch callbackEntered = new CountDownLatch(1);
            CountDownLatch releaseCallback = new CountDownLatch(1);
            CountDownLatch callbackReturned = new CountDownLatch(1);
            first.thenRun(() -> {
                callbackEntered.countDown();
                try {
                    if (!releaseCallback.await(3, TimeUnit.SECONDS)) {
                        throw new AssertionError("completion budget callback timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                } finally {
                    callbackReturned.countDown();
                }
            });
            transport.accept(
                    firstMessage, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            transport.accept(
                    firstMessage, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            try {
                check(callbackEntered.await(2, TimeUnit.SECONDS));
                check(budget.availablePermits() == before - 1);
                check(result(first)
                        == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            } finally {
                releaseCallback.countDown();
            }
            check(callbackReturned.await(2, TimeUnit.SECONDS));
            awaitGlobalBudgetQuiescence();
            check(budget.availablePermits() == before);
            check(transport.bridge().invariant());
        }

        // A global-budget denial must be a pure pre-dispatch rejection:
        // no correlation, credit, or host completion may be consumed. Once
        // capacity returns, that exact message must still be admissible.
        // A throwing downstream callback must never strand its global permit
        // or alter the authenticated terminal outcome.
        {
            awaitGlobalBudgetQuiescence();
            Semaphore budget = globalBudgetForTest();
            final int limit = 16_384;
            var message = env(1, "global-budget-exhaustion");
            var transport = bound(1, message, 57);
            check(budget.tryAcquire(limit, 5, TimeUnit.SECONDS));
            try {
                denied(() -> transport.admit(message));
                check(budget.availablePermits() == 0);
                check(transport.bridge().counters().pending() == 0);
                check(transport.bridge().invariant());
            } finally {
                budget.release(limit);
            }
            var outcome = transport.admit(message);
            CountDownLatch callbackEntered = new CountDownLatch(1);
            CompletionStage<Void> throwingDependent = outcome.thenRun(() -> {
                callbackEntered.countDown();
                throw new IllegalStateException("intentional IPC test callback failure");
            });
            transport.accept(
                    message, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            transport.accept(
                    message, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(result(outcome) == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(callbackEntered.await(2, TimeUnit.SECONDS));
            try {
                throwingDependent.toCompletableFuture().join();
                throw new AssertionError("throwing dependent was not exceptional");
            } catch (CompletionException expected) {
                check(expected.getCause() instanceof IllegalStateException);
                check("intentional IPC test callback failure"
                        .equals(expected.getCause().getMessage()));
            }
            awaitGlobalBudgetQuiescence();
            check(budget.availablePermits() == limit);
            check(transport.bridge().counters().pending() == 0);
            check(transport.bridge().invariant());
        }

        // Terminal callbacks are isolated from receipt processing and remain
        // charged against bridge capacity until synchronous dependents return.
        // A blocking callback therefore applies bounded backpressure instead
        // of pinning the receipt thread or allowing an unbounded delivery pileup.
        {
            var firstMessage = env(1, "terminal-delivery-bound");
            var secondMessage = env(2, "terminal-delivery-bound");
            var transport = bound(1, firstMessage, 52);
            var first = transport.admit(firstMessage);
            CountDownLatch callbackEntered = new CountDownLatch(1);
            CountDownLatch releaseCallback = new CountDownLatch(1);
            CountDownLatch callbackReturned = new CountDownLatch(1);
            first.thenRun(() -> {
                callbackEntered.countDown();
                try {
                    releaseCallback.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                } finally {
                    callbackReturned.countDown();
                }
            });

            transport.accept(
                    firstMessage, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            transport.accept(
                    firstMessage, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(callbackEntered.await(2, TimeUnit.SECONDS));
            check(result(first)
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            denied(() -> transport.admit(secondMessage));
            check(transport.bridge().invariant());

            releaseCallback.countDown();
            check(callbackReturned.await(2, TimeUnit.SECONDS));
            // The callback's final latch fires before PreparedDelivery.run()
            // decrements bridge capacity and returns its global reservation.
            // Wait for that delivery to drain before expecting re-admission.
            awaitGlobalBudgetQuiescence();
            var second = transport.admit(secondMessage);
            transport.accept(
                    secondMessage, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            transport.accept(
                    secondMessage, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(result(second)
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(transport.bridge().invariant());
        }

        // Physical-loss delivery is independently live per receipt and per
        // transport. One blocked dependent callback cannot postpone another
        // terminal notification or monopolize the physical cleanup worker.
        {
            var firstMessage = env(1, "loss-delivery-isolation");
            var secondMessage = env(2, "loss-delivery-isolation");
            var transport = bound(2, firstMessage, 53);
            var first = transport.admit(firstMessage);
            var second = transport.admit(secondMessage);
            CountDownLatch firstEntered = new CountDownLatch(1);
            CountDownLatch releaseFirst = new CountDownLatch(1);
            CountDownLatch firstReturned = new CountDownLatch(1);
            CountDownLatch secondObserved = new CountDownLatch(1);
            first.thenRun(() -> {
                firstEntered.countDown();
                try {
                    releaseFirst.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                } finally {
                    firstReturned.countDown();
                }
            });
            second.thenAccept(stage -> {
                if (stage == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN) {
                    secondObserved.countDown();
                }
            });

            check(transport.lost() == 2);
            check(firstEntered.await(2, TimeUnit.SECONDS));
            check(secondObserved.await(2, TimeUnit.SECONDS));
            check(result(second) == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(transport.bridge().invariant());

            var otherMessage = env(1, "loss-delivery-other-transport");
            var other = bound(1, otherMessage, 54);
            var otherFuture = other.admit(otherMessage);
            CountDownLatch otherObserved = new CountDownLatch(1);
            otherFuture.thenAccept(stage -> {
                if (stage == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN) {
                    otherObserved.countDown();
                }
            });
            check(other.lost() == 1);
            check(otherObserved.await(2, TimeUnit.SECONDS));
            check(result(otherFuture)
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(other.bridge().invariant());

            releaseFirst.countDown();
            check(firstReturned.await(2, TimeUnit.SECONDS));
            check(result(first) == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(transport.bridge().invariant());
        }

        // Terminal receipt application must preflight completion-table parity
        // before mutating the receipt ledger. Simulate internal completion-map
        // corruption after ACK, verify terminal transition is rejected without
        // consuming the ledger entry, then restore parity and commit normally.
        {
            awaitGlobalBudgetQuiescence();
            Semaphore budget = globalBudgetForTest();
            int before = budget.availablePermits();
            var message = env(1, "terminal-preflight");
            var transport = bound(1, message, 55);
            var future = transport.admit(message);
            check(budget.availablePermits() == before - 1);
            transport.accept(
                    message, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            Object removed = removeCompletionForTest(
                    transport.bridge(), message.messageSequence());
            denied(() -> transport.accept(
                    message, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED));
            // A rejected terminal transition must preserve both retry authority
            // and the global completion slot owned by the still-pending receipt.
            check(budget.availablePermits() == before - 1);
            check(!future.toCompletableFuture().isDone());
            restoreCompletionForTest(
                    transport.bridge(), message.messageSequence(), removed);
            transport.accept(
                    message, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(result(future)
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            awaitGlobalBudgetQuiescence();
            check(budget.availablePermits() == before);
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

        // Deterministic ACK -> physical loss -> late terminal race. A
        // delayed peer terminal can never convert trusted uncertainty into a
        // committed outcome after physical loss has been claimed.
        {
            var message = env(1, "post-ack-loss");
            var transport = bound(1, message, 65);
            var future = transport.admit(message);
            transport.accept(message, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            check(transport.lost() == 1);
            check(result(future) == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            try {
                transport.accept(message, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
                throw new AssertionError("terminal accepted after physical loss");
            } catch (IllegalStateException expectedLoss) {
                check("IPC physical transport already lost".equals(
                        expectedLoss.getMessage()));
            }
            check(transport.lost() == 0);
            check(transport.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            check(transport.bridge().invariant());
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
