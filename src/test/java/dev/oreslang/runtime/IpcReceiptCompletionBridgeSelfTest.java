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
        // The bridge is host-side state. Actor carriers cannot enter any
        // operation that may contend on its gate, including diagnostic reads.
        var carrierBridge = new IpcReceiptCompletionBridge(2);
        var carrierEnvelope = env(1, "carrier-bridge");
        var carrierFuture = carrierBridge.admit(carrierEnvelope);
        var carrierBindingBridge = new IpcReceiptCompletionBridge(1);
        var carrierBindingEnvelope = env(1, "carrier-binding");
        Object carrierOwner = new Object();
        RuntimeBlockingGuard.enterActorCarrier();
        try {
            denied(() -> carrierBridge.admit(env(2, "carrier-bridge")));
            denied(() -> carrierBridge.commitDispatch(carrierEnvelope));
            denied(() -> carrierBridge.acceptVerified(
                    carrierEnvelope, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED));
            denied(() -> carrierBridge.rejectBeforeRemote(carrierEnvelope));
            denied(() -> carrierBridge.transportLost(carrierEnvelope));
            denied(carrierBridge::counters);
            denied(carrierBridge::invariant);
            denied(() -> carrierBindingBridge.claimPhysicalTransport(
                    carrierOwner, carrierBindingEnvelope, 77));
        } finally {
            RuntimeBlockingGuard.exitActorCarrier();
        }
        check(!carrierFuture.toCompletableFuture().isDone());
        check(carrierBridge.counters().equals(
                new IpcReceiptCorrelation.Counters(1, 1)));
        carrierBridge.rejectBeforeRemote(carrierEnvelope);
        check(result(carrierFuture)
                == IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE);
        carrierBindingBridge.claimPhysicalTransport(
                carrierOwner, carrierBindingEnvelope, 77);
        check(carrierBridge.invariant());

        var gate = new IpcReceiptCompletionBridge(3);
        var one = env(1, "old");
        var two = env(2, "new");
        var first = gate.admit(one);
        var second = gate.admit(two);
        check(!first.toCompletableFuture().isDone());
        denied(() -> gate.acceptVerified(
                one, IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN));
        check(!first.toCompletableFuture().isDone());
        denied(() -> gate.acceptVerified(one, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED));
        check(!first.toCompletableFuture().isDone());
        gate.commitDispatch(one);
        denied(() -> gate.commitDispatch(one));
        denied(() -> gate.rejectBeforeRemote(one));
        gate.acceptVerified(one, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        denied(() -> gate.acceptVerified(
                one, IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN));
        check(!first.toCompletableFuture().isDone());
        check(gate.invariant());
        denied(() -> gate.acceptVerified(two,
                IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE));
        check(!second.toCompletableFuture().isDone());
        gate.rejectBeforeRemote(two);
        check(result(second) == IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE);
        denied(() -> gate.rejectBeforeRemote(one));
        check(!first.toCompletableFuture().isDone());
        check(gate.transportLost(one) == 1);
        check(result(first) == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(gate.transportLost(one) == 0);
        check(gate.invariant());
        denied(() -> gate.acceptVerified(one, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED));

        // A production-bound bridge is irreversibly scoped to one opaque
        // physical owner + full logical epoch + physical generation. Legacy
        // unbound entrypoints and foreign/stale authorities fail closed.
        var boundBridge = new IpcReceiptCompletionBridge(2);
        var boundEpoch = env(1, "bound");
        Object boundOwner = new Object();
        Object foreignOwner = new Object();
        boundBridge.claimPhysicalTransport(boundOwner, boundEpoch, 33);
        denied(() -> boundBridge.claimPhysicalTransport(
                foreignOwner, boundEpoch, 34));
        denied(() -> boundBridge.admit(boundEpoch));
        denied(() -> boundBridge.admit(foreignOwner, boundEpoch, 33));
        denied(() -> boundBridge.admit(boundOwner, boundEpoch, 32));
        var wrongBoundEpoch = new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "bound", "a".repeat(64),
                1, 2, 4, 5, 1, 1);
        denied(() -> boundBridge.admit(boundOwner, wrongBoundEpoch, 33));
        var boundFuture = boundBridge.admit(boundOwner, boundEpoch, 33);
        denied(() -> boundBridge.commitDispatch(boundEpoch));
        denied(() -> boundBridge.commitDispatch(foreignOwner, boundEpoch, 33));
        denied(() -> boundBridge.commitDispatch(boundOwner, boundEpoch, 34));
        boundBridge.commitDispatch(boundOwner, boundEpoch, 33);
        denied(() -> boundBridge.acceptVerified(
                boundEpoch, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED));
        denied(() -> boundBridge.acceptVerified(
                foreignOwner, boundEpoch, 33,
                IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED));
        boundBridge.acceptVerified(
                boundOwner, boundEpoch, 33,
                IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        boundBridge.acceptVerified(
                boundOwner, boundEpoch, 33,
                IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
        check(result(boundFuture) == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
        check(boundBridge.invariant());

        var boundLost = new IpcReceiptCompletionBridge(1);
        var lostEpoch = env(1, "bound-loss");
        Object lostOwner = new Object();
        boundLost.claimPhysicalTransport(lostOwner, lostEpoch, 44);
        var lostFuture = boundLost.admit(lostOwner, lostEpoch, 44);
        boundLost.commitDispatch(lostOwner, lostEpoch, 44);
        denied(() -> boundLost.transportLost(lostEpoch));
        denied(() -> boundLost.transportLost(new Object(), lostEpoch, 44));
        denied(() -> boundLost.transportLost(lostOwner, lostEpoch, 45));
        check(boundLost.transportLost(lostOwner, lostEpoch, 44) == 1);
        check(result(lostFuture) == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(boundLost.invariant());

        var boundReject = new IpcReceiptCompletionBridge(1);
        var rejectEpoch = env(1, "bound-reject");
        Object rejectOwner = new Object();
        boundReject.claimPhysicalTransport(rejectOwner, rejectEpoch, 55);
        var rejectFuture = boundReject.admit(rejectOwner, rejectEpoch, 55);
        denied(() -> boundReject.rejectBeforeRemote(rejectEpoch));
        denied(() -> boundReject.rejectBeforeRemote(
                new Object(), rejectEpoch, 55));
        boundReject.rejectBeforeRemote(rejectOwner, rejectEpoch, 55);
        check(result(rejectFuture)
                == IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE);
        check(boundReject.invariant());

        // Trusted pre-remote rejection is terminal exactly once and all
        // dependent callbacks run outside the internal host correlation gate.
        var localDenial = new IpcReceiptCompletionBridge(1);
        var deniedEnvelope = env(1, "local");
        var rejected = localDenial.admit(deniedEnvelope);
        AtomicReference<Throwable> localCallbackFailure = new AtomicReference<>();
        AtomicInteger localCallbackCount = new AtomicInteger();
        rejected.thenAccept(stage -> {
            CountDownLatch reentered = new CountDownLatch(1);
            Thread probe = new Thread(() -> {
                try {
                    if (stage == IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE
                            && localDenial.invariant()) localCallbackCount.incrementAndGet();
                } catch (Throwable error) {
                    localCallbackFailure.compareAndSet(null, error);
                } finally {
                    reentered.countDown();
                }
            });
            probe.start();
            try {
                if (!reentered.await(2, TimeUnit.SECONDS)) {
                    localCallbackFailure.compareAndSet(null,
                            new AssertionError("local rejection callback held bridge gate"));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                localCallbackFailure.compareAndSet(null, interrupted);
            }
        });
        denied(() -> localDenial.acceptVerified(deniedEnvelope,
                IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE));
        check(!rejected.toCompletableFuture().isDone());
        localDenial.rejectBeforeRemote(deniedEnvelope);
        check(result(rejected) == IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE);
        check(localCallbackFailure.get() == null);
        check(localCallbackCount.get() == 1);
        check(localDenial.invariant());
        denied(() -> localDenial.rejectBeforeRemote(deniedEnvelope));
        denied(() -> localDenial.acceptVerified(deniedEnvelope,
                IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED));
        check(localDenial.transportLost(deniedEnvelope) == 0);

        // Same textual transport session is not sufficient authority:
        // delayed EOF for the old route must leave the new epoch's pending
        // future and one transport credit untouched.
        var reusedBridge = new IpcReceiptCompletionBridge(2);
        var previousEpoch = env(1, "reused");
        var latestEpoch = new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "reused", "a".repeat(64),
                1, 2, 4, 5, 2, 2);
        var previousFuture = reusedBridge.admit(previousEpoch);
        var latestFuture = reusedBridge.admit(latestEpoch);
        reusedBridge.commitDispatch(previousEpoch);
        reusedBridge.commitDispatch(latestEpoch);
        check(reusedBridge.transportLost(previousEpoch) == 1);
        check(result(previousFuture) == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(!latestFuture.toCompletableFuture().isDone());
        check(reusedBridge.counters().equals(new IpcReceiptCorrelation.Counters(1, 1)));
        check(reusedBridge.transportLost(previousEpoch) == 0);
        reusedBridge.acceptVerified(latestEpoch, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        reusedBridge.acceptVerified(latestEpoch, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
        check(result(latestFuture) == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
        check(reusedBridge.invariant());

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
        onTerminal.commitDispatch(three);
        onTerminal.acceptVerified(three, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        onTerminal.acceptVerified(three, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
        check(result(completion) == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
        check(callbackFailure.get() == null);
        check(observed.get() == 1);
        check(onTerminal.invariant());

        // A failure after the irreversible dispatch claim has uncertain
        // remote visibility; it must never complete as "never sent".
        var afterClaim = new IpcReceiptCompletionBridge(1);
        var claimedMessage = env(1, "claim");
        var uncertain = afterClaim.admit(claimedMessage);
        afterClaim.commitDispatch(claimedMessage);
        denied(() -> afterClaim.rejectBeforeRemote(claimedMessage));
        denied(() -> afterClaim.acceptVerified(claimedMessage,
                IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN));
        check(!uncertain.toCompletableFuture().isDone());
        check(afterClaim.counters().transportCredits() == 1);
        check(afterClaim.transportLost(claimedMessage) == 1);
        check(result(uncertain) == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(afterClaim.counters().pending() == 0);
        check(afterClaim.counters().transportCredits() == 0);

        // A local session loss must fence a dispatch claim, not merely
        // remove the future while allowing a late writer to reopen it.
        for (int trial = 0; trial < 256; ++trial) {
            var closing = new IpcReceiptCompletionBridge(1);
            var message = env(1, "closed");
            var future = closing.admit(message);
            CountDownLatch fire = new CountDownLatch(1);
            AtomicInteger commits = new AtomicInteger();
            AtomicInteger losses = new AtomicInteger();
            AtomicReference<Throwable> problem = new AtomicReference<>();
            Thread publisher = new Thread(() -> {
                try {
                    fire.await();
                    closing.commitDispatch(message);
                    commits.incrementAndGet();
                } catch (IllegalArgumentException expectedRetired) {
                    // EOF won the correlation ledger.
                } catch (Throwable failure) { problem.compareAndSet(null, failure); }
            });
            Thread eof = new Thread(() -> {
                try {
                    fire.await();
                    losses.addAndGet(closing.transportLost(message));
                } catch (Throwable failure) { problem.compareAndSet(null, failure); }
            });
            publisher.start();
            eof.start();
            fire.countDown();
            publisher.join();
            eof.join();
            check(problem.get() == null);
            check(commits.get() == 0 || commits.get() == 1);
            check(losses.get() == 1);
            check(result(future) == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(closing.counters().pending() == 0);
            check(closing.counters().transportCredits() == 0);
            check(closing.invariant());
        }

        // Physical transmission must begin only after commitDispatch,
        // which races atomically with local never-sent rejection. Regardless
        // of which wins, there is exactly one terminal host completion.
        for (int trial = 0; trial < 512; ++trial) {
            var competing = new IpcReceiptCompletionBridge(1);
            var message = env(1, "raced");
            var future = competing.admit(message);
            CountDownLatch fire = new CountDownLatch(1);
            AtomicInteger committed = new AtomicInteger();
            AtomicInteger preRejected = new AtomicInteger();
            AtomicReference<Throwable> problem = new AtomicReference<>();
            Thread publisher = new Thread(() -> {
                try {
                    fire.await();
                    competing.commitDispatch(message);
                    committed.incrementAndGet();
                } catch (IllegalArgumentException expectedLost) {
                    // Reject-before-publish won the gate.
                } catch (Throwable failure) { problem.compareAndSet(null, failure); }
            });
            Thread deny = new Thread(() -> {
                try {
                    fire.await();
                    competing.rejectBeforeRemote(message);
                    preRejected.incrementAndGet();
                } catch (IllegalArgumentException expectedCommitted) {
                    // The dispatch fence already won.
                } catch (Throwable failure) { problem.compareAndSet(null, failure); }
            });
            publisher.start();
            deny.start();
            fire.countDown();
            publisher.join();
            deny.join();
            check(problem.get() == null);
            check(committed.get() + preRejected.get() == 1);
            check(competing.invariant());
            if (committed.get() == 1) {
                check(!future.toCompletableFuture().isDone());
                check(competing.counters().transportCredits() == 1);
                check(competing.transportLost(message) == 1);
                check(result(future) == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            } else {
                check(result(future) == IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE);
                denied(() -> competing.commitDispatch(message));
                check(competing.transportLost(message) == 0);
            }
            check(competing.invariant());
            check(competing.counters().pending() == 0);
            check(competing.counters().transportCredits() == 0);
        }

        for (int trial = 0; trial < 512; ++trial) {
            var racing = new IpcReceiptCompletionBridge(1);
            var message = env(1, "session");
            var future = racing.admit(message);
            racing.commitDispatch(message);
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
                    lost.addAndGet(racing.transportLost(message));
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
