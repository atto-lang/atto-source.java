package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Host-side supervisor PROCESS. This is not source-level Oreslang
 * `define supervisor` yet: the compiler must later lower callbacks into
 * this logically exclusive CONTROL admission boundary.
 *
 * A supervisor owns exactly one logical callback lease. If onFailure returns
 * an incomplete stage, the lease is retained while the physical CONTROL
 * carrier is released. Completion threads only enqueue the continuation on
 * the runtime-owned dispatcher. They never execute the decision sink.
 *
 * Decisions are REQUESTS to the trusted host, not automatic actor restarts,
 * escalation or cancellation. The host must implement and acknowledge those
 * lifecycle operations before claiming them as completed.
 */
public final class SupervisorProcess implements AutoCloseable {
    public enum Directive { RESTART, STOP, ESCALATE, ACKNOWLEDGE }

    /**
     * Trusted host lifecycle request. The monotonic turnGeneration is scoped
     * to supervisorId + groupId; the same immutable notice object can appear
     * in several distinct turns, so it cannot serve as an idempotency key.
     */
    public record Resolution(
            SupervisorFailureControl.Notice notice,
            Directive directive,
            long turnGeneration) {
        public Resolution {
            Objects.requireNonNull(notice);
            Objects.requireNonNull(directive);
            if (turnGeneration < 0) {
                throw new IllegalArgumentException("negative supervisor turn generation");
            }
        }
        /** Source compatibility for callers constructing host-only test values. */
        public Resolution(SupervisorFailureControl.Notice notice, Directive directive) {
            this(notice, directive, 0);
        }
    }

    private enum State { RUNNING, FAILED, CLOSED }

    private final ActorRuntime.SupervisorId supervisorId;
    private final ActorRuntime.ActorGroupId groupId;
    private final Executor controlDispatcher;
    /**
     * Decision-commit fence. A concurrent close must linearize before or
     * after an already admitted host lifecycle decision, never between
     * validation and the decision side effect.
     *
     * Do not use the mailbox's own monitor for host policy callbacks.
     */
    private final Object decisionFence = new Object();
    private final int capacity;
    private final Function<SupervisorFailureControl.Notice,
            CompletionStage<Directive>> onFailure;
    private final Consumer<Resolution> decisionSink;
    /** Optional async authoritative effect: acknowledgment is observed on CONTROL. */
    private final Function<Resolution, CompletionStage<Void>> asyncDecisionSink;
    private final Consumer<Resolution> onAcknowledged;
    /** Executes only on CONTROL for an observed, unsuccessful host effect. */
    private final Consumer<Resolution> onEffectFailure;
    private final ArrayDeque<SupervisorFailureControl.Notice> mailbox =
            new ArrayDeque<>();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong resolved = new AtomicLong();
    private final AtomicLong undelivered = new AtomicLong();
    private State state = State.RUNNING;
    /** Single-flight lease, including while an await is suspended. */
    private boolean callbackActive;
    /** Admission guard against a scheduler replaying the current turn. */
    private boolean callbackStarted;
    /** A generation may request one lifecycle effect, even if its runnable is replayed. */
    private boolean decisionIssued;
    /** Monotonic turn identity; notice object identity is NOT a continuation fence. */
    private long callbackGeneration;
    // close() invoked reentrantly from a lifecycle decision cannot erase that
    // already-admitted decision before its successful host commit is counted.
    private boolean committingDecision;
    private boolean closeAfterCommit;
    private Throwable fatal;

    SupervisorProcess(
            ActorRuntime.SupervisorId supervisorId,
            ActorRuntime.ActorGroupId groupId,
            Executor controlDispatcher,
            int capacity,
            Function<SupervisorFailureControl.Notice,
                    CompletionStage<Directive>> onFailure,
            Consumer<Resolution> decisionSink) {
        this.supervisorId = Objects.requireNonNull(supervisorId);
        this.groupId = Objects.requireNonNull(groupId);
        this.controlDispatcher = Objects.requireNonNull(controlDispatcher);
        this.onFailure = Objects.requireNonNull(onFailure);
        this.decisionSink = Objects.requireNonNull(decisionSink);
        this.asyncDecisionSink = null;
        this.onAcknowledged = null;
        this.onEffectFailure = null;
        if (capacity < 1) throw new IllegalArgumentException(
                "supervisor mailbox capacity must be positive");
        this.capacity = capacity;
    }

    /**
     * Async host effect mode. A successful CompletionStage is an authoritative
     * effect acknowledgment, not just successful enqueue. The finalizer runs
     * on a supervisor CONTROL carrier before a turn is counted as resolved.
     */
    SupervisorProcess(
            ActorRuntime.SupervisorId supervisorId,
            ActorRuntime.ActorGroupId groupId,
            Executor controlDispatcher,
            int capacity,
            Function<SupervisorFailureControl.Notice, CompletionStage<Directive>> onFailure,
            Function<Resolution, CompletionStage<Void>> asyncDecisionSink,
            Consumer<Resolution> onAcknowledged) {
        this(supervisorId, groupId, controlDispatcher, capacity, onFailure,
                asyncDecisionSink, onAcknowledged, ignored -> { });
    }

    SupervisorProcess(
            ActorRuntime.SupervisorId supervisorId,
            ActorRuntime.ActorGroupId groupId,
            Executor controlDispatcher,
            int capacity,
            Function<SupervisorFailureControl.Notice, CompletionStage<Directive>> onFailure,
            Function<Resolution, CompletionStage<Void>> asyncDecisionSink,
            Consumer<Resolution> onAcknowledged,
            Consumer<Resolution> onEffectFailure) {
        this.supervisorId = Objects.requireNonNull(supervisorId);
        this.groupId = Objects.requireNonNull(groupId);
        this.controlDispatcher = Objects.requireNonNull(controlDispatcher);
        this.onFailure = Objects.requireNonNull(onFailure);
        this.decisionSink = null;
        this.asyncDecisionSink = Objects.requireNonNull(asyncDecisionSink);
        this.onAcknowledged = Objects.requireNonNull(onAcknowledged);
        this.onEffectFailure = Objects.requireNonNull(onEffectFailure);
        if (capacity < 1) throw new IllegalArgumentException(
                "supervisor mailbox capacity must be positive");
        this.capacity = capacity;
    }

    public ActorRuntime.SupervisorId supervisorId() { return supervisorId; }
    public ActorRuntime.ActorGroupId groupId() { return groupId; }
    public long admitted() { return admitted.get(); }
    public long resolved() { return resolved.get(); }
    public long undelivered() { return undelivered.get(); }

    public synchronized int queued() { return mailbox.size(); }
    public synchronized boolean callbackActive() { return callbackActive; }
    public synchronized boolean healthy() {
        return state == State.RUNNING && !closeAfterCommit && undelivered.get() == 0;
    }
    public synchronized Optional<String> failure() {
        return Optional.ofNullable(fatal).map(Throwable::toString);
    }

    /** Called only from the trusted runtime CONTROL lane. */
    void accept(SupervisorFailureControl.Notice notice) {
        Objects.requireNonNull(notice);
        if (!supervisorId.equals(notice.supervisorId())
                || !groupId.equals(notice.groupId())) {
            throw new SecurityException("foreign supervisor process notice");
        }
        synchronized (this) {
            if (state != State.RUNNING || closeAfterCommit || mailbox.size() >= capacity) {
                undelivered.incrementAndGet();
                // Stop consuming CONTROL rather than misreporting loss as success.
                throw new IllegalStateException("supervisor process cannot admit notice");
            }
            try {
                mailbox.addLast(notice);
            } catch (Throwable enqueueFailure) {
                // Failed admission was never accepted. Account for it, then
                // fail the process and retire any previously accepted notices.
                undelivered.incrementAndGet();
                failLocked(enqueueFailure);
                throw enqueueFailure;
            }
            admitted.incrementAndGet();
            scheduleLocked();
            if (state != State.RUNNING) {
                // A rejected CONTROL dispatcher cannot masquerade as
                // successful notification admission.
                throw new IllegalStateException(
                        "supervisor CONTROL dispatch failed during admission", fatal);
            }
        }
    }

    private void scheduleLocked() {
        if (callbackActive || mailbox.isEmpty() || state != State.RUNNING) return;
        if (callbackGeneration == Long.MAX_VALUE) {
            failLocked(new IllegalStateException("supervisor CONTROL turn generation exhausted"));
            return;
        }
        final long generation = ++callbackGeneration;
        callbackActive = true;
        callbackStarted = false;
        decisionIssued = false;
        try {
            // Must enqueue; ActorRuntime supplies its shared CONTROL pool.
            SupervisorDispatchGuard.enqueue(controlDispatcher, () -> beginCallback(generation));
        } catch (Throwable schedulingFailure) {
            failLocked(schedulingFailure);
        }
    }

    private void beginCallback(long generation) {
        SupervisorFailureControl.Notice notice;
        synchronized (this) {
            if (state != State.RUNNING || !callbackActive || callbackStarted
                    || generation != callbackGeneration) return;
            callbackStarted = true;
            notice = mailbox.peekFirst();
            if (notice == null) {
                callbackActive = false;
                callbackStarted = false;
                return;
            }
        }
        final CompletionStage<Directive> pending;
        try {
            pending = Objects.requireNonNull(
                    onFailure.apply(notice),
                    "supervisor failure handler returned null completion");
        } catch (Throwable failure) {
            synchronized (this) { failLocked(failure); }
            return;
        }
        // No user callback, decision or mailbox state mutation happens here.
        // Completion may occur on an arbitrary foreign or child carrier.
        final AtomicBoolean completionAccepted = new AtomicBoolean();
        try {
            pending.whenComplete((decision, failure) -> {
                // CompletionStage implementations are pluggable. Even a broken
                // stage that calls whenComplete twice cannot replay a decision.
                if (!completionAccepted.compareAndSet(false, true)) return;
                try {
                    SupervisorDispatchGuard.enqueue(
                            controlDispatcher,
                            () -> finishCallback(notice, generation, decision, failure));
                } catch (Throwable schedulingFailure) {
                    synchronized (this) { failLocked(schedulingFailure); }
                }
            });
        } catch (Throwable subscriptionFailure) {
            synchronized (this) { failLocked(subscriptionFailure); }
        }
    }

    private void finishCallback(
            SupervisorFailureControl.Notice notice,
            long generation,
            Directive decision,
            Throwable error) {
        synchronized (decisionFence) {
        synchronized (this) {
            if (state != State.RUNNING || generation != callbackGeneration) return;
            // The lease is never released while a callback is unfinished.
            if (!callbackActive) {
                // A delayed duplicate completion after the last queued turn
                // was committed is harmless. There may be no next generation
                // yet, so generation equality alone is insufficient to fence
                // a replay. Never poison the supervisor for this stale task.
                return;
            }
            if (decisionIssued) return; // Replayed completion during async acknowledgment.
            if (!callbackStarted || mailbox.peekFirst() != notice) {
                failLocked(new IllegalStateException(
                        "supervisor continuation lost its exclusive lease"));
                return;
            }
            if (error != null || decision == null) {
                failLocked(error == null
                        ? new IllegalStateException("supervisor decision is null")
                        : error);
                return;
            }
        }
        final Resolution resolution = new Resolution(notice, decision, generation);
        synchronized (this) {
            decisionIssued = true;
            committingDecision = true;
        }
        if (asyncDecisionSink != null) {
            beginDecisionAcknowledgment(resolution, generation);
            return;
        }
        try {
            // Exactly one decision request, on an admitted runtime turn.
            decisionSink.accept(resolution);
        } catch (Throwable sinkFailure) {
            synchronized (this) {
                committingDecision = false;
                // A reentrant close() must complete even when the sink throws
                // after requesting shutdown. The host effect's commit status
                // is unknown: account for the notice as undelivered, preserve
                // the failure, and never retry the ambiguous side effect.
                failLocked(sinkFailure);
                if (closeAfterCommit) {
                    state = State.CLOSED;
                    mailbox.clear();
                    callbackActive = false;
                    callbackStarted = false;
                    decisionIssued = false;
                    closeAfterCommit = false;
                }
            }
            return;
        }
        synchronized (this) {
            committingDecision = false;
            if (state != State.RUNNING) return;
            mailbox.removeFirst();
            resolved.incrementAndGet();
            callbackActive = false;
            callbackStarted = false;
            decisionIssued = false;
            if (closeAfterCommit) {
                state = State.CLOSED;
                undelivered.addAndGet(mailbox.size());
                mailbox.clear();
            } else {
                scheduleLocked();
            }
        }
        }
    }

    /**
     * Initiate a host lifecycle effect once on CONTROL, then release the
     * carrier without releasing the supervisor's logical lease. The host's
     * completion may run on any thread; it can only enqueue an acknowledgment
     * continuation. Never run guest code or retire child identities there.
     */
    private void beginDecisionAcknowledgment(Resolution resolution, long generation) {
        final CompletionStage<Void> pending;
        try {
            pending = Objects.requireNonNull(
                    asyncDecisionSink.apply(resolution),
                    "async supervisor lifecycle sink returned null completion");
        } catch (Throwable failure) {
            noteEffectFailure(resolution, failure);
            synchronized (this) { finishSinkFailureLocked(failure); }
            return;
        } finally {
            synchronized (this) { committingDecision = false; }
        }
        final AtomicBoolean accepted = new AtomicBoolean();
        try {
            pending.whenComplete((ignored, failure) -> {
                if (!accepted.compareAndSet(false, true)) return;
                try {
                    SupervisorDispatchGuard.enqueue(controlDispatcher,
                            () -> finishDecisionAcknowledgment(resolution, generation, failure));
                } catch (Throwable enqueueFailure) {
                    synchronized (this) { finishSinkFailureLocked(enqueueFailure); }
                }
            });
        } catch (Throwable registrationFailure) {
            // Registration happens on CONTROL. A hostile CompletionStage can
            // throw after a host effect was requested, so retain uncertainty.
            noteEffectFailure(resolution, registrationFailure);
            synchronized (this) { finishSinkFailureLocked(registrationFailure); }
        }
    }

    private void finishDecisionAcknowledgment(
            Resolution resolution, long generation, Throwable error) {
        synchronized (decisionFence) {
            synchronized (this) {
                if (state != State.RUNNING || !callbackActive
                        || generation != callbackGeneration || !decisionIssued) return;
                if (mailbox.peekFirst() != resolution.notice()) {
                    failLocked(new IllegalStateException(
                            "async supervisor acknowledgment lost its CONTROL lease"));
                    return;
                }
                // A failed effect also needs a CONTROL-only receipt update,
                // outside this monitor, before fail-closed accounting.
                // The host-effect stage has succeeded only if error is null.
                // Preserve the decision
                // fence, but DO NOT call trusted host finalization under the
                // mailbox monitor. This avoids lock inversion with Group state.
                committingDecision = true;
            }
            if (error != null) {
                noteEffectFailure(resolution, error);
                synchronized (this) {
                    committingDecision = false;
                    finishSinkFailureLocked(error);
                }
                return;
            }
            try {
                onAcknowledged.accept(resolution);
            } catch (Throwable finalizationFailure) {
                synchronized (this) {
                    committingDecision = false;
                    finishSinkFailureLocked(finalizationFailure);
                }
                return;
            }
            synchronized (this) {
                committingDecision = false;
                if (state != State.RUNNING || !callbackActive
                        || generation != callbackGeneration) return;
                mailbox.removeFirst();
                resolved.incrementAndGet();
                callbackActive = false;
                callbackStarted = false;
                decisionIssued = false;
                if (closeAfterCommit) {
                    state = State.CLOSED;
                    undelivered.addAndGet(mailbox.size());
                    mailbox.clear();
                    closeAfterCommit = false;
                } else {
                    scheduleLocked();
                }
            }
        }
    }

    private void noteEffectFailure(Resolution resolution, Throwable failure) {
        try {
            // CONTROL-owned failure classification; no guest callback or
            // registry mutation on the foreign Future completion thread.
            onEffectFailure.accept(resolution);
        } catch (Throwable receiptFailure) {
            if (receiptFailure != failure) failure.addSuppressed(receiptFailure);
        }
    }

    private void finishSinkFailureLocked(Throwable failure) {
        failLocked(failure);
        if (closeAfterCommit) {
            state = State.CLOSED;
            closeAfterCommit = false;
        }
    }

    private void failLocked(Throwable error) {
        if (state != State.RUNNING) return;
        fatal = error;
        state = State.FAILED;
        undelivered.addAndGet(mailbox.size());
        mailbox.clear(); // Terminal errors must not retain failed guest metadata.
        callbackActive = false;
        callbackStarted = false;
        decisionIssued = false;
    }

    /**
     * The lifecycle owner closes this separately from closing ActorGroup.
     * An unresolved callback cannot be declared completed by cancellation.
     */
    @Override
    public void close() {
        synchronized (decisionFence) {
            synchronized (this) {
                if (state == State.CLOSED) return;
                if (state == State.RUNNING && committingDecision) {
                    closeAfterCommit = true;
                    return;
                }
                if (state == State.RUNNING) {
                    undelivered.addAndGet(mailbox.size());
                }
                state = State.CLOSED;
                mailbox.clear();
                callbackActive = false;
                callbackStarted = false;
                decisionIssued = false;
            }
        }
    }
}
