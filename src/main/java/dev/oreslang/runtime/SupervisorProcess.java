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

    public record Resolution(
            SupervisorFailureControl.Notice notice,
            Directive directive) {
        public Resolution {
            Objects.requireNonNull(notice);
            Objects.requireNonNull(directive);
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
            if (!callbackActive || mailbox.peekFirst() != notice) {
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
        synchronized (this) {
            committingDecision = true;
        }
        try {
            // Exactly one decision request, on an admitted runtime turn.
            decisionSink.accept(new Resolution(notice, decision));
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

    private void failLocked(Throwable error) {
        if (state != State.RUNNING) return;
        fatal = error;
        state = State.FAILED;
        undelivered.addAndGet(mailbox.size());
        mailbox.clear(); // Terminal errors must not retain failed guest metadata.
        callbackActive = false;
        callbackStarted = false;
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
            }
        }
    }
}
