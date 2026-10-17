package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
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

    private static final class Pending {
        private final SupervisorFailureControl.Notice notice;
        private final CompletableFuture<Void> acknowledgement = new CompletableFuture<>();

        private Pending(SupervisorFailureControl.Notice notice) {
            this.notice = Objects.requireNonNull(notice);
        }
    }

    private final ActorRuntime.SupervisorId supervisorId;
    private final ActorRuntime.ActorGroupId groupId;
    private final Executor controlDispatcher;
    private final int capacity;
    private final Function<SupervisorFailureControl.Notice,
            CompletionStage<Directive>> onFailure;
    private final Consumer<Resolution> decisionSink;
    private final ArrayDeque<Pending> mailbox = new ArrayDeque<>();
    private final AtomicLong admitted = new AtomicLong();
    private final AtomicLong resolved = new AtomicLong();
    private final AtomicLong undelivered = new AtomicLong();
    private State state = State.RUNNING;
    /** Single-flight lease, including while an await is suspended. */
    private boolean callbackActive;
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
        return state == State.RUNNING && undelivered.get() == 0;
    }
    public synchronized Optional<String> failure() {
        return Optional.ofNullable(fatal).map(Throwable::toString);
    }

    public synchronized List<SupervisorFailureControl.Notice> pendingSnapshot() {
        return mailbox.stream().map(pending -> pending.notice).toList();
    }

    /** Completion is the durable acknowledgement boundary back to CONTROL. */
    CompletionStage<Void> accept(SupervisorFailureControl.Notice notice) {
        Objects.requireNonNull(notice);
        if (!supervisorId.equals(notice.supervisorId())
                || !groupId.equals(notice.groupId())) {
            throw new SecurityException("foreign supervisor process notice");
        }
        synchronized (this) {
            if (state != State.RUNNING || mailbox.size() >= capacity) {
                undelivered.incrementAndGet();
                CompletableFuture<Void> rejected = new CompletableFuture<>();
                rejected.completeExceptionally(
                        new IllegalStateException("supervisor process cannot admit notice"));
                return rejected;
            }
            Pending pending = new Pending(notice);
            mailbox.addLast(pending);
            admitted.incrementAndGet();
            scheduleLocked();
            return pending.acknowledgement;
        }
    }

    private void scheduleLocked() {
        if (callbackActive || mailbox.isEmpty() || state != State.RUNNING) return;
        callbackActive = true;
        try {
            // Must enqueue; ActorRuntime supplies its shared CONTROL pool.
            SupervisorDispatchGuard.enqueue(controlDispatcher, this::beginCallback);
        } catch (Throwable schedulingFailure) {
            failLocked(schedulingFailure);
        }
    }

    private void beginCallback() {
        Pending current;
        synchronized (this) {
            if (state != State.RUNNING) return;
            current = mailbox.peekFirst();
            if (current == null) {
                callbackActive = false;
                return;
            }
        }
        final CompletionStage<Directive> pending;
        try {
            pending = Objects.requireNonNull(
                    onFailure.apply(current.notice),
                    "supervisor failure handler returned null completion");
        } catch (Throwable failure) {
            synchronized (this) { failLocked(failure); }
            return;
        }
        // No user callback, decision or mailbox state mutation happens here.
        // Completion may occur on an arbitrary foreign or child carrier.
        try {
            pending.whenComplete((decision, failure) -> {
                try {
                    SupervisorDispatchGuard.enqueue(
                            controlDispatcher,
                            () -> finishCallback(current, decision, failure));
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
            Directive decision,
            Throwable error) {
        synchronized (this) {
            if (state != State.RUNNING) return;
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
        try {
            // Exactly one decision request, on an admitted runtime turn.
            decisionSink.accept(new Resolution(notice, decision));
        } catch (Throwable sinkFailure) {
            synchronized (this) { failLocked(sinkFailure); }
            return;
        }
        synchronized (this) {
            if (state != State.RUNNING) return;
            mailbox.removeFirst();
            resolved.incrementAndGet();
            callbackActive = false;
            scheduleLocked();
        }
    }

    private void failLocked(Throwable error) {
        if (state != State.RUNNING) return;
        fatal = error;
        state = State.FAILED;
        undelivered.addAndGet(mailbox.size());
        // The failed process cannot resume or dispatch these notices.
        // Keep observable failure counters but release retained payloads.
        mailbox.clear();
        callbackActive = false;
    }

    /**
     * The lifecycle owner closes this separately from closing ActorGroup.
     * An unresolved callback cannot be declared completed by cancellation.
     */
    @Override
    public synchronized void close() {
        if (state == State.CLOSED) return;
        if (state == State.RUNNING) {
            undelivered.addAndGet(mailbox.size());
        }
        state = State.CLOSED;
        mailbox.clear();
        callbackActive = false;
    }
}
