package dev.oreslang.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
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
    private final LogicalSourceActorRouterV1 router;
    private final LogicalActorProxyMailboxV1 mailbox;
    private volatile boolean poisoned;
    private volatile boolean closed;

    private LogicalSourceActorProxyBridgeV1(
            LogicalSourceActorRouterV1 router,
            LogicalActorProxyMailboxV1 mailbox) {
        this.router = Objects.requireNonNull(router);
        this.mailbox = Objects.requireNonNull(mailbox);
        if (router.ref().routeEpoch() != mailbox.snapshot().routeEpoch()) {
            throw new IllegalStateException("proxy and physical route epochs differ");
        }
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

    public LogicalSourceActorRouterV1.LogicalActorRef ref() { return router.ref(); }

    public LogicalActorProxyMailboxV1.Snapshot snapshot() { return mailbox.snapshot(); }

    public boolean failSealed() { return poisoned; }

    /**
     * Admission is bounded and remains available while the upgrade holds
     * dispatchLock. It stores canonical, endian-stable immutable scalar bytes.
     * It does not claim physical delivery.
     */
    public LogicalActorProxyMailboxV1.Envelope admitLong(long value) {
        if (closed || poisoned)
            throw new IllegalStateException("logical proxy bridge is closed or fail-sealed");
        byte[] bytes = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; --i) {
            bytes[i] = (byte) value;
            value >>>= Byte.SIZE;
        }
        return mailbox.admit(bytes);
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
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("dispatch deadline must be positive");
        synchronized (dispatchLock) {
            requireUsable();
            var envelope = mailbox.pollDispatch();
            if (envelope == null) return false;
            try {
                // The sole encoder of this bridge is admitLong(). A corrupt
                // envelope must never be sent to the guest.
                byte[] wire = envelope.payload();
                if (wire.length != Long.BYTES)
                    throw new SecurityException("invalid canonical scalar proxy wire length");
                long value = 0L;
                for (byte octet : wire) value = (value << Byte.SIZE) | (octet & 255L);
                OresFuture<Void> receipt = router.sendAndDrainForProxy(value);
                receipt.get(toNanosSaturated(timeout), TimeUnit.NANOSECONDS);
                mailbox.complete(envelope.messageSequence());
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
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative())
            throw new IllegalArgumentException("upgrade deadline must be positive");
        synchronized (dispatchLock) {
            requireUsable();
            var ticket = mailbox.freezeDispatchForUpgrade();
            try {
                if (!ticket.drained()) {
                    ticket.rollback();
                    return false;
                }
                if (!router.upgradeTo(candidate, timeout)) {
                    ticket.rollback();
                    return false;
                }
                ticket.commit();
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

    private static long toNanosSaturated(Duration timeout) {
        try {
            return timeout.toNanos();
        } catch (ArithmeticException huge) {
            return Long.MAX_VALUE;
        }
    }

    private void requireUsable() {
        if (closed || poisoned)
            throw new IllegalStateException("logical proxy bridge is closed or fail-sealed");
    }

    @Override public void close() {
        synchronized (dispatchLock) {
            if (closed) return;
            poisoned = true; // no new admission while physical teardown runs
            try {
                router.close();
            } finally {
                // The proxy ledger intentionally remains if teardown throws:
                // an unretired code generation is not evidence of completion.
                closed = true;
            }
            mailbox.close();
        }
    }
}
