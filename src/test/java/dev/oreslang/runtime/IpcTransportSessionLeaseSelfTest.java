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

    private static void offCallerCleanup(Runnable cleanup) {
        Thread worker = new Thread(cleanup, "ipc-cleanup-selftest");
        worker.start();
        try { worker.join(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cleanup self-test interrupted", interrupted);
        }
    }

    public static void main(String[] ignored) throws Exception {
        // Defense in depth: package-local lease APIs themselves enforce the
        // actor-carrier blocking boundary. This prevents future adapters from
        // bypassing IpcAuthenticatedSocketTransport's outer guard.
        {
            var guardedBridge = new IpcReceiptCompletionBridge(1);
            var guardedMessage = message(1, "guarded");
            Object guardedOwner = new Object();
            guardedBridge.claimPhysicalTransport(guardedOwner, guardedMessage, 80);
            AtomicInteger guardedWrites = new AtomicInteger();
            var guardedLease = new IpcTransportSessionLease(
                    guardedMessage, 80, 1, guardedBridge, guardedOwner,
                    (value, preparedFrame) -> guardedWrites.incrementAndGet(),
                    () -> { },
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);

            RuntimeBlockingGuard.enterActorCarrier();
            try {
                deny(() -> guardedLease.sendPreparedFrame(
                        80, guardedMessage, new byte[] { 1 }));
                deny(() -> {
                    try {
                        guardedLease.awaitTerminal(1);
                    } catch (InterruptedException impossibleBeforeGuard) {
                        throw new AssertionError(impossibleBeforeGuard);
                    }
                });
                deny(() -> guardedLease.quarantineIfUnsettled(
                        new IllegalStateException("actor misuse")));
            } finally {
                RuntimeBlockingGuard.exitActorCarrier();
            }
            check(guardedWrites.get() == 0);
            check(guardedBridge.counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            check(guardedLease.snapshot().phase() == IpcTransportSessionLease.Phase.OPEN);
            guardedLease.requestClose();
            check(guardedLease.awaitTerminal(3000));
            check(guardedBridge.invariant());
        }

        {
            var guardedBridge = new IpcReceiptCompletionBridge(1);
            var guardedMessage = message(1, "guardedLegacy");
            var guardedOutcome = guardedBridge.admit(guardedMessage);
            AtomicInteger guardedWrites = new AtomicInteger();
            var guardedLease = new IpcTransportSessionLease(
                    guardedMessage, 801, 1, guardedBridge,
                    (value, preparedFrame) -> guardedWrites.incrementAndGet(),
                    () -> { },
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);

            RuntimeBlockingGuard.enterActorCarrier();
            try {
                deny(() -> {
                    try {
                        guardedLease.writeFrame(
                                801, guardedMessage, new byte[] { 1 });
                    } catch (IOException impossibleBeforeGuard) {
                        throw new AssertionError(impossibleBeforeGuard);
                    }
                });
            } finally {
                RuntimeBlockingGuard.exitActorCarrier();
            }
            check(guardedWrites.get() == 0);
            guardedBridge.rejectBeforeRemote(guardedMessage);
            check(guardedOutcome.toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.REJECTED_BEFORE_REMOTE);
            guardedLease.requestClose();
            check(guardedLease.awaitTerminal(3000));
            check(guardedBridge.invariant());
        }

        var bridge = new IpcReceiptCompletionBridge(2);
        var frame = message(1, "session");
        var done = bridge.admit(frame);
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        var transport = new IpcTransportSessionLease(frame, 81, 1,
                bridge, (value, preparedFrame) -> writes.incrementAndGet(),
                closes::incrementAndGet,
                IpcTransportSessionLeaseSelfTest::offCallerCleanup);
        transport.writeFrame(81, frame, new byte[] { 1 });
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
            try { transport.writeFrame(81, frame, new byte[] { 1 }); }
            catch (IOException impossible) { throw new AssertionError(impossible); }
        });

        var rejectionBridge = new IpcReceiptCompletionBridge(1);
        var rejectMessage = message(1, "reject");
        var rejected = rejectionBridge.admit(rejectMessage);
        var blocked = new IpcTransportSessionLease(rejectMessage, 15, 1,
                rejectionBridge, (value, preparedFrame) -> {
                    throw new AssertionError("stale session wrote");
                }, () -> { }, IpcTransportSessionLeaseSelfTest::offCallerCleanup);
        deny(() -> {
            try { blocked.writeFrame(14, rejectMessage, new byte[] { 1 }); } catch (IOException impossible) { throw new AssertionError(impossible); }
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
                failureBridge, (value, preparedFrame) -> {
                    throw new IOException("wire write was uncertain");
                }, failedCloses::incrementAndGet,
                IpcTransportSessionLeaseSelfTest::offCallerCleanup);
        try {
            failing.writeFrame(55, failingMessage, new byte[] { 1 });
            throw new AssertionError("write must throw");
        } catch (IOException expected) { check(expected.getMessage().contains("uncertain")); }
        check(failingFuture.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(failedCloses.get() == 1);
        check(failing.snapshot().phase() == IpcTransportSessionLease.Phase.RETIRED);
        check(failing.invariant());

        // VM-level host Errors are never converted into ordinary send
        // failures. The lease still requests close, releases the write pin,
        // and retires the committed correlation as OUTCOME_UNKNOWN.
        var fatalBridge = new IpcReceiptCompletionBridge(1);
        var fatalMessage = message(1, "fatal");
        var fatalFuture = fatalBridge.admit(fatalMessage);
        AtomicInteger fatalCloses = new AtomicInteger();
        var fatal = new IpcTransportSessionLease(fatalMessage, 551, 1,
                fatalBridge, (value, preparedFrame) -> {
                    throw new AssertionError("fatal writer");
                }, fatalCloses::incrementAndGet,
                IpcTransportSessionLeaseSelfTest::offCallerCleanup);
        try {
            fatal.writeFrame(551, fatalMessage, new byte[] { 1 });
            throw new AssertionError("fatal writer Error was swallowed");
        } catch (AssertionError expected) {
            check(expected.getMessage().equals("fatal writer"));
        }
        check(fatalFuture.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(fatalCloses.get() == 1);
        check(fatal.snapshot().phase() == IpcTransportSessionLease.Phase.RETIRED);
        check(fatal.snapshot().activeWrites() == 0);
        check(fatal.invariant());

        // A failed physical close is QUARANTINED, never marked reusable.
        var quarantineBridge = new IpcReceiptCompletionBridge(1);
        var quarantinedMessage = message(1, "quarantine");
        var quarantineFuture = quarantineBridge.admit(quarantinedMessage);
        var quarantine = new IpcTransportSessionLease(quarantinedMessage, 56, 1,
                quarantineBridge, (value, preparedFrame) -> { },
                () -> { throw new IOException("close not confirmed"); },
                IpcTransportSessionLeaseSelfTest::offCallerCleanup);
        quarantine.writeFrame(56, quarantinedMessage, new byte[] { 1 });
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
                recoveryBridge, (value, preparedFrame) -> { }, () -> {
                    if (closeAttempts.incrementAndGet() == 1) {
                        throw new IOException("first close was unconfirmed");
                    }
                }, IpcTransportSessionLeaseSelfTest::offCallerCleanup);
        recovery.writeFrame(57, recoveryMessage, new byte[] { 1 });
        recovery.requestClose();
        check(recovery.snapshot().phase() == IpcTransportSessionLease.Phase.QUARANTINED);
        check(!recoveryResult.toCompletableFuture().isDone());
        deny(() -> {
            try { recovery.writeFrame(57, recoveryMessage, new byte[] { 1 }); }
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
        // Misconfigured caller-runs cleanup is detected before physical
        // close and quarantined instead of blocking the requesting carrier.
        var inlineBridge = new IpcReceiptCompletionBridge(1);
        var inlineMessage = message(1, "inline");
        var inlineFuture = inlineBridge.admit(inlineMessage);
        AtomicInteger inlineCloses = new AtomicInteger();
        var inline = new IpcTransportSessionLease(inlineMessage, 580, 1,
                inlineBridge, (value, preparedFrame) -> { }, inlineCloses::incrementAndGet, Runnable::run);
        inline.writeFrame(580, inlineMessage, new byte[] { 1 });
        inline.requestClose();
        check(inline.snapshot().phase() == IpcTransportSessionLease.Phase.QUARANTINED);
        check(inlineCloses.get() == 0);
        check(!inlineFuture.toCompletableFuture().isDone());
        check(inline.closeFailure() instanceof IllegalStateException);
        check(inline.invariant());

        // A scheduler can violate the host-worker contract without running
        // inline: executing later on a different actor carrier is equally
        // forbidden. The lease must quarantine before physical close.
        var carrierCleanupBridge = new IpcReceiptCompletionBridge(1);
        var carrierCleanupMessage = message(1, "carrierCleanup");
        var carrierCleanupFuture = carrierCleanupBridge.admit(carrierCleanupMessage);
        AtomicReference<Runnable> actorCarrierCleanup = new AtomicReference<>();
        AtomicInteger actorCarrierCloses = new AtomicInteger();
        var carrierCleanup = new IpcTransportSessionLease(
                carrierCleanupMessage, 581, 1,
                carrierCleanupBridge, (value, preparedFrame) -> { },
                actorCarrierCloses::incrementAndGet,
                cleanup -> {
                    if (!actorCarrierCleanup.compareAndSet(null, cleanup)) {
                        throw new IllegalStateException("cleanup queue overflow");
                    }
                });
        carrierCleanup.writeFrame(581, carrierCleanupMessage, new byte[] { 1 });
        carrierCleanup.requestClose();
        check(carrierCleanup.snapshot().phase()
                == IpcTransportSessionLease.Phase.FINALIZING);
        CountDownLatch cleanupMonitorHeld = new CountDownLatch(1);
        CountDownLatch releaseCleanupMonitor = new CountDownLatch(1);
        Thread cleanupMonitorHolder = new Thread(() -> {
            synchronized (carrierCleanup) {
                cleanupMonitorHeld.countDown();
                try {
                    releaseCleanupMonitor.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }, "ipc-test-cleanup-monitor-holder");
        cleanupMonitorHolder.start();
        check(cleanupMonitorHeld.await(2, TimeUnit.SECONDS));

        AtomicReference<Throwable> actorCleanupFailure = new AtomicReference<>();
        Thread actorCleanupWorker = new Thread(() -> {
            RuntimeBlockingGuard.enterActorCarrier();
            try {
                actorCarrierCleanup.get().run();
            } catch (Throwable failure) {
                actorCleanupFailure.set(failure);
            } finally {
                RuntimeBlockingGuard.exitActorCarrier();
            }
        }, "ipc-test-illegal-actor-cleanup");
        actorCleanupWorker.start();
        actorCleanupWorker.join(1000);
        // The lifecycle monitor is deliberately still held by another thread:
        // a guard placed inside synchronized(this) would strand this carrier.
        check(!actorCleanupWorker.isAlive());
        check(actorCleanupFailure.get() instanceof IllegalStateException);
        check(actorCleanupFailure.get().getMessage().contains(
                "cannot block an actor carrier"));
        check(actorCarrierCloses.get() == 0);

        releaseCleanupMonitor.countDown();
        cleanupMonitorHolder.join(3000);
        check(!cleanupMonitorHolder.isAlive());
        check(carrierCleanup.snapshot().phase()
                == IpcTransportSessionLease.Phase.FINALIZING);
        check(carrierCleanup.quarantineIfUnsettled(actorCleanupFailure.get()));
        check(carrierCleanup.snapshot().phase()
                == IpcTransportSessionLease.Phase.QUARANTINED);
        check(!carrierCleanupFuture.toCompletableFuture().isDone());
        check(carrierCleanup.closeFailure() == actorCleanupFailure.get());
        check(carrierCleanup.invariant());

        var deferredBridge = new IpcReceiptCompletionBridge(1);
        var deferredMessage = message(1, "deferred");
        var deferredResult = deferredBridge.admit(deferredMessage);
        AtomicReference<Runnable> queuedCleanup = new AtomicReference<>();
        AtomicInteger deferredCloses = new AtomicInteger();
        var deferred = new IpcTransportSessionLease(deferredMessage, 58, 1,
                deferredBridge, (value, preparedFrame) -> { }, deferredCloses::incrementAndGet,
                cleanup -> {
                    if (!queuedCleanup.compareAndSet(null, cleanup)) {
                        throw new IllegalStateException("cleanup queue overflow");
                    }
                });
        deferred.writeFrame(58, deferredMessage, new byte[] { 1 });
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
                deniedCleanupBridge, (value, preparedFrame) -> { }, () -> { },
                cleanup -> { throw new IllegalStateException("host cleanup budget denied"); });
        deniedCleanup.writeFrame(59, deniedCleanupMessage, new byte[] { 1 });
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
        var staleGate = new IpcTransportSessionLease(staleMessage, 60, 1,
                staleBridge, (value, preparedFrame) -> { }, actualCloses::incrementAndGet, cleanup -> {
                    if (cleanupSchedules.incrementAndGet() == 1) {
                        priorTask.set(cleanup);
                        throw new IllegalStateException("schedule admission uncertain");
                    }
                    resumedTask.set(cleanup);
                });
        staleGate.writeFrame(60, staleMessage, new byte[] { 1 });
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

        // Reusing both session text and FD generation cannot grant an old
        // route/capability authority over a new logical transport epoch.
        var epochBridge = new IpcReceiptCompletionBridge(2);
        var previousRoute = message(1, "shared");
        var upgradedRoute = new IpcReceiptCorrelation.Envelope(
                "tenant", "sender", "worker", "shared", "a".repeat(64),
                1, 2, 4, 5, 2, 2);
        var oldReceipt = epochBridge.admit(previousRoute);
        var newReceipt = epochBridge.admit(upgradedRoute);
        var prior = new IpcTransportSessionLease(previousRoute, 77, 1,
                epochBridge, (ignoredFrame, preparedFrame) -> { }, () -> { },
                IpcTransportSessionLeaseSelfTest::offCallerCleanup);
        deny(() -> {
            try {
                prior.writeFrame(77, upgradedRoute, new byte[] { 1 });
            } catch (IOException impossible) { throw new AssertionError(impossible); }
        });
        prior.writeFrame(77, previousRoute, new byte[] { 1 });
        prior.requestClose();
        check(oldReceipt.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        check(!newReceipt.toCompletableFuture().isDone());
        check(epochBridge.counters().equals(new IpcReceiptCorrelation.Counters(1, 1)));
        var successor = new IpcTransportSessionLease(upgradedRoute, 77, 1,
                epochBridge, (ignoredFrame, preparedFrame) -> { }, () -> { },
                IpcTransportSessionLeaseSelfTest::offCallerCleanup);
        successor.writeFrame(77, upgradedRoute, new byte[] { 1 });
        epochBridge.acceptVerified(upgradedRoute, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
        epochBridge.acceptVerified(upgradedRoute, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
        check(newReceipt.toCompletableFuture().join()
                == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
        successor.requestClose();
        check(epochBridge.invariant());

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
                    candidateBridge, (value, preparedFrame) -> {
                        entered.countDown();
                        try {
                            if (!release.await(3, TimeUnit.SECONDS))
                                throw new IOException("test write latch expired");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                    }, closed::incrementAndGet,
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            long epoch = trial + 1L;
            Thread writer = new Thread(() -> {
                try {
                    candidate.writeFrame(epoch, candidateMessage, new byte[] { 1 });
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
                    contenderBridge, (x, preparedFrame) -> wrote.incrementAndGet(), closeCount::incrementAndGet,
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            long epoch = trial + 1L;
            Thread sender = new Thread(() -> {
                try {
                    start.await();
                    contender.writeFrame(epoch, contenderMessage, new byte[] { 1 });
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
