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

    private record BoundLease(
            IpcReceiptCompletionBridge bridge,
            Object owner,
            long generation,
            IpcTransportSessionLease lease) {

        IpcTransportSessionLease.WriteAttempt send(
                IpcReceiptCorrelation.Envelope message) {
            return lease.sendPreparedFrame(generation, message, new byte[] { 1 });
        }

        void accept(
                IpcReceiptCorrelation.Envelope message,
                IpcReceiptCorrelation.Stage stage) {
            bridge.acceptVerified(owner, message, generation, stage);
        }
    }

    private static IpcReceiptCorrelation.Envelope message(long id, String session) {
        return new IpcReceiptCorrelation.Envelope("tenant", "sender", "worker",
                session, "a".repeat(64), 1, 2, 3, 4, id, id);
    }

    private static BoundLease boundLease(
            IpcReceiptCorrelation.Envelope trustedEpoch,
            long generation,
            int capacity,
            int writeLimit,
            IpcTransportSessionLease.FrameWriter writer,
            IpcTransportSessionLease.PhysicalCloser closer,
            IpcTransportSessionLease.CleanupScheduler scheduler) {
        var bridge = new IpcReceiptCompletionBridge(capacity);
        Object owner = new Object();
        bridge.claimPhysicalTransport(owner, trustedEpoch, generation);
        var lease = new IpcTransportSessionLease(
                trustedEpoch, generation, writeLimit, bridge, owner,
                writer, closer, scheduler);
        return new BoundLease(bridge, owner, generation, lease);
    }

    private static void check(boolean value) {
        ++assertions;
        if (!value) throw new AssertionError("IPC transport physical lifetime invariant");
    }

    private static void deny(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException | IllegalArgumentException expected) {
            ++assertions;
            return;
        }
        throw new AssertionError("invalid transport admission was allowed");
    }

    private static void offCallerCleanup(Runnable cleanup) {
        Thread worker = new Thread(cleanup, "ipc-cleanup-selftest");
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("cleanup self-test interrupted", interrupted);
        }
    }

    public static void main(String[] ignored) throws Exception {
        // Every lease is physically owner-bound by construction; there is no
        // nullable compatibility mode that can fall back to unbound retirement.
        try {
            new IpcTransportSessionLease(
                    message(1, "missingOwner"), 79, 1,
                    new IpcReceiptCompletionBridge(1), null,
                    (value, preparedFrame) -> { },
                    () -> { },
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            throw new AssertionError("IPC lease accepted a null physical owner");
        } catch (NullPointerException expected) {
            check(expected.getMessage().contains("physicalOwner"));
        }

        // Defense in depth: the package-local lease rejects actor carriers
        // before admission, waits, quarantine, or diagnostic monitor entry.
        {
            var guardedMessage = message(1, "guarded");
            AtomicInteger guardedWrites = new AtomicInteger();
            var guarded = boundLease(
                    guardedMessage, 80, 1, 1,
                    (value, preparedFrame) -> guardedWrites.incrementAndGet(),
                    () -> { },
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);

            RuntimeBlockingGuard.enterActorCarrier();
            try {
                deny(() -> guarded.send(guardedMessage));
                deny(() -> {
                    try {
                        guarded.lease().awaitTerminal(1);
                    } catch (InterruptedException impossibleBeforeGuard) {
                        throw new AssertionError(impossibleBeforeGuard);
                    }
                });
                deny(() -> guarded.lease().quarantineIfUnsettled(
                        new IllegalStateException("actor misuse")));
            } finally {
                RuntimeBlockingGuard.exitActorCarrier();
            }
            check(guardedWrites.get() == 0);
            check(guarded.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            check(guarded.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.OPEN);
            guarded.lease().requestClose();
            check(guarded.lease().awaitTerminal(3000));
            check(guarded.bridge().invariant());
        }

        // Successful owner-bound admission, physical write and authenticated
        // ACK/terminal completion.
        {
            var frame = message(1, "session");
            AtomicInteger writes = new AtomicInteger();
            AtomicInteger closes = new AtomicInteger();
            var transport = boundLease(
                    frame, 81, 2, 1,
                    (value, preparedFrame) -> writes.incrementAndGet(),
                    closes::incrementAndGet,
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            var attempt = transport.send(frame);
            check(writes.get() == 1);
            check(!attempt.outcome().toCompletableFuture().isDone());
            transport.accept(frame, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            transport.accept(frame, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(attempt.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(transport.lease().invariant());
            transport.lease().requestClose();
            check(closes.get() == 1);
            check(transport.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            transport.lease().requestClose();
            check(closes.get() == 1);
            deny(() -> transport.send(frame));
        }

        // Adversarial terminal callback: a dependent host consumer blocks
        // after receipt retirement. That must not postpone physical RETIRED,
        // keep cleanupExecuting pinned, or prevent the supervisor wait.
        {
            var frame = message(1, "blockedTerminalCallback");
            CountDownLatch callbackEntered = new CountDownLatch(1);
            CountDownLatch releaseCallback = new CountDownLatch(1);
            CountDownLatch callbackFinished = new CountDownLatch(1);
            AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
            AtomicReference<Thread> cleanupThread = new AtomicReference<>();
            var transport = boundLease(
                    frame, 812, 1, 1,
                    (value, preparedFrame) -> { },
                    () -> { },
                    cleanup -> {
                        Thread worker = new Thread(
                                cleanup, "ipc-test-blocked-completion-cleanup");
                        worker.setDaemon(true);
                        cleanupThread.set(worker);
                        worker.start();
                    });
            var attempt = transport.send(frame);
            attempt.outcome().thenAccept(stage -> {
                callbackEntered.countDown();
                try {
                    if (!releaseCallback.await(5, TimeUnit.SECONDS)) {
                        callbackFailure.compareAndSet(null,
                                new AssertionError("blocked receipt callback timed out"));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    callbackFailure.compareAndSet(null, interrupted);
                } finally {
                    callbackFinished.countDown();
                }
            });
            transport.lease().requestClose();
            try {
                check(callbackEntered.await(3, TimeUnit.SECONDS));
                check(callbackFinished.getCount() == 1);
                check(transport.lease().awaitTerminal(1000));
                check(transport.lease().snapshot().phase()
                        == IpcTransportSessionLease.Phase.RETIRED);
                check(transport.lease().invariant());
                check(transport.bridge().counters().pending() == 0);
                deny(() -> transport.send(frame));
            } finally {
                releaseCallback.countDown();
            }
            check(callbackFinished.await(3, TimeUnit.SECONDS));
            Thread worker = cleanupThread.get();
            check(worker != null);
            worker.join(3000);
            check(!worker.isAlive());
            check(callbackFailure.get() == null);
            check(attempt.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        }

        // Stale physical generation is rejected before correlation admission.
        {
            var rejectMessage = message(1, "reject");
            var blocked = boundLease(
                    rejectMessage, 15, 1, 1,
                    (value, preparedFrame) -> {
                        throw new AssertionError("stale session wrote");
                    },
                    () -> { },
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            deny(() -> blocked.lease().sendPreparedFrame(
                    14, rejectMessage, new byte[] { 1 }));
            check(blocked.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            blocked.lease().requestClose();
            check(blocked.lease().awaitTerminal(3000));
            check(blocked.lease().invariant());
        }

        // Ordinary writer failure is returned diagnostically while receipt
        // authority becomes local transport-loss uncertainty after close.
        {
            var failingMessage = message(1, "failed");
            AtomicInteger failedCloses = new AtomicInteger();
            var failing = boundLease(
                    failingMessage, 55, 1, 1,
                    (value, preparedFrame) -> {
                        throw new IOException("wire write was uncertain");
                    },
                    failedCloses::incrementAndGet,
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            var attempt = failing.send(failingMessage);
            check(attempt.error() instanceof IOException);
            check(attempt.error().getMessage().contains("uncertain"));
            check(attempt.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(failedCloses.get() == 1);
            check(failing.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            check(failing.lease().invariant());
        }

        // VM-level Errors propagate rather than being converted to ordinary
        // send failures. Close still drains the write pin and retires receipts.
        {
            var fatalMessage = message(1, "fatal");
            AtomicInteger fatalCloses = new AtomicInteger();
            var fatal = boundLease(
                    fatalMessage, 551, 1, 1,
                    (value, preparedFrame) -> {
                        throw new AssertionError("fatal writer");
                    },
                    fatalCloses::incrementAndGet,
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            try {
                fatal.send(fatalMessage);
                throw new AssertionError("fatal writer Error was swallowed");
            } catch (AssertionError expected) {
                check(expected.getMessage().equals("fatal writer"));
            }
            check(fatalCloses.get() == 1);
            check(fatal.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            check(fatal.lease().snapshot().activeWrites() == 0);
            check(fatal.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            check(fatal.lease().invariant());
        }

        // Failed physical close is quarantined and the outcome remains pending.
        {
            var quarantinedMessage = message(1, "quarantine");
            var quarantine = boundLease(
                    quarantinedMessage, 56, 1, 1,
                    (value, preparedFrame) -> { },
                    () -> { throw new IOException("close not confirmed"); },
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            var attempt = quarantine.send(quarantinedMessage);
            quarantine.lease().requestClose();
            check(quarantine.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(!quarantine.lease().snapshot().physicalCloseConfirmed());
            check(!attempt.outcome().toCompletableFuture().isDone());
            check(quarantine.lease().closeFailure() instanceof IOException);
            check(quarantine.lease().invariant());
        }

        // Explicit supervisor retry never reopens send admission.
        {
            var recoveryMessage = message(1, "recovery");
            AtomicInteger closeAttempts = new AtomicInteger();
            var recovery = boundLease(
                    recoveryMessage, 57, 1, 1,
                    (value, preparedFrame) -> { },
                    () -> {
                        if (closeAttempts.incrementAndGet() == 1) {
                            throw new IOException("first close was unconfirmed");
                        }
                    },
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            var attempt = recovery.send(recoveryMessage);
            recovery.lease().requestClose();
            check(recovery.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(!attempt.outcome().toCompletableFuture().isDone());
            deny(() -> recovery.send(recoveryMessage));
            recovery.lease().retryQuarantinedClose();
            check(closeAttempts.get() == 2);
            check(recovery.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            check(attempt.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(recovery.lease().invariant());
            deny(recovery.lease()::retryQuarantinedClose);
        }

        // Caller-runs cleanup is rejected and quarantined.
        {
            var inlineMessage = message(1, "inline");
            AtomicInteger inlineCloses = new AtomicInteger();
            var inline = boundLease(
                    inlineMessage, 580, 1, 1,
                    (value, preparedFrame) -> { },
                    inlineCloses::incrementAndGet,
                    Runnable::run);
            var attempt = inline.send(inlineMessage);
            inline.lease().requestClose();
            check(inline.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(inlineCloses.get() == 0);
            check(!attempt.outcome().toCompletableFuture().isDone());
            check(inline.lease().closeFailure() instanceof IllegalStateException);
            check(inline.lease().invariant());
        }

        // A scheduler that later runs cleanup on an actor carrier must fail
        // before any lifecycle-monitor acquisition or physical close.
        {
            var carrierCleanupMessage = message(1, "carrierCleanup");
            AtomicReference<Runnable> actorCarrierCleanup = new AtomicReference<>();
            AtomicInteger actorCarrierCloses = new AtomicInteger();
            var carrierCleanup = boundLease(
                    carrierCleanupMessage, 581, 1, 1,
                    (value, preparedFrame) -> { },
                    actorCarrierCloses::incrementAndGet,
                    cleanup -> {
                        if (!actorCarrierCleanup.compareAndSet(null, cleanup)) {
                            throw new IllegalStateException("cleanup queue overflow");
                        }
                    });
            var attempt = carrierCleanup.send(carrierCleanupMessage);
            carrierCleanup.lease().requestClose();
            check(carrierCleanup.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.FINALIZING);

            CountDownLatch cleanupMonitorHeld = new CountDownLatch(1);
            CountDownLatch releaseCleanupMonitor = new CountDownLatch(1);
            Thread cleanupMonitorHolder = new Thread(() -> {
                synchronized (carrierCleanup.lease()) {
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

            Runnable[] actorDiagnosticCalls = new Runnable[] {
                    () -> carrierCleanup.lease().snapshot(),
                    () -> carrierCleanup.lease().closeFailure(),
                    () -> carrierCleanup.lease().invariant()
            };
            for (Runnable diagnosticCall : actorDiagnosticCalls) {
                AtomicReference<Throwable> actorDiagnosticFailure = new AtomicReference<>();
                Thread actorDiagnosticWorker = new Thread(() -> {
                    RuntimeBlockingGuard.enterActorCarrier();
                    try {
                        diagnosticCall.run();
                    } catch (Throwable failure) {
                        actorDiagnosticFailure.set(failure);
                    } finally {
                        RuntimeBlockingGuard.exitActorCarrier();
                    }
                }, "ipc-test-illegal-actor-diagnostic");
                actorDiagnosticWorker.start();
                actorDiagnosticWorker.join(1000);
                check(!actorDiagnosticWorker.isAlive());
                check(actorDiagnosticFailure.get() instanceof IllegalStateException);
                check(actorDiagnosticFailure.get().getMessage().contains(
                        "cannot block an actor carrier"));
            }

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
            check(!actorCleanupWorker.isAlive());
            check(actorCleanupFailure.get() instanceof IllegalStateException);
            check(actorCleanupFailure.get().getMessage().contains(
                    "cannot block an actor carrier"));
            check(actorCarrierCloses.get() == 0);

            releaseCleanupMonitor.countDown();
            cleanupMonitorHolder.join(3000);
            check(!cleanupMonitorHolder.isAlive());
            check(carrierCleanup.lease().quarantineIfUnsettled(
                    actorCleanupFailure.get()));
            check(carrierCleanup.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(!attempt.outcome().toCompletableFuture().isDone());
            check(carrierCleanup.lease().invariant());
        }

        // Deferred cleanup runs once even if the scheduler callback is duplicated.
        {
            var deferredMessage = message(1, "deferred");
            AtomicReference<Runnable> queuedCleanup = new AtomicReference<>();
            AtomicInteger deferredCloses = new AtomicInteger();
            var deferred = boundLease(
                    deferredMessage, 58, 1, 1,
                    (value, preparedFrame) -> { },
                    deferredCloses::incrementAndGet,
                    cleanup -> {
                        if (!queuedCleanup.compareAndSet(null, cleanup)) {
                            throw new IllegalStateException("cleanup queue overflow");
                        }
                    });
            var attempt = deferred.send(deferredMessage);
            deferred.lease().requestClose();
            check(deferred.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.FINALIZING);
            check(deferredCloses.get() == 0);
            check(!attempt.outcome().toCompletableFuture().isDone());
            queuedCleanup.get().run();
            queuedCleanup.get().run();
            check(deferredCloses.get() == 1);
            check(deferred.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            check(attempt.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        }

        // Cleanup admission failure is quarantined.
        {
            var deniedMessage = message(1, "noWorker");
            var denied = boundLease(
                    deniedMessage, 59, 1, 1,
                    (value, preparedFrame) -> { },
                    () -> { },
                    cleanup -> {
                        throw new IllegalStateException("host cleanup budget denied");
                    });
            var attempt = denied.send(deniedMessage);
            denied.lease().requestClose();
            check(denied.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(!attempt.outcome().toCompletableFuture().isDone());
            check(denied.lease().closeFailure() instanceof IllegalStateException);
            check(denied.lease().invariant());
        }

        // A stale cleanup task cannot steal a later explicit recovery attempt.
        {
            var staleMessage = message(1, "staleCleanup");
            AtomicInteger cleanupSchedules = new AtomicInteger();
            AtomicInteger actualCloses = new AtomicInteger();
            AtomicReference<Runnable> priorTask = new AtomicReference<>();
            AtomicReference<Runnable> resumedTask = new AtomicReference<>();
            var stale = boundLease(
                    staleMessage, 60, 1, 1,
                    (value, preparedFrame) -> { },
                    actualCloses::incrementAndGet,
                    cleanup -> {
                        if (cleanupSchedules.incrementAndGet() == 1) {
                            priorTask.set(cleanup);
                            throw new IllegalStateException("schedule admission uncertain");
                        }
                        resumedTask.set(cleanup);
                    });
            var attempt = stale.send(staleMessage);
            stale.lease().requestClose();
            check(stale.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            stale.lease().retryQuarantinedClose();
            priorTask.get().run();
            check(actualCloses.get() == 0);
            check(!attempt.outcome().toCompletableFuture().isDone());
            resumedTask.get().run();
            check(actualCloses.get() == 1);
            check(stale.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            check(attempt.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(stale.lease().invariant());
        }

        // Route/grant upgrade requires a fresh physical bridge. An old lease
        // cannot send the upgraded epoch even when session text/generation match.
        {
            var previousRoute = message(1, "shared");
            var upgradedRoute = new IpcReceiptCorrelation.Envelope(
                    "tenant", "sender", "worker", "shared", "a".repeat(64),
                    1, 2, 4, 5, 2, 2);
            var prior = boundLease(
                    previousRoute, 77, 1, 1,
                    (ignoredFrame, preparedFrame) -> { },
                    () -> { },
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            deny(() -> prior.send(upgradedRoute));
            check(prior.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            var oldAttempt = prior.send(previousRoute);
            prior.lease().requestClose();
            check(oldAttempt.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);

            var successor = boundLease(
                    upgradedRoute, 77, 1, 1,
                    (ignoredFrame, preparedFrame) -> { },
                    () -> { },
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            var newAttempt = successor.send(upgradedRoute);
            successor.accept(
                    upgradedRoute, IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            successor.accept(
                    upgradedRoute, IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(newAttempt.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            successor.lease().requestClose();
            check(successor.bridge().invariant());
        }

        // Close never recycles a descriptor during a real pinned host write.
        for (int trial = 0; trial < 512; ++trial) {
            var candidateMessage = message(1, "race");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger closed = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<IpcTransportSessionLease.WriteAttempt> result =
                    new AtomicReference<>();
            long generation = trial + 1L;
            var candidate = boundLease(
                    candidateMessage, generation, 1, 1,
                    (value, preparedFrame) -> {
                        entered.countDown();
                        try {
                            if (!release.await(3, TimeUnit.SECONDS)) {
                                throw new IOException("test write latch expired");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                        }
                    },
                    closed::incrementAndGet,
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            Thread writer = new Thread(() -> {
                try {
                    result.set(candidate.send(candidateMessage));
                } catch (Throwable problem) {
                    failure.set(problem);
                }
            }, "ipc-write-close-race");
            writer.start();
            check(entered.await(3, TimeUnit.SECONDS));
            candidate.lease().requestClose();
            check(closed.get() == 0);
            check(candidate.lease().snapshot().activeWrites() == 1);
            release.countDown();
            writer.join();
            check(failure.get() == null);
            check(closed.get() == 1);
            check(candidate.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            check(result.get().outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(candidate.bridge().invariant());
            check(candidate.lease().invariant());
        }

        // Competing close and atomic admission linearize cleanly.
        for (int trial = 0; trial < 512; ++trial) {
            var contenderMessage = message(1, "compete");
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger closeCount = new AtomicInteger();
            AtomicInteger wrote = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<>();
            AtomicReference<IpcTransportSessionLease.WriteAttempt> result =
                    new AtomicReference<>();
            long generation = trial + 1L;
            var contender = boundLease(
                    contenderMessage, generation, 1, 1,
                    (value, preparedFrame) -> wrote.incrementAndGet(),
                    closeCount::incrementAndGet,
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            Thread sender = new Thread(() -> {
                try {
                    start.await();
                    result.set(contender.send(contenderMessage));
                } catch (IpcTransportSessionLease.PreDispatchRejectedException expectedClosed) {
                    // Close won before admission; no correlation was created.
                } catch (Throwable bad) {
                    error.compareAndSet(null, bad);
                }
            }, "ipc-close-send-race");
            Thread shutdown = new Thread(() -> {
                try {
                    start.await();
                    contender.lease().requestClose();
                } catch (Throwable bad) {
                    error.compareAndSet(null, bad);
                }
            }, "ipc-close-racer");
            sender.start();
            shutdown.start();
            start.countDown();
            sender.join();
            shutdown.join();
            check(error.get() == null);
            check(closeCount.get() == 1);
            check(contender.lease().snapshot().activeWrites() == 0);
            check(contender.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            if (result.get() == null) {
                check(contender.bridge().counters().equals(
                        new IpcReceiptCorrelation.Counters(0, 0)));
                check(wrote.get() == 0);
            } else {
                check(result.get().outcome().toCompletableFuture().join()
                        == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
                check(wrote.get() == 1);
            }
            check(contender.bridge().invariant());
        }

        System.out.println("JAVA_IPC_PHYSICAL_SESSION_HOST_PASS assertions=" + assertions
                + " races=1024");
    }
}
