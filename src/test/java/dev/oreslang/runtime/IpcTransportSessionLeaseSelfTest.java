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
            IpcTransportSessionLease lease,
            IpcReceiptCompletionBridge bridge,
            Object owner,
            long generation) { }

    private static IpcReceiptCorrelation.Envelope message(long id, String session) {
        return new IpcReceiptCorrelation.Envelope("tenant", "sender", "worker",
                session, "a".repeat(64), 1, 2, 3, 4, id, id);
    }

    private static BoundLease bind(
            IpcReceiptCorrelation.Envelope epoch,
            long generation,
            int capacity,
            IpcTransportSessionLease.FrameWriter writer,
            IpcTransportSessionLease.PhysicalCloser closer,
            IpcTransportSessionLease.CleanupScheduler scheduler) {
        var bridge = new IpcReceiptCompletionBridge(capacity);
        Object owner = new Object();
        bridge.claimPhysicalTransport(owner, epoch, generation);
        var lease = new IpcTransportSessionLease(
                epoch, generation, 1, bridge, owner, writer, closer, scheduler);
        return new BoundLease(lease, bridge, owner, generation);
    }

    private static IpcTransportSessionLease.WriteAttempt send(
            BoundLease bound,
            IpcReceiptCorrelation.Envelope message) {
        return bound.lease().sendPreparedFrame(
                bound.generation(), message, new byte[] { 1 });
    }

    private static void check(boolean value) {
        ++assertions;
        if (!value) throw new AssertionError("IPC transport physical lifetime invariant");
    }

    private static void deny(Runnable action) {
        try { action.run(); }
        catch (IllegalStateException | IllegalArgumentException expected) {
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
        // Every lease is physically owner-bound. There is no compatibility
        // constructor that can synthesize or defer physical authority.
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

        // Defense in depth: package-local lease APIs themselves enforce the
        // actor-carrier blocking boundary before admission or monitor entry.
        {
            var guardedMessage = message(1, "guarded");
            AtomicInteger guardedWrites = new AtomicInteger();
            var guarded = bind(
                    guardedMessage, 80, 1,
                    (value, preparedFrame) -> guardedWrites.incrementAndGet(),
                    () -> { },
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);

            RuntimeBlockingGuard.enterActorCarrier();
            try {
                deny(() -> send(guarded, guardedMessage));
                deny(guarded.lease()::requestClose);
                deny(() -> {
                    try {
                        guarded.lease().awaitTerminal(1);
                    } catch (InterruptedException impossibleBeforeGuard) {
                        throw new AssertionError(impossibleBeforeGuard);
                    }
                });
                deny(() -> guarded.lease().quarantineIfUnsettled(
                        new IllegalStateException("actor carrier quarantine")));
            } finally {
                RuntimeBlockingGuard.exitActorCarrier();
            }
            check(guardedWrites.get() == 0);
            check(guarded.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            check(guarded.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.OPEN);

            // Hold the lease monitor on a host thread and prove every
            // diagnostic rejects an actor carrier before monitor entry.
            CountDownLatch diagnosticMonitorHeld = new CountDownLatch(1);
            CountDownLatch releaseDiagnosticMonitor = new CountDownLatch(1);
            AtomicReference<Throwable> diagnosticHolderFailure =
                    new AtomicReference<>();
            Thread diagnosticHolder = new Thread(() -> {
                synchronized (guarded.lease()) {
                    diagnosticMonitorHeld.countDown();
                    try {
                        if (!releaseDiagnosticMonitor.await(3, TimeUnit.SECONDS)) {
                            throw new AssertionError(
                                    "diagnostic monitor hold timed out");
                        }
                    } catch (Throwable failure) {
                        diagnosticHolderFailure.set(failure);
                    }
                }
            }, "ipc-test-diagnostic-monitor-holder");
            diagnosticHolder.start();
            check(diagnosticMonitorHeld.await(3, TimeUnit.SECONDS));

            AtomicInteger diagnosticDenials = new AtomicInteger();
            AtomicReference<Throwable> diagnosticCarrierFailure =
                    new AtomicReference<>();
            Thread diagnosticCarrier = new Thread(() -> {
                RuntimeBlockingGuard.enterActorCarrier();
                try {
                    try {
                        guarded.lease().snapshot();
                        throw new AssertionError(
                                "actor carrier entered snapshot monitor");
                    } catch (IllegalStateException expected) {
                        diagnosticDenials.incrementAndGet();
                    }
                    try {
                        guarded.lease().closeFailure();
                        throw new AssertionError(
                                "actor carrier entered closeFailure monitor");
                    } catch (IllegalStateException expected) {
                        diagnosticDenials.incrementAndGet();
                    }
                    try {
                        guarded.lease().invariant();
                        throw new AssertionError(
                                "actor carrier entered invariant monitor");
                    } catch (IllegalStateException expected) {
                        diagnosticDenials.incrementAndGet();
                    }
                } catch (Throwable failure) {
                    diagnosticCarrierFailure.set(failure);
                } finally {
                    RuntimeBlockingGuard.exitActorCarrier();
                }
            }, "ipc-test-diagnostic-actor-carrier");
            diagnosticCarrier.start();
            diagnosticCarrier.join(500);
            check(!diagnosticCarrier.isAlive());
            check(diagnosticCarrierFailure.get() == null);
            check(diagnosticDenials.get() == 3);

            releaseDiagnosticMonitor.countDown();
            diagnosticHolder.join(3000);
            check(!diagnosticHolder.isAlive());
            check(diagnosticHolderFailure.get() == null);

            guarded.lease().requestClose();
            check(guarded.lease().awaitTerminal(3000));
            check(guarded.bridge().invariant());
        }

        // Happy path: admission and dispatch commitment are atomic with the
        // physical write pin and return the authoritative completion.
        {
            var frame = message(1, "session");
            AtomicInteger writes = new AtomicInteger();
            AtomicInteger closes = new AtomicInteger();
            var bound = bind(
                    frame, 81, 2,
                    (value, preparedFrame) -> writes.incrementAndGet(),
                    closes::incrementAndGet,
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            var attempt = send(bound, frame);
            check(attempt.error() == null);
            check(writes.get() == 1);
            check(!attempt.outcome().toCompletableFuture().isDone());
            bound.bridge().acceptVerified(
                    bound.owner(), frame, bound.generation(),
                    IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            bound.bridge().acceptVerified(
                    bound.owner(), frame, bound.generation(),
                    IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(attempt.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(bound.lease().invariant());
            bound.lease().requestClose();
            check(closes.get() == 1);
            check(bound.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            bound.lease().requestClose();
            check(closes.get() == 1);
            deny(() -> send(bound, frame));
        }

        // Stale physical generations fail before admission or writer entry.
        {
            var rejectMessage = message(1, "reject");
            AtomicInteger writes = new AtomicInteger();
            var bound = bind(
                    rejectMessage, 15, 1,
                    (value, preparedFrame) -> writes.incrementAndGet(),
                    () -> { },
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            deny(() -> bound.lease().sendPreparedFrame(
                    14, rejectMessage, new byte[] { 1 }));
            check(writes.get() == 0);
            check(bound.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            bound.lease().requestClose();
            check(bound.lease().invariant());
        }

        // A failed committed write is indeterminate. Ordinary host failures
        // are returned diagnostically while the receipt completion remains
        // authoritative.
        {
            var failingMessage = message(1, "failed");
            AtomicInteger closes = new AtomicInteger();
            var bound = bind(
                    failingMessage, 55, 1,
                    (value, preparedFrame) -> {
                        throw new IOException("wire write was uncertain");
                    },
                    closes::incrementAndGet,
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            var attempt = send(bound, failingMessage);
            check(attempt.error() instanceof IOException);
            check(attempt.error().getMessage().contains("uncertain"));
            check(attempt.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(closes.get() == 1);
            check(bound.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            check(bound.lease().invariant());
        }

        // VM-level Errors are never converted into ordinary send failures.
        // Close still retires the committed receipt as OUTCOME_UNKNOWN.
        {
            var fatalMessage = message(1, "fatal");
            AtomicInteger closes = new AtomicInteger();
            var bound = bind(
                    fatalMessage, 551, 1,
                    (value, preparedFrame) -> {
                        throw new AssertionError("fatal writer");
                    },
                    closes::incrementAndGet,
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            try {
                send(bound, fatalMessage);
                throw new AssertionError("fatal writer Error was swallowed");
            } catch (AssertionError expected) {
                check(expected.getMessage().equals("fatal writer"));
            }
            check(closes.get() == 1);
            check(bound.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            check(bound.lease().snapshot().activeWrites() == 0);
            check(bound.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            check(bound.lease().invariant());
        }

        // A failed physical close is QUARANTINED, never marked reusable.
        {
            var quarantinedMessage = message(1, "quarantine");
            var bound = bind(
                    quarantinedMessage, 56, 1,
                    (value, preparedFrame) -> { },
                    () -> { throw new IOException("close not confirmed"); },
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            var attempt = send(bound, quarantinedMessage);
            check(attempt.error() == null);
            bound.lease().requestClose();
            check(bound.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(!bound.lease().snapshot().physicalCloseConfirmed());
            check(!attempt.outcome().toCompletableFuture().isDone());
            check(bound.lease().closeFailure() instanceof IOException);
            check(bound.lease().invariant());
        }

        // Once physical close has actually been invoked, an exception is
        // ambiguous: the descriptor may already be closed. Never invoke the
        // closer a second time or rely on idempotence of an arbitrary/native
        // descriptor wrapper.
        {
            var recoveryMessage = message(1, "recovery");
            AtomicInteger closeAttempts = new AtomicInteger();
            var bound = bind(
                    recoveryMessage, 57, 1,
                    (value, preparedFrame) -> { },
                    () -> {
                        closeAttempts.incrementAndGet();
                        throw new IOException("close outcome unconfirmed");
                    },
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            var attempt = send(bound, recoveryMessage);
            bound.lease().requestClose();
            check(bound.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(!attempt.outcome().toCompletableFuture().isDone());
            deny(() -> send(bound, recoveryMessage));
            deny(bound.lease()::retryQuarantinedClose);
            check(closeAttempts.get() == 1);
            check(bound.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(!bound.lease().snapshot().physicalCloseConfirmed());
            check(!attempt.outcome().toCompletableFuture().isDone());
            check(bound.lease().invariant());
        }

        // If scheduling/timeout quarantine is recorded first but an already
        // claimed physical close later fails, the descriptor-close failure is
        // authoritative. Do not leave a less relevant scheduler diagnostic as
        // the apparent reason this generation can never be reused.
        {
            var precedenceMessage = message(1, "closeFailurePrecedence");
            CountDownLatch writeEntered = new CountDownLatch(1);
            CountDownLatch releaseWrite = new CountDownLatch(1);
            AtomicReference<IpcTransportSessionLease.WriteAttempt> result =
                    new AtomicReference<>();
            AtomicReference<Throwable> senderFailure = new AtomicReference<>();
            var bound = bind(
                    precedenceMessage, 571, 1,
                    (value, preparedFrame) -> {
                        writeEntered.countDown();
                        try {
                            if (!releaseWrite.await(3, TimeUnit.SECONDS)) {
                                throw new IOException("precedence write timed out");
                            }
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IOException(interrupted);
                        }
                    },
                    () -> { },
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);

            Thread sender = new Thread(() -> {
                try {
                    result.set(send(bound, precedenceMessage));
                } catch (Throwable failure) {
                    senderFailure.set(failure);
                }
            }, "ipc-test-close-failure-precedence");
            sender.start();
            check(writeEntered.await(3, TimeUnit.SECONDS));

            bound.lease().requestClose();
            var schedulingFailure =
                    new IllegalStateException("cleanup scheduling failed first");
            check(bound.lease().quarantineIfUnsettled(schedulingFailure));
            check(bound.lease().closeFailure() == schedulingFailure);

            check(bound.lease().beginExternalPhysicalClose());
            var physicalFailure =
                    new IOException("physical descriptor close failed");
            bound.lease().completeExternalPhysicalClose(physicalFailure);
            check(bound.lease().closeFailure() == physicalFailure);
            check(bound.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(!bound.lease().snapshot().physicalCloseConfirmed());

            releaseWrite.countDown();
            sender.join(3000);
            check(!sender.isAlive());
            check(senderFailure.get() == null);
            check(result.get() != null);
            check(!result.get().outcome().toCompletableFuture().isDone());
            check(bound.lease().snapshot().activeWrites() == 0);
            deny(bound.lease()::retryQuarantinedClose);
            check(bound.lease().closeFailure() == physicalFailure);
            check(bound.lease().invariant());
        }

        // Misconfigured caller-runs cleanup is detected before physical close.
        {
            var inlineMessage = message(1, "inline");
            AtomicInteger inlineCloses = new AtomicInteger();
            var bound = bind(
                    inlineMessage, 580, 1,
                    (value, preparedFrame) -> { },
                    inlineCloses::incrementAndGet,
                    Runnable::run);
            var attempt = send(bound, inlineMessage);
            bound.lease().requestClose();
            check(bound.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(inlineCloses.get() == 0);
            check(!attempt.outcome().toCompletableFuture().isDone());
            check(bound.lease().closeFailure() instanceof IllegalStateException);
            check(bound.lease().invariant());
        }

        // A delayed cleanup callback routed onto an actor carrier must report
        // misuse lock-free even while the lease monitor is held elsewhere.
        {
            var carrierMessage = message(1, "carrierCleanup");
            AtomicReference<Runnable> actorCarrierCleanup = new AtomicReference<>();
            AtomicInteger closes = new AtomicInteger();
            var bound = bind(
                    carrierMessage, 581, 1,
                    (value, preparedFrame) -> { },
                    closes::incrementAndGet,
                    cleanup -> {
                        if (!actorCarrierCleanup.compareAndSet(null, cleanup)) {
                            throw new IllegalStateException("cleanup queue overflow");
                        }
                    });
            var attempt = send(bound, carrierMessage);
            bound.lease().requestClose();
            check(bound.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.FINALIZING);

            CountDownLatch leaseMonitorHeld = new CountDownLatch(1);
            CountDownLatch releaseLeaseMonitor = new CountDownLatch(1);
            AtomicReference<Throwable> monitorHolderFailure = new AtomicReference<>();
            Thread monitorHolder = new Thread(() -> {
                synchronized (bound.lease()) {
                    leaseMonitorHeld.countDown();
                    try {
                        if (!releaseLeaseMonitor.await(3, TimeUnit.SECONDS)) {
                            throw new AssertionError("lease monitor hold timed out");
                        }
                    } catch (Throwable failure) {
                        monitorHolderFailure.set(failure);
                    }
                }
            }, "ipc-test-lease-monitor-holder");
            monitorHolder.start();
            check(leaseMonitorHeld.await(3, TimeUnit.SECONDS));

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
            actorCleanupWorker.join(500);
            check(!actorCleanupWorker.isAlive());
            check(actorCleanupFailure.get() == null);
            check(closes.get() == 0);

            releaseLeaseMonitor.countDown();
            monitorHolder.join(3000);
            check(!monitorHolder.isAlive());
            check(monitorHolderFailure.get() == null);

            check(bound.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(!attempt.outcome().toCompletableFuture().isDone());
            check(bound.lease().closeFailure() instanceof IllegalStateException);
            check(bound.lease().closeFailure().getMessage().contains("actor carrier"));
            check(bound.lease().invariant());
        }

        // Duplicate scheduler callbacks are inert after the first successful
        // physical close/receipt retirement.
        {
            var deferredMessage = message(1, "deferred");
            AtomicReference<Runnable> queuedCleanup = new AtomicReference<>();
            AtomicInteger closes = new AtomicInteger();
            var bound = bind(
                    deferredMessage, 58, 1,
                    (value, preparedFrame) -> { },
                    closes::incrementAndGet,
                    cleanup -> {
                        if (!queuedCleanup.compareAndSet(null, cleanup)) {
                            throw new IllegalStateException("cleanup queue overflow");
                        }
                    });
            var attempt = send(bound, deferredMessage);
            bound.lease().requestClose();
            check(bound.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.FINALIZING);
            check(closes.get() == 0);
            queuedCleanup.get().run();
            queuedCleanup.get().run();
            check(closes.get() == 1);
            check(bound.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            check(attempt.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
        }

        // Scheduler admission failure quarantines without retiring receipts.
        {
            var deniedMessage = message(1, "noWorker");
            var bound = bind(
                    deniedMessage, 59, 1,
                    (value, preparedFrame) -> { },
                    () -> { },
                    cleanup -> {
                        throw new IllegalStateException("host cleanup budget denied");
                    });
            var attempt = send(bound, deniedMessage);
            bound.lease().requestClose();
            check(bound.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            check(!attempt.outcome().toCompletableFuture().isDone());
            check(bound.lease().closeFailure() instanceof IllegalStateException);
            check(bound.lease().invariant());
        }

        // A partially enqueued stale callback cannot steal a later explicit
        // recovery attempt or poison it from an actor carrier.
        {
            var staleMessage = message(1, "staleCleanup");
            AtomicInteger schedules = new AtomicInteger();
            AtomicInteger closes = new AtomicInteger();
            AtomicReference<Runnable> priorTask = new AtomicReference<>();
            AtomicReference<Runnable> resumedTask = new AtomicReference<>();
            var bound = bind(
                    staleMessage, 60, 1,
                    (value, preparedFrame) -> { },
                    closes::incrementAndGet,
                    cleanup -> {
                        if (schedules.incrementAndGet() == 1) {
                            priorTask.set(cleanup);
                            throw new IllegalStateException(
                                    "schedule admission uncertain");
                        }
                        resumedTask.set(cleanup);
                    });
            var attempt = send(bound, staleMessage);
            bound.lease().requestClose();
            check(bound.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.QUARANTINED);
            bound.lease().retryQuarantinedClose();

            AtomicReference<Throwable> staleActorFailure = new AtomicReference<>();
            Thread staleActor = new Thread(() -> {
                RuntimeBlockingGuard.enterActorCarrier();
                try {
                    priorTask.get().run();
                } catch (Throwable failure) {
                    staleActorFailure.set(failure);
                } finally {
                    RuntimeBlockingGuard.exitActorCarrier();
                }
            }, "ipc-test-stale-actor-cleanup");
            staleActor.start();
            staleActor.join(1000);
            check(!staleActor.isAlive());
            check(staleActorFailure.get() == null);
            check(closes.get() == 0);
            check(bound.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.FINALIZING);
            check(!attempt.outcome().toCompletableFuture().isDone());

            resumedTask.get().run();
            check(closes.get() == 1);
            check(bound.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            check(attempt.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(bound.lease().invariant());
        }

        // A stale logical transport epoch cannot use an old physical lease.
        // Reconnect allocates a fresh receipt bridge and physical generation.
        {
            var previousRoute = message(1, "shared");
            var upgradedRoute = new IpcReceiptCorrelation.Envelope(
                    "tenant", "sender", "worker", "shared", "a".repeat(64),
                    1, 2, 4, 5, 2, 2);
            var prior = bind(
                    previousRoute, 77, 1,
                    (ignoredFrame, preparedFrame) -> { },
                    () -> { },
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            deny(() -> prior.lease().sendPreparedFrame(
                    77, upgradedRoute, new byte[] { 1 }));
            check(prior.bridge().counters().equals(
                    new IpcReceiptCorrelation.Counters(0, 0)));
            var oldAttempt = send(prior, previousRoute);
            prior.lease().requestClose();
            check(oldAttempt.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);

            var successor = bind(
                    upgradedRoute, 78, 1,
                    (ignoredFrame, preparedFrame) -> { },
                    () -> { },
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            var newAttempt = send(successor, upgradedRoute);
            successor.bridge().acceptVerified(
                    successor.owner(), upgradedRoute, successor.generation(),
                    IpcReceiptCorrelation.Stage.REMOTE_ACCEPTED);
            successor.bridge().acceptVerified(
                    successor.owner(), upgradedRoute, successor.generation(),
                    IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            check(newAttempt.outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.HANDLER_COMMITTED);
            successor.lease().requestClose();
            check(successor.bridge().invariant());
        }

        // Close never recycles a descriptor while a host write is pinned.
        for (int trial = 0; trial < 512; ++trial) {
            var candidateMessage = message(1, "race");
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger closed = new AtomicInteger();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<IpcTransportSessionLease.WriteAttempt> attemptRef =
                    new AtomicReference<>();
            var candidate = bind(
                    candidateMessage, trial + 1L, 1,
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
                    attemptRef.set(send(candidate, candidateMessage));
                } catch (Throwable problem) {
                    failure.set(problem);
                }
            });
            writer.start();
            check(entered.await(3, TimeUnit.SECONDS));
            candidate.lease().requestClose();
            check(closed.get() == 0);
            check(candidate.lease().snapshot().activeWrites() == 1);
            release.countDown();
            writer.join();
            check(failure.get() == null);
            check(attemptRef.get() != null);
            check(closed.get() == 1);
            check(candidate.lease().snapshot().phase()
                    == IpcTransportSessionLease.Phase.RETIRED);
            check(attemptRef.get().outcome().toCompletableFuture().join()
                    == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            check(candidate.bridge().invariant());
            check(candidate.lease().invariant());
        }

        // Competing close and admission linearize. If send wins, the admitted
        // write drains and becomes OUTCOME_UNKNOWN. If close wins, there is no
        // correlation to retire and send is rejected before the writer.
        for (int trial = 0; trial < 512; ++trial) {
            var contenderMessage = message(1, "compete");
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger closeCount = new AtomicInteger();
            AtomicInteger wrote = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            AtomicReference<Throwable> error = new AtomicReference<>();
            AtomicReference<IpcTransportSessionLease.WriteAttempt> admitted =
                    new AtomicReference<>();
            var contender = bind(
                    contenderMessage, trial + 1L, 1,
                    (value, preparedFrame) -> wrote.incrementAndGet(),
                    closeCount::incrementAndGet,
                    IpcTransportSessionLeaseSelfTest::offCallerCleanup);
            Thread sender = new Thread(() -> {
                try {
                    start.await();
                    admitted.set(send(contender, contenderMessage));
                } catch (IpcTransportSessionLease.PreDispatchRejectedException expectedClosed) {
                    rejected.incrementAndGet();
                } catch (Throwable bad) {
                    error.compareAndSet(null, bad);
                }
            });
            Thread shutdown = new Thread(() -> {
                try {
                    start.await();
                    contender.lease().requestClose();
                } catch (Throwable bad) {
                    error.compareAndSet(null, bad);
                }
            });
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
            if (admitted.get() != null) {
                check(rejected.get() == 0);
                check(wrote.get() == 1);
                check(admitted.get().outcome().toCompletableFuture().join()
                        == IpcReceiptCorrelation.Stage.OUTCOME_UNKNOWN);
            } else {
                check(rejected.get() == 1);
                check(wrote.get() == 0);
                check(contender.bridge().counters().equals(
                        new IpcReceiptCorrelation.Counters(0, 0)));
            }
            check(contender.bridge().invariant());
        }

        System.out.println("JAVA_IPC_PHYSICAL_SESSION_HOST_PASS assertions=" + assertions
                + " races=1024 owner_bound=true");
    }
}
