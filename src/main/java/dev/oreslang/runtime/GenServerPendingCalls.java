package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeoutException;

/**
 * Runtime-only pending GenServer call lifecycle.
 *
 * <p>Reply, timeout, cancellation, target death and registry close all arbitrate
 * through the same {@link GenServerReplyLedger} ticket. No terminal path may
 * settle the Future unless it first wins that one-shot ledger claim. This
 * prevents late replies from reviving timed-out/cancelled calls and prevents
 * consumer cancellation from defeating a reply that already owns correlation.</p>
 *
 * <p>Crash settlement is indexed by exact runtime/actor/incarnation target, so
 * one actor death is O(calls-to-that-incarnation), not O(all pending calls).
 * Deadline storage is likewise bounded by live timed calls and owns no timer
 * thread: the VM/CONTROL scheduler supplies monotonic ticks through
 * {@link #expireDue(long)}.</p>
 *
 * <p>This type and {@link ReplyCapability} are intentionally package-private.
 * A ReplyCapability is runtime authority sidecar state, never actor DATA, and
 * must not be serialized, frozen, copied, logged, or accepted as an ordinary
 * message field.</p>
 */
final class GenServerPendingCalls<R> implements AutoCloseable {
    /** Cause-free terminal projection for runtime/registry shutdown. */
    static final class GenServerCallClosedException extends RuntimeException {
        private final String diagnosticType;

        private GenServerCallClosedException(String diagnosticType) {
            super("GenServer call closed: " + diagnosticType, null, false, false);
            this.diagnosticType = diagnosticType;
        }

        String diagnosticType() {
            return diagnosticType;
        }
    }

    /** Closed, payload-free reason for one exact target generation ending. */
    enum TargetTermination {
        FAILURE,
        CANCELLED,
        QUOTA_EXCEEDED,
        SHUTDOWN,
        PROTOCOL
    }

    /** Cause-free projection of one exact target incarnation terminating. */
    static final class GenServerTargetTerminatedException extends RuntimeException {
        private final TargetTermination termination;

        private GenServerTargetTerminatedException(TargetTermination termination) {
            super("GenServer target terminated: " + termination, null, false, false);
            this.termination = Objects.requireNonNull(termination, "termination");
        }

        TargetTermination termination() {
            return termination;
        }
    }

    /** Typed fail-closed admission errors before a call owns correlation. */
    static final class CallAdmissionException extends IllegalStateException {
        enum Reason {
            SELF_CALL,
            DEADLINE_EXPIRED,
            DEADLINE_OVERFLOW,
            CLOCK_REGRESSION
        }

        private final Reason reason;

        private CallAdmissionException(Reason reason, String message) {
            super(message, null);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        Reason reason() {
            return reason;
        }
    }

    /**
     * Opaque one-shot reply authority. It exposes neither correlation nor the
     * pending-call registry and succeeds only from the exact target generation.
     */
    static final class ReplyCapability<R> {
        private final GenServerPendingCalls<R> owner;
        private final PendingCall<R> call;

        private ReplyCapability(GenServerPendingCalls<R> owner, PendingCall<R> call) {
            this.owner = owner;
            this.call = call;
        }

        boolean tryReply(GenServerReplyLedger.Target executingActor, R value) {
            return owner.tryReply(call, executingActor, value);
        }

        @Override
        public String toString() {
            return "GenServerReplyCapability[opaque]";
        }
    }

    static final class PendingCall<R> {
        private final GenServerPendingCalls<R> owner;
        private final GenServerReplyLedger.Ticket ticket;
        private final GenServerReplyLedger.Target target;
        private final OresFuture<R> future;
        private final ReplyCapability<R> replyCapability;
        private final boolean timed;
        private final long deadlineNanos;

        private PendingCall(
                GenServerPendingCalls<R> owner,
                GenServerReplyLedger.Ticket ticket,
                GenServerReplyLedger.Target target,
                OresFuture<R> future,
                boolean timed,
                long deadlineNanos) {
            this.owner = owner;
            this.ticket = ticket;
            this.target = target;
            this.future = future;
            this.replyCapability = new ReplyCapability<>(owner, this);
            this.timed = timed;
            this.deadlineNanos = deadlineNanos;
        }

        OresFuture<R> future() {
            return future;
        }

        ReplyCapability<R> replyCapability() {
            return replyCapability;
        }
    }

    /** Payload-free structural pressure diagnostics. */
    record Snapshot(
            int pending,
            int targetBuckets,
            int timedPending,
            int deadlineBuckets,
            long selfCallRejected,
            long deadlineExpirations,
            long targetFailureSettlements,
            long clockRegressions) {
        Snapshot {
            if (pending < 0 || targetBuckets < 0 || timedPending < 0
                    || deadlineBuckets < 0 || selfCallRejected < 0
                    || deadlineExpirations < 0 || targetFailureSettlements < 0
                    || clockRegressions < 0) {
                throw new IllegalArgumentException("negative GenServer pending-call statistics");
            }
        }
    }

    private final GenServerReplyLedger ledger;
    private final ActorReliabilityKernel reliability;

    /*
     * Ticket has identity equality. Linked collections preserve admission order
     * for deterministic close/target-failure settlement while remaining O(1)
     * for direct removals.
     */
    private final Map<GenServerReplyLedger.Ticket, PendingCall<R>> active =
            new LinkedHashMap<>();
    private final Map<GenServerReplyLedger.Target, Set<GenServerReplyLedger.Ticket>> byTarget =
            new LinkedHashMap<>();
    private final NavigableMap<Long, Set<GenServerReplyLedger.Ticket>> byDeadline =
            new TreeMap<>();
    private final Map<GenServerReplyLedger.Ticket, Long> deadlineByTicket =
            new LinkedHashMap<>();

    private boolean closed;
    private boolean hasClockSample;
    private long lastClockSample;
    private long selfCallRejected;
    private long deadlineExpirations;
    private long targetFailureSettlements;
    private long clockRegressions;

    GenServerPendingCalls(int capacity) {
        this(capacity, null);
    }

    GenServerPendingCalls(int capacity, ActorReliabilityKernel reliability) {
        this.ledger = new GenServerReplyLedger(capacity);
        this.reliability = reliability;
    }

    PendingCall<R> register(GenServerReplyLedger.Target target) {
        return registerInternal(target, false, 0L);
    }

    /**
     * Admit an untimed actor-originated call. Calling the same incarnation
     * synchronously through the GenServer protocol is rejected before a ticket
     * is minted, avoiding a deterministic single-flight deadlock.
     */
    PendingCall<R> registerFrom(
            GenServerReplyLedger.Target caller,
            GenServerReplyLedger.Target target) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(target, "target");
        rejectSelfCall(caller, target);
        return registerInternal(target, false, 0L);
    }

    /**
     * Admit a timed call using an explicit nanoTime-style monotonic sample.
     * No timer thread is created. The runtime later calls {@link #expireDue(long)}
     * from its existing bounded timing/CONTROL substrate.
     */
    PendingCall<R> registerTimedFrom(
            GenServerReplyLedger.Target caller,
            GenServerReplyLedger.Target target,
            long timeoutNanos,
            long nowNanos) {
        Objects.requireNonNull(caller, "caller");
        Objects.requireNonNull(target, "target");
        rejectSelfCall(caller, target);
        return registerTimed(target, timeoutNanos, nowNanos);
    }

    PendingCall<R> registerTimed(
            GenServerReplyLedger.Target target,
            long timeoutNanos,
            long nowNanos) {
        Objects.requireNonNull(target, "target");
        if (timeoutNanos < 0) {
            throw new IllegalArgumentException("timeoutNanos must be nonnegative");
        }

        synchronized (active) {
            requireClockForwardLocked(nowNanos);
            if (timeoutNanos == 0) {
                throw new CallAdmissionException(
                        CallAdmissionException.Reason.DEADLINE_EXPIRED,
                        "GenServer call deadline is already expired");
            }
            final long deadline;
            try {
                deadline = Math.addExact(nowNanos, timeoutNanos);
            } catch (ArithmeticException overflow) {
                throw new CallAdmissionException(
                        CallAdmissionException.Reason.DEADLINE_OVERFLOW,
                        "GenServer call deadline exceeds the supported monotonic interval");
            }

            // Keep the sampled clock and deadline indexing in one registry
            // critical section. Otherwise a concurrent expiry tick could move
            // past this deadline between sampling and call publication.
            return registerInternal(target, true, deadline);
        }
    }

    private PendingCall<R> registerInternal(
            GenServerReplyLedger.Target target,
            boolean timed,
            long deadlineNanos) {
        Objects.requireNonNull(target, "target");
        PendingCall<R> call;
        int depth;
        try {
            synchronized (active) {
                if (closed) {
                    throw new GenServerReplyLedger.AdmissionException(
                            GenServerReplyLedger.AdmissionException.Reason.CLOSED,
                            "pending-call registry is closed");
                }
                GenServerReplyLedger.Ticket ticket = ledger.register(target);
                try {
                    OresFuture<R> future = new OresFuture<>(
                            () -> retireForConsumerCancel(ticket, target),
                            () -> { });
                    call = new PendingCall<>(
                            this, ticket, target, future, timed, deadlineNanos);
                    indexActiveLocked(call);
                    depth = active.size();
                } catch (RuntimeException | Error constructionFailure) {
                    ledger.retire(ticket);
                    throw constructionFailure;
                }
            }
        } catch (GenServerReplyLedger.AdmissionException rejected) {
            ActorReliabilityKernel.FaultClass fault = switch (rejected.reason()) {
                case CAPACITY -> ActorReliabilityKernel.FaultClass.OVERLOAD;
                case CLOSED -> ActorReliabilityKernel.FaultClass.SHUTDOWN;
                case CORRELATION_EXHAUSTED -> ActorReliabilityKernel.FaultClass.PROTOCOL;
            };
            record(ActorReliabilityKernel.EventKind.OVERLOAD_SHED, target,
                    fault, pendingCount());
            throw rejected;
        }
        record(ActorReliabilityKernel.EventKind.CALL_REGISTERED, target,
                ActorReliabilityKernel.FaultClass.NONE, depth);
        return call;
    }

    private void rejectSelfCall(
            GenServerReplyLedger.Target caller,
            GenServerReplyLedger.Target target) {
        if (!caller.equals(target)) return;
        synchronized (active) {
            selfCallRejected = saturatingIncrement(selfCallRejected);
        }
        throw new CallAdmissionException(
                CallAdmissionException.Reason.SELF_CALL,
                "GenServer self-call to the same actor incarnation is not permitted");
    }

    boolean tryReply(
            PendingCall<R> call,
            GenServerReplyLedger.Target respondingActor,
            R value) {
        if (!owns(call)) return false;
        if (!ledger.tryReply(call.ticket, respondingActor)) {
            record(ActorReliabilityKernel.EventKind.CALL_REPLY_REJECTED, respondingActor,
                    ActorReliabilityKernel.FaultClass.STALE_OR_DUPLICATE, pendingCount());
            return false;
        }
        removeActive(call.ticket);
        if (!call.future.completeFromRuntime(value)) {
            throw new IllegalStateException(
                    "GenServer reply won correlation but Future was already terminal");
        }
        record(ActorReliabilityKernel.EventKind.CALL_REPLIED, call.target,
                ActorReliabilityKernel.FaultClass.NONE, pendingCount());
        return true;
    }

    boolean tryTimeout(PendingCall<R> call) {
        if (!owns(call)) return false;
        if (!ledger.retire(call.ticket)) return false;
        removeActive(call.ticket);
        settleTimeout(call);
        return true;
    }

    /**
     * Expire every deadline at or before {@code nowNanos} in deterministic
     * deadline/admission order. A regressing correctness clock fails closed.
     */
    int expireDue(long nowNanos) {
        List<PendingCall<R>> expired = new ArrayList<>();
        synchronized (active) {
            requireClockForwardLocked(nowNanos);
            while (!byDeadline.isEmpty()) {
                Map.Entry<Long, Set<GenServerReplyLedger.Ticket>> first =
                        byDeadline.firstEntry();
                if (nowNanos - first.getKey() < 0) break;

                byDeadline.pollFirstEntry();
                for (GenServerReplyLedger.Ticket ticket : List.copyOf(first.getValue())) {
                    deadlineByTicket.remove(ticket);
                    PendingCall<R> call = active.get(ticket);
                    if (call != null && ledger.retire(ticket)) {
                        removeActiveLocked(ticket, false);
                        expired.add(call);
                    }
                }
            }
            deadlineExpirations = saturatingAdd(deadlineExpirations, expired.size());
        }

        for (PendingCall<R> call : expired) {
            settleTimeout(call);
        }
        return expired.size();
    }

    /**
     * Fence pending calls for exactly one failed/restarted actor incarnation.
     * Work is proportional to calls addressed to that target, not registry size.
     */
    int failTarget(
            GenServerReplyLedger.Target target,
            TargetTermination termination) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(termination, "termination");
        List<PendingCall<R>> failed = new ArrayList<>();

        synchronized (active) {
            Set<GenServerReplyLedger.Ticket> targetTickets = byTarget.get(target);
            if (targetTickets != null) {
                for (GenServerReplyLedger.Ticket ticket : List.copyOf(targetTickets)) {
                    PendingCall<R> call = active.get(ticket);
                    if (call != null && ledger.retire(ticket)) {
                        removeActiveLocked(ticket, true);
                        failed.add(call);
                    }
                }
            }
            targetFailureSettlements =
                    saturatingAdd(targetFailureSettlements, failed.size());
        }

        ActorReliabilityKernel.FaultClass fault = switch (termination) {
            case FAILURE -> ActorReliabilityKernel.FaultClass.HANDLER_FAILURE;
            case CANCELLED -> ActorReliabilityKernel.FaultClass.CANCELLED;
            case QUOTA_EXCEEDED -> ActorReliabilityKernel.FaultClass.WATCHDOG;
            case SHUTDOWN -> ActorReliabilityKernel.FaultClass.SHUTDOWN;
            case PROTOCOL -> ActorReliabilityKernel.FaultClass.PROTOCOL;
        };
        for (PendingCall<R> call : failed) {
            if (!call.future.failFromRuntime(
                    new GenServerTargetTerminatedException(termination))) {
                throw new IllegalStateException(
                        "GenServer target failure won correlation but Future was already terminal");
            }
            record(ActorReliabilityKernel.EventKind.CALL_CLOSED, call.target,
                    fault, pendingCount());
        }
        return failed.size();
    }

    boolean cancel(PendingCall<R> call) {
        return owns(call) && call.future.cancel(false);
    }

    int pendingCount() {
        synchronized (active) {
            return active.size();
        }
    }

    Snapshot snapshot() {
        synchronized (active) {
            return new Snapshot(
                    active.size(),
                    byTarget.size(),
                    deadlineByTicket.size(),
                    byDeadline.size(),
                    selfCallRejected,
                    deadlineExpirations,
                    targetFailureSettlements,
                    clockRegressions);
        }
    }

    GenServerReplyLedger.Snapshot ledgerSnapshot() {
        return ledger.snapshot();
    }

    int closePending(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        List<PendingCall<R>> toFail = new ArrayList<>();
        synchronized (active) {
            if (closed) return 0;
            closed = true;
            for (GenServerReplyLedger.Ticket ticket : ledger.closeTickets()) {
                PendingCall<R> call = removeActiveLocked(ticket, true);
                if (call != null) toFail.add(call);
            }
        }

        String diagnosticType = boundedDiagnosticType(failure);
        for (PendingCall<R> call : toFail) {
            if (!call.future.failFromRuntime(
                    new GenServerCallClosedException(diagnosticType))) {
                throw new IllegalStateException(
                        "GenServer close won correlation but Future was already terminal");
            }
            record(ActorReliabilityKernel.EventKind.CALL_CLOSED, call.target,
                    ActorReliabilityKernel.FaultClass.SHUTDOWN, pendingCount());
        }
        return toFail.size();
    }

    @Override
    public void close() {
        closePending(new IllegalStateException("GenServer pending calls closed"));
    }

    private void settleTimeout(PendingCall<R> call) {
        if (!call.future.failFromRuntime(
                new TimeoutException("GenServer call deadline expired"))) {
            throw new IllegalStateException(
                    "GenServer timeout won correlation but Future was already terminal");
        }
        record(ActorReliabilityKernel.EventKind.CALL_TIMEOUT, call.target,
                ActorReliabilityKernel.FaultClass.TIMEOUT, pendingCount());
    }

    private boolean retireForConsumerCancel(
            GenServerReplyLedger.Ticket ticket,
            GenServerReplyLedger.Target target) {
        boolean won = ledger.retire(ticket);
        if (won) {
            removeActive(ticket);
            record(ActorReliabilityKernel.EventKind.CALL_CANCELLED, target,
                    ActorReliabilityKernel.FaultClass.CANCELLED, pendingCount());
        }
        return won;
    }

    private void indexActiveLocked(PendingCall<R> call) {
        if (active.put(call.ticket, call) != null) {
            throw new IllegalStateException("duplicate GenServer pending ticket");
        }
        byTarget.computeIfAbsent(
                call.target, ignored -> new LinkedHashSet<>()).add(call.ticket);
        if (call.timed) {
            deadlineByTicket.put(call.ticket, call.deadlineNanos);
            byDeadline.computeIfAbsent(
                    call.deadlineNanos, ignored -> new LinkedHashSet<>()).add(call.ticket);
        }
    }

    private PendingCall<R> removeActive(GenServerReplyLedger.Ticket ticket) {
        synchronized (active) {
            return removeActiveLocked(ticket, true);
        }
    }

    private PendingCall<R> removeActiveLocked(
            GenServerReplyLedger.Ticket ticket,
            boolean removeDeadlineBucket) {
        PendingCall<R> call = active.remove(ticket);
        if (call == null) return null;

        Set<GenServerReplyLedger.Ticket> targetTickets = byTarget.get(call.target);
        if (targetTickets == null || !targetTickets.remove(ticket)) {
            throw new IllegalStateException("GenServer target index lost a live ticket");
        }
        if (targetTickets.isEmpty()) byTarget.remove(call.target);

        Long deadline = deadlineByTicket.remove(ticket);
        if (deadline != null && removeDeadlineBucket) {
            Set<GenServerReplyLedger.Ticket> deadlineTickets = byDeadline.get(deadline);
            if (deadlineTickets == null || !deadlineTickets.remove(ticket)) {
                throw new IllegalStateException("GenServer deadline index lost a live ticket");
            }
            if (deadlineTickets.isEmpty()) byDeadline.remove(deadline);
        }
        return call;
    }

    private void requireClockForwardLocked(long nowNanos) {
        if (hasClockSample && nowNanos - lastClockSample < 0) {
            clockRegressions = saturatingIncrement(clockRegressions);
            throw new CallAdmissionException(
                    CallAdmissionException.Reason.CLOCK_REGRESSION,
                    "GenServer monotonic deadline clock regressed");
        }
        hasClockSample = true;
        lastClockSample = nowNanos;
    }

    private boolean owns(PendingCall<R> call) {
        return call != null && call.owner == this;
    }

    private void record(
            ActorReliabilityKernel.EventKind kind,
            GenServerReplyLedger.Target target,
            ActorReliabilityKernel.FaultClass fault,
            long value) {
        if (reliability == null) return;
        reliability.record(kind,
                new ActorReliabilityKernel.ActorKey(
                        target.runtimeId(), target.actorId(), target.incarnation()),
                fault, value);
    }

    private static long saturatingIncrement(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static long saturatingAdd(long value, long delta) {
        if (delta <= 0) return value;
        return value > Long.MAX_VALUE - delta ? Long.MAX_VALUE : value + delta;
    }
}
