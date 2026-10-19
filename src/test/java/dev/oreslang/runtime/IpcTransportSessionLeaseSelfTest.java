package dev.oreslang.runtime;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host-only physical session/write lifetime tests. This is real Java code
 * but does not authenticate peer sockets or execute .ores guest actors.
 */
public final class IpcTransportSessionLeaseSelfTest {
    private IpcTransportSessionLeaseSelfTest() { }
    private static int assertions;

    private static IpcReceiptCorrelation.Envelope message(long id, String session) {
        return new IpcReceiptCorrelation.Envelope("tenant", "sender", "worker",
                session, "a".repeat(64), 1, 2, 3, 4, id, id);
    }

    private static void check(boolean value) {
        ++assertions;
        if (!value) throw new AssertionError("IPC transport physical lifetime invariant");
    }

    private static void deny(Runnable action) {
        try { action.run(); }
        catch (IllegalStateException | IllegalArgumentException expected) {
            ++assertions; return;
        }
        throw new AssertionError("invalid transport admission was allowed");
    }

    public static void main(String[] ignored) throws Exception {
        var bridge = new IpcReceiptCompletionBridge(2);
        var frame = message(1, "session");
        var done = bridge.admit(frame);
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        var transport = new IpcTransportSessionLease("session", 81, 1,
                bridge, closes::incrementAndGet);
        transport.writeFrame(81, frame, value -> writes.incrementAndGet());
        check(writes.get() == 1);
        check(!done.toCompletableFuture().isDone());
        bridge.acceptVerified(frame, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        bridge.acceptVerified(frame, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
        check(done.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
        check(transport.invariant());
        transport.requestClose();
        check(closes.get() == 1);
        check(transport.snapshot().phase() == IpcTransportSessionLease.Phase.RETIRED);
        transport.requestClose();
        check(closes.get() == 1);
        deny(() -> {
            try { transport.writeFrame(81, frame, value -> writes.incrementAndGet()); }
            catch (IOException impossible) { throw new AssertionError(impossible); }
        });

        var rejectionBridge = new IpcReceiptCompletionBridge(1);
        var rejectMessage = message(1, "reject");
        var rejected = rejectionBridge.admit(rejectMessage);
        var blocked = new IpcTransportSessionLease("reject", 15, 1,
                rejectionBridge, () -> { });
        deny(() -> {
            try { blocked.writeFrame(14, rejectMessage, value -> {
                throw new AssertionError("stale session wrote");
            }); } catch (IOException impossible) { throw new AssertionError(impossible); }
        });
        rejectionBridge.rejectBeforeRemote(rejectMessage);
        check(rejected.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE);
        blocked.requestClose();
        check(blocked.invariant());

        var failureBridge = new IpcReceiptCompletionBridge(1);
        var failingMessage = message(1, "failed");
        var failingFuture = failureBridge.admit(failingMessage);
        AtomicInteger failedCloses = new AtomicInteger();
        var failing = new IpcTransportSessionLease("failed", 55, 1,
                failureBridge, failedCloses::incrementAndGet);
        try {
            failing.writeFrame(55, failingMessage, value -> {
                throw new IOException("wire write was uncertain");
            });
            throw new AssertionError("write must throw");
        } catch (IOException expected) { check(expected.getMessage().contains("uncertain")); }
        check(failingFuture.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(failedCloses.get() == 1);
        check(failing.snapshot().phase() == IpcTransportSessionLease.Phase.RETIRED);
        check(failing.invariant());

        // A failed physical close is QUARANTINED, never marked reusable.
        var quarantineBridge = new IpcReceiptCompletionBridge(1);
        var quarantinedMessage = message(1, "quarantine");
        var quarantineFuture = quarantineBridge.admit(quarantinedMessage);
        var quarantine = new IpcTransportSessionLease("quarantine", 56, 1,
                quarantineBridge, () -> { throw new IOException("close not confirmed"); });
        quarantine.writeFrame(56, quarantinedMessage, value -> { });
        quarantine.requestClose();
        check(quarantine.snapshot().phase() == IpcTransportSessionLease.Phase.QUARANTINED);
        check(!quarantine.snapshot().physicalCloseConfirmed());
        check(!quarantineFuture.toCompletableFuture().isDone());
        check(quarantine.closeFailure() instanceof IOException);
        check(quarantine.invariant());

        // A trusted supervisor may explicitly retry a quarantined physical
        // close, but no write admission reopens during recovery.
        var recoveryBridge = new IpcReceiptCompletionBridge(1);
        var recoveryMessage = message(1, "recovery");
        var recoveryResult = recoveryBridge.admit(recoveryMessage);
        AtomicInteger closeAttempts = new AtomicInteger();
        var recovery = new IpcTransportSessionLease("recovery", 57, 1,
                recoveryBridge, () -> {
                    if (closeAttempts.incrementAndGet() == 1) {
                        throw new IOException("first close was unconfirmed");
                    }
                });
        recovery.writeFrame(57, recoveryMessage, value -> { });
        recovery.requestClose();
        check(recovery.snapshot().phase() == IpcTransportSessionLease.Phase.QUARANTINED);
        check(!recoveryResult.toCompletableFuture().isDone());
        deny(() -> {
            try { recovery.writeFrame(57, recoveryMessage, value -> { }); }
            catch (IOException impossible) { throw new AssertionError(impossible); }
        });
        recovery.retryQuarantinedClose();
        check(closeAttempts.get() == 2);
        check(recovery.snapshot().phase() == IpcTransportSessionLease.Phase.RETIRED);
        check(recoveryResult.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(recovery.invariant());
        deny(recovery::retryQuarantinedClose);

        // Verify close never recycles a descriptor during a real host write.
        for (int trial = 0; trial < 512; ++trial) {
            var candidateBridge = new IpcReceiptCompletionBridge(1);
            var candidateMessage = message(1, "race");
            var terminal = candidateBridge.admit(candidateMessage);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger closed = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            var candidate = new IpcTransportSessionLease("race", trial + 1L, 1,
                    candidateBridge, closed::incrementAndGet);
            long epoch = trial + 1L;
            Thread writer = new Thread(() -> {
                try {
                    candidate.writeFrame(epoch, candidateMessage, value -> {
                        entered.countDown();
                        try {
                            if (!release.await(3, TimeUnit.SECONDS))
                                throw new IOException("test write latch expired");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                    });
                } catch (Throwable problem) { failure.set(problem); }
            });
            writer.start();
            check(entered.await(3, TimeUnit.SECONDS));
            candidate.requestClose();
            check(closed.get() == 0);
            check(candidate.snapshot().activeWrites() == 1);
            check(!terminal.toCompletableFuture().isDone());
            release.countDown();
            writer.join();
            check(failure.get() == null);
            check(closed.get() == 1);
            check(candidate.snapshot().phase() == IpcTransportSessionLease.Phase.RETIRED);
            check(terminal.toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(candidateBridge.invariant());
            check(candidate.invariant());
        }

        // Competing close and transport admission linearize: no writes
        // permitted after close, but any admitted in-flight writer drains.
        for (int trial = 0; trial < 512; ++trial) {
            var contenderBridge = new IpcReceiptCompletionBridge(1);
            var contenderMessage = message(1, "compete");
            var terminal = contenderBridge.admit(contenderMessage);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger closeCount = new AtomicInteger();
            AtomicInteger wrote = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<>();
            var contender = new IpcTransportSessionLease("compete", trial + 1L, 1,
                    contenderBridge, closeCount::incrementAndGet);
            long epoch = trial + 1L;
            Thread sender = new Thread(() -> {
                try {
                    start.await();
                    contender.writeFrame(epoch, contenderMessage, x -> wrote.incrementAndGet());
                } catch (IllegalStateException expectedClosed) {
                    // Close took precedence; local unsent outcome remains valid.
                } catch (Throwable bad) { error.compareAndSet(null, bad); }
            });
            Thread shutdown = new Thread(() -> {
                try { start.await(); contender.requestClose(); }
                catch (Throwable bad) { error.compareAndSet(null, bad); }
            });
            sender.start();shutdown.start();start.countDown();
            sender.join();shutdown.join();
            check(error.get() == null);
            check(closeCount.get() == 1);
            check(contender.snapshot().activeWrites() == 0);
            check(contender.snapshot().phase() == IpcTransportSessionLease.Phase.RETIRED);
            check(terminal.toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(wrote.get() == 0 || wrote.get() == 1);
            check(contenderBridge.invariant());
        }
        System.out.println("JAVA_IPC_PHYSICAL_SESSION_HOST_PASS assertions=" + assertions
                + " races=1024");
    }
}
