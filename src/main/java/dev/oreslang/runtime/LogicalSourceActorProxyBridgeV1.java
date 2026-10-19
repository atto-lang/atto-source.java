package dev.oreslang.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Deliberately serialized, trusted-HOST integration proof joining immutable
 * bounded proxy mail to real source-actor physical send and drain receipts.
 *
 * <p>Only signed 64-bit scalar mail is encoded here; arbitrary guest objects
 * and schemas require a compiler-owned typed wire codec. Successful physical
 * enqueue is not logical completion: the proxy refunds credits only after the
 * exact physical actor drain observer succeeds. No parallel guest-visible
 * dispatch, durable exactly-once effects, or isolated/untrusted placement is
 * claimed. A failed/ambiguous send, drain or deadline seals dispatch without
 * retrying possibly accepted work.</p>
 */
public final class LogicalSourceActorProxyBridgeV1 implements AutoCloseable {
    private final Object dispatchLock = new Object();
    // Separate from dispatchLock: admission remains open while upgrading,
    // but close must atomically seal admission before physical teardown.
    private final Object admissionLock = new Object();
    private final LogicalSourceActorRouterV1 router;
    private final LogicalActorProxyMailboxV1 mailbox;
    private final ActorHotUpgradeTaskOwnerV1 taskOwner;
    private volatile boolean poisoned;
    private volatile boolean closed;
    // Under dispatchLock. Sealing admission does not prove that physical
    // ActorRuntime.stop and generation retirement have completed.
    private boolean physicallyRetired;

    private LogicalSourceActorProxyBridgeV1(
            LogicalSourceActorRouterV1 router,
            LogicalActorProxyMailboxV1 mailbox) {
        this.router = Objects.requireNonNull(router);
        this.mailbox = Objects.requireNonNull(mailbox);
        this.taskOwner = new ActorHotUpgradeTaskOwnerV1(router);
        router.bindProxyMailboxForSupervisor(mailbox);
    }

    public static LogicalSourceActorProxyBridgeV1 spawnShared(
            HotReloadManager.Generation first,
            String actorTypeName,
            Duration readyTimeout,
            int maxMessages,
            int maxMessageBytes,
            long maxRetainedBytes)
            throws InterruptedException, ExecutionException, TimeoutException {
        // Capacity validation must precede physical actor spawn.
        var mailbox = new LogicalActorProxyMailboxV1(
                maxMessages, maxMessageBytes, maxRetainedBytes);
        var router = LogicalSourceActorRouterV1.spawn(
                first, ActorRuntime.ActorKind.SHARED, actorTypeName, readyTimeout);
        try {
            return new LogicalSourceActorProxyBridgeV1(router, mailbox);
        } catch (RuntimeException | Error failure) {
            try {
                router.close();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    /**
     * Read-only logical address, never an ActorRef carrying a raw send().
     * The bridge must not leak a bypass around its bounded proxy credits.
     */
    public LogicalSourceActorRouterV1.LogicalAddress address() {
        // Do not expose the router's new epoch while upgradeTo() still holds
        // an uncommitted proxy mailbox freeze ticket.
        synchronized (dispatchLock) {
            return router.ref().address();
        }
    }

    /**
     * The only coherent bridge-level publication observation: physical
     * route and proxy mailbox epoch are captured under the same monitor
     * as the upgrade transaction. Separate address()/snapshot() calls
     * cannot promise one cross-call point-in-time observation.
     */
    public record Publication(
            LogicalSourceActorRouterV1.LogicalAddress address,
            LogicalActorProxyMailboxV1.Snapshot mailbox) { }

    public Publication publication() {
        synchronized (dispatchLock) {
            var address = router.ref().address();
            var snapshot = mailbox.snapshot();
            if (!poisoned && address.routeEpoch() != snapshot.routeEpoch()) {
                poisoned = true;
                throw new IllegalStateException(
                        "inconsistent code-image and proxy route publication");
            }
            return new Publication(address, snapshot);
        }
    }

    public GeneratorRuntime.Step<ActorRuntime.ActorOutput<Object>> nextOutput(
            Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        requireHost();
        return router.ref().nextOutput(timeout);
    }

    /**
     * Host-only verification hook: bind one resumable scheduler frame to the
     * current physical actor before its source generation can be retired.
     * Unlike public admission, this is deliberately NOT an ActorRef method.
     * The final compiler-owned TaskScope must replace this test seam.
     */
    <T> OresFuture<T> startHostPinnedTaskForUpgrade(
            OresScheduler scheduler, OresScheduler.Task<T> frame) {
        requireHost();
        synchronized (dispatchLock) {
            requireUsable();
            return taskOwner.start(scheduler, frame);
        }
    }

    ActorHotUpgradeTaskOwnerV1 childHostTaskScopeForUpgrade() {
        requireHost();
        synchronized (dispatchLock) {
            requireUsable();
            return taskOwner.childScope();
        }
    }

    public LogicalActorProxyMailboxV1.Snapshot snapshot() {
        synchronized (dispatchLock) {
            return mailbox.snapshot();
        }
    }

    public boolean failSealed() { return poisoned; }

    // Test-only coherent observation under the physical route monitor.
    boolean routeEpochsAlignedForTesting() {
        return router.proxyRouteEpochAlignedForTesting(mailbox);
    }

    /**
     * Admission is bounded and remains available while the upgrade holds
     * dispatchLock. It stores canonical, endian-stable immutable scalar bytes.
     * It does not claim physical delivery.
     */
    public LogicalActorProxyMailboxV1.Envelope admitLong(long value) {
        requireHost();
        synchronized (admissionLock) {
            if (closed || poisoned)
                throw new IllegalStateException(
                        "logical proxy bridge is closed or fail-sealed");
            byte[] bytes = new byte[Long.BYTES];
            for (int i = Long.BYTES - 1; i >= 0; --i) {
                bytes[i] = (byte) value;
                value >>>= Byte.SIZE;
            }
            return mailbox.admit(bytes);
        }
    }

    /**
     * Dispatch at most one message; only a successful *physical drain* removes
     * its retention credit. All dispatches are serialized for this v1 bridge,
     * making the actor's drain prefix an unambiguous per-dispatch barrier.
     * Physical acceptance/drain failure is ambiguous and leaves the envelope
     * outstanding until explicit supervisor reconciliation; NEVER auto-retry.
     */
    public boolean dispatchOne(Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        requireHost();
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("dispatch deadline must be positive");
        synchronized (dispatchLock) {
            requireUsable();
            try {
                // A failed or corrupted dispatch reservation may have changed
                // mailbox accounting; it must not escape the fail-seal handler.
                var envelope = mailbox.pollDispatch();
                if (envelope == null) return false;
                // Only the authoritative physical router may decode and
                // send these immutable bytes. Its opaque receipt binds the
                // exact envelope, route epoch and physical actor instance;
                // only a successful physical drain may refund proxy credits.
                long routeEpoch = mailbox.snapshot().routeEpoch();
                var receipt = router.sendAndDrainForProxy(
                        mailbox, envelope, routeEpoch);
                receipt.awaitAndComplete(envelope, timeout);
                return true;
            } catch (InterruptedException | ExecutionException | TimeoutException
                    | RuntimeException | Error uncertain) {
                poisoned = true;
                throw uncertain;
            }
        }
    }

    /**
     * Freeze proxy dispatch, then migrate the physical route at a drained
     * watermark. Bounded admission can continue concurrently. If upgrade
     * raises an exception, retain the freeze and fail sealed, because the
     * physical router may already have published a candidate.
     */
    public boolean upgradeTo(
            HotReloadManager.Generation candidate, Duration timeout)
            throws InterruptedException, ExecutionException, TimeoutException {
        requireHost();
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("upgrade deadline must be positive");
        synchronized (dispatchLock) {
            requireUsable();
            try {
                // Freeze acquisition is part of the supervisor transaction,
                // not a preflight outside its quarantine boundary.
                var ticket = mailbox.freezeDispatchForUpgrade();
                if (!ticket.drained()) {
                    ticket.rollback();
                    return false;
                }
                // Physical Route and mailbox epoch publish under one router
                // monitor, after construction and validation of the new route.
                if (!router.upgradeToForProxy(
                        candidate, timeout, mailbox, ticket)) {
                    ticket.rollback();
                    return false;
                }
                if (mailbox.snapshot().routeEpoch() != router.ref().routeEpoch()) {
                    poisoned = true;
                    throw new IllegalStateException(
                            "logical proxy and physical route publication epochs disagree");
                }
                return true;
            } catch (InterruptedException | ExecutionException | TimeoutException
                    | RuntimeException | Error uncertain) {
                // An exception after physical publication is not a rollback
                // permit. Leave the active ticket frozen and require supervisor
                // retirement/reconciliation.
                poisoned = true;
                throw uncertain;
            }
        }
    }

    private static void requireHost() {
        if (ActorRuntime.inActorExecution()
                || OresScheduler.current() != null
                || OresScheduler.isSchedulerCarrierThread()
                || AsyncRuntime.isAsyncCarrierThread()) {
            // Carrier/terminal callbacks can run after CURRENT turn was
            // cleared. They are still not a trusted blocking supervisor.
            throw new SecurityException(
                    "bounded logical source proxy bridge is a HOST-only API");
        }
    }

    private void requireUsable() {
        if (closed || poisoned)
            throw new IllegalStateException("logical proxy bridge is closed or fail-sealed");
    }

    @Override public void close() {
        requireHost();
        synchronized (dispatchLock) {
            synchronized (admissionLock) {
                if (physicallyRetired) return;
                if (!closed) {
                    // Sealing is permanent once admission is quiescent. A
                    // previously failed physical retirement must not reopen
                    // admission during a later cleanup attempt.
                    if (mailbox.snapshot().retainedMessages() != 0) {
                        throw new IllegalStateException(
                                "cannot close bounded proxy with outstanding or queued mail");
                    }
                    closed = true;
                    poisoned = true;
                }
            }
            // ActorRuntime.stop() has a bounded wait. If it throws, leave
            // physicallyRetired=false so a later close retries the router's
            // pinned-generation cleanup without admitting new messages.
            // A cancelled result is not a physical Task exit. The owning
            // scope cancels all descendants and joins every generation pin
            // before the router is allowed to retire actor code/context.
            taskOwner.close();
            router.close();
            mailbox.close();
            physicallyRetired = true;
        }
    }
}
