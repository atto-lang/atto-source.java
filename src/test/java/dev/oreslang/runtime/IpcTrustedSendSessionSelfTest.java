package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Isolated JDK trusted host-model self-test; NOT a real kernel socket test. */
public final class IpcTrustedSendSessionSelfTest {
    private IpcTrustedSendSessionSelfTest() { }
    private static int checks;

    private static void check(boolean value) {
        ++checks;
        if (!value) throw new AssertionError("trusted IPC send gate violated");
    }

    private static void denies(Runnable operation) {
        try { operation.run(); }
        catch (IllegalArgumentException | IllegalStateException expected) {
            ++checks; return;
        }
        throw new AssertionError("untrusted/stale session operation accepted");
    }

    private static IpcReceiptCorrelation.Envelope env(long seq, String session,
                                                        long route, long grant) {
        return new IpcReceiptCorrelation.Envelope("tenant", "sender", "worker",
                session, "a".repeat(64), 1, 2, route, grant, seq, seq);
    }

    public static void main(String[] args) throws InterruptedException {
        var receipts = new IpcReceiptCompletionBridge(2);
        var first = env(1, "session", 3, 4);
        var second = env(2, "session", 3, 4);
        var doneFirst = receipts.admit(first);
        var doneSecond = receipts.admit(second);
        var owner = new IpcTrustedSendSession(receipts, first, 1);
        var send = owner.begin(first);
        check(owner.snapshot().outstanding() == 1);
        denies(() -> owner.begin(first));
        denies(() -> owner.begin(second));
        check(receipts.counters().transportCredits() == 2);
        owner.requestClose();
        denies(() -> owner.begin(second));
        denies(owner::confirmPhysicalClosed);
        check(!doneFirst.toCompletableFuture().isDone());
        send.releaseAfterPhysicalQuiescence();
        denies(send::releaseAfterPhysicalQuiescence);
        check(owner.snapshot().outstanding() == 0);
        check(owner.confirmPhysicalClosed() == 2);
        check(doneFirst.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(doneSecond.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        denies(owner::confirmPhysicalClosed);
        check(owner.snapshot().finalizationComplete());
        check(receipts.invariant());

        // A stale incarnation/route/capability from an old session may not
        // claim a dispatch permit or retire a new epoch sharing session text.
        var sharedReceipts = new IpcReceiptCompletionBridge(2);
        var oldEpoch = env(1, "reused", 3, 4);
        var newEpoch = env(2, "reused", 5, 6);
        var oldFuture = sharedReceipts.admit(oldEpoch);
        var newFuture = sharedReceipts.admit(newEpoch);
        var oldSession = new IpcTrustedSendSession(sharedReceipts, oldEpoch, 1);
        denies(() -> oldSession.begin(newEpoch));
        var oldLease = oldSession.begin(oldEpoch);
        oldSession.requestClose();
        oldLease.releaseAfterPhysicalQuiescence();
        check(oldSession.confirmPhysicalClosed() == 1);
        check(oldFuture.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(!newFuture.toCompletableFuture().isDone());
        check(sharedReceipts.counters().equals(
                new IpcReceiptCorrelation.Counters(1, 1)));
        var newSession = new IpcTrustedSendSession(sharedReceipts, newEpoch, 1);
        var newLease = newSession.begin(newEpoch);
        sharedReceipts.acceptVerified(newEpoch, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        sharedReceipts.acceptVerified(newEpoch, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
        check(newFuture.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
        newSession.requestClose();
        denies(newSession::confirmPhysicalClosed);
        newLease.releaseAfterPhysicalQuiescence();
        check(newSession.confirmPhysicalClosed() == 0);
        check(sharedReceipts.invariant());

        // Finalization completes arbitrary user Futures OUTSIDE the session
        // monitor so a callback can query session state from another carrier.
        var callbacks = new IpcReceiptCompletionBridge(1);
        var cbMessage = env(1, "callback", 1, 1);
        var cbFuture = callbacks.admit(cbMessage);
        var cbOwner = new IpcTrustedSendSession(callbacks, cbMessage, 1);
        AtomicReference<Throwable> callbackError = new AtomicReference<>();
        AtomicInteger callbackObserved = new AtomicInteger();
        cbFuture.thenAccept(result -> {
            CountDownLatch observed = new CountDownLatch(1);
            Thread probe = new Thread(() -> {
                try {
                    if (result == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN
                            && cbOwner.snapshot().closing()) callbackObserved.incrementAndGet();
                } catch (Throwable error) {
                    callbackError.compareAndSet(null, error);
                } finally {
                    observed.countDown();
                }
            });
            probe.start();
            try {
                if (!observed.await(2, TimeUnit.SECONDS)) {
                    callbackError.compareAndSet(null,
                            new AssertionError("callback executed under session monitor"));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                callbackError.compareAndSet(null, interrupted);
            }
        });
        var cbLease = cbOwner.begin(cbMessage);
        cbOwner.requestClose();
        cbLease.releaseAfterPhysicalQuiescence();
        check(cbOwner.confirmPhysicalClosed() == 1);
        check(callbackError.get() == null);
        check(callbackObserved.get() == 1);

        // Concurrent admission and shutdown have one linearization point.
        // A granted permit remains live until the physical adapter releases
        // it; close never fabricates a socket drain or prematurely retires it.
        for (int trial = 0; trial < 512; ++trial) {
            var bridge = new IpcReceiptCompletionBridge(1);
            var message = env(1, "concurrent", 3, 4);
            var future = bridge.admit(message);
            var session = new IpcTrustedSendSession(bridge, message, 1);
            CountDownLatch fire = new CountDownLatch(1);
            AtomicReference<IpcTrustedSendSession.SendLease> winning = new AtomicReference<>();
            AtomicReference<Throwable> problem = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try {
                    fire.await();
                    winning.set(session.begin(message));
                } catch (IllegalStateException expectedClosed) {
                    // Close won the session gate.
                } catch (Throwable failure) { problem.compareAndSet(null, failure); }
            });
            Thread closer = new Thread(() -> {
                try {
                    fire.await();
                    session.requestClose();
                } catch (Throwable failure) { problem.compareAndSet(null, failure); }
            });
            writer.start(); closer.start(); fire.countDown();
            writer.join(); closer.join();
            check(problem.get() == null);
            check(session.snapshot().closing());
            check(session.snapshot().outstanding() == (winning.get() == null ? 0 : 1));
            denies(() -> session.begin(message));
            if (winning.get() != null) {
                denies(session::confirmPhysicalClosed);
                winning.get().releaseAfterPhysicalQuiescence();
            }
            check(session.confirmPhysicalClosed() == 1);
            check(future.toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(session.snapshot().outstanding() == 0);
            check(bridge.invariant());
        }
        System.out.println("JAVA_IPC_SEND_LEASE_HOST_ONLY_PASS checks=" + checks
                + " closeRaces=512");
    }
}
