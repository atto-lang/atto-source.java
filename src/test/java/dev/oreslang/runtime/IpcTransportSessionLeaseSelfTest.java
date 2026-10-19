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
        var transport = new IpcTransportSessionLease(frame, 81, 1,
                bridge, closes::incrementAndGet, Runnable::run);
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
        var blocked = new IpcTransportSessionLease(rejectMessage, 15, 1,
                rejectionBridge, () -> { }, Runnable::run);
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
        var failing = new IpcTransportSessionLease(failingMessage, 55, 1,
                failureBridge, failedCloses::incrementAndGet, Runnable::run);
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
        var quarantine = new IpcTransportSessionLease(quarantinedMessage, 56, 1,
                quarantineBridge, () -> { throw new IOException("close not confirmed"); }, Runnable::run);
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
        var recovery = new IpcTransportSessionLease(recoveryMessage, 57, 1,
                recoveryBridge, () -> {
                    if (closeAttempts.incrementAndGet() == 1) {
                        throw new IOException("first close was unconfirmed");
                    }
                }, Runnable::run);
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

        // A trusted host scheduler must accept cleanup without running it on
        // an actor carrier. No physical close or callback occurs before the
        // queued cleanup task is actually executed on the host worker.
        var deferredBridge = new IpcReceiptCompletionBridge(1);
        var deferredMessage = message(1, "deferred");
        var deferredResult = deferredBridge.admit(deferredMessage);
        AtomicReference<Runnable> queuedCleanup = new AtomicReference<>();
        AtomicInteger deferredCloses = new AtomicInteger();
        var deferred = new IpcTransportSessionLease(deferredMessage, 58, 1,
                deferredBridge, deferredCloses::incrementAndGet,
                cleanup -> {
                    if (!queuedCleanup.compareAndSet(null, cleanup)) {
                        throw new IllegalStateException("cleanup queue overflow");
                    }
                });
        deferred.writeFrame(58, deferredMessage, value -> { });
        deferred.requestClose();
        check(deferred.snapshot().phase() == IpcTransportSessionLease.Phase.FINALIZING);
        check(deferredCloses.get() == 0);
        check(!deferredResult.toCompletableFuture().isDone());
        queuedCleanup.get().run(); // explicit stand-in for isolated host worker
        queuedCleanup.get().run(); // duplicated scheduler callback must be inert
        check(deferredCloses.get() == 1);
        check(deferred.snapshot().phase() == IpcTransportSessionLease.Phase.RETIRED);
        check(deferredResult.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);

        var deniedCleanupBridge = new IpcReceiptCompletionBridge(1);
        var deniedCleanupMessage = message(1, "noWorker");
        var deniedCleanupFuture = deniedCleanupBridge.admit(deniedCleanupMessage);
        var deniedCleanup = new IpcTransportSessionLease(deniedCleanupMessage, 59, 1,
                deniedCleanupBridge, () -> { },
                cleanup -> { throw new IllegalStateException("host cleanup budget denied"); });
        deniedCleanup.writeFrame(59, deniedCleanupMessage, value -> { });
        deniedCleanup.requestClose();
        check(deniedCleanup.snapshot().phase() == IpcTransportSessionLease.Phase.QUARANTINED);
        check(!deniedCleanupFuture.toCompletableFuture().isDone());
        check(deniedCleanup.closeFailure() instanceof IllegalStateException);
        check(deniedCleanup.invariant());

        // A scheduler that partially enqueued a task before throwing must
        // not let that stale callback steal a later explicit recovery attempt.
        var staleBridge = new IpcReceiptCompletionBridge(1);
        var staleMessage = message(1, "staleCleanup");
        var staleOutcome = staleBridge.admit(staleMessage);
        AtomicInteger cleanupSchedules = new AtomicInteger();
        AtomicInteger actualCloses = new AtomicInteger();
        AtomicReference<Runnable> priorTask = new AtomicReference<>();
        AtomicReference<Runnable> resumedTask = new AtomicReference<>();
        var staleGate = new IpcTransportSessionLease("staleCleanup", 60, 1,
                staleBridge, actualCloses::incrementAndGet, cleanup -> {
                    if (cleanupSchedules.incrementAndGet() == 1) {
                        priorTask.set(cleanup);
                        throw new IllegalStateException("schedule admission uncertain");
                    }
                    resumedTask.set(cleanup);
                });
        staleGate.writeFrame(60, staleMessage, value -> { });
        staleGate.requestClose();
        check(staleGate.snapshot().phase() == IpcTransportSessionLease.Phase.QUARANTINED);
        staleGate.retryQuarantinedClose();
        priorTask.get().run(); // stale attempt cannot execute physical close
        check(actualCloses.get() == 0);
        check(!staleOutcome.toCompletableFuture().isDone());
        resumedTask.get().run();
        check(actualCloses.get() == 1);
        check(staleGate.snapshot().phase() == IpcTransportSessionLease.Phase.RETIRED);
        check(staleOutcome.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(staleGate.invariant());

        // Verify close never recycles a descriptor during a real host write.
        for (int trial = 0; trial < 512; ++trial) {
            var candidateBridge = new IpcReceiptCompletionBridge(1);
            var candidateMessage = message(1, "race");
            var terminal = candidateBridge.admit(candidateMessage);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger closed = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            var candidate = new IpcTransportSessionLease(candidateMessage, trial + 1L, 1,
                    candidateBridge, closed::incrementAndGet, Runnable::run);
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
            var contender = new IpcTransportSessionLease(contenderMessage, trial + 1L, 1,
                    contenderBridge, closeCount::incrementAndGet, Runnable::run);
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
