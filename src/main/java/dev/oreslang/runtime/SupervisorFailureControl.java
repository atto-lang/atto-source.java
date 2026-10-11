package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * A bounded, supervisor-owned HOST CONTROL notification lane.
 *
 * The owning runtime supplies its existing shared dispatcher. Child-carrier
 * code only enqueues immutable value notices; a failure handler never runs
 * inline on that carrier, and handlers are strictly single-flight.
 *
 * This is a host substrate, not parsed Oreslang supervisor/on_panic syntax.
 * Terminal actor failures are not guest recoverable; policy and restart
 * decisions are left to the trusted supervisor.
 */
public final class SupervisorFailureControl {
    public enum Kind { TERMINAL_FAILURE, CANCELLED }

    public record Notice(
            ActorRuntime.SupervisorId supervisorId,
            ActorRuntime.ActorGroupId groupId,
            ActorRuntime.ActorId actorId,
            Optional<ActorRuntime.ActorId> spawnedBy,
            Kind kind,
            String failureType,
            String diagnostic,
            Optional<SupervisorChildRegistry.ChildId> logicalChild,
            long incarnation) {
        public Notice {
            Objects.requireNonNull(supervisorId);
            Objects.requireNonNull(groupId);
            Objects.requireNonNull(actorId);
            spawnedBy = Objects.requireNonNull(spawnedBy);
            Objects.requireNonNull(kind);
            logicalChild = Objects.requireNonNull(logicalChild);
            if (logicalChild.isPresent()) {
                var child = logicalChild.get();
                if (!child.supervisorId().equals(supervisorId)
                        || !child.groupId().equals(groupId)
                        || incarnation < 1) {
                    throw new IllegalArgumentException("foreign or invalid logical child incarnation");
                }
            } else if (incarnation != 0) {
                throw new IllegalArgumentException("unbound logical child incarnation");
            }
            failureType = bounded(Objects.requireNonNull(failureType));
            diagnostic = bounded(Objects.requireNonNull(diagnostic));
        }

        /** Compatibility for host notices without a trusted registry binding. */
        public Notice(ActorRuntime.SupervisorId supervisorId,
                      ActorRuntime.ActorGroupId groupId,
                      ActorRuntime.ActorId actorId,
                      Optional<ActorRuntime.ActorId> spawnedBy,
                      Kind kind, String failureType, String diagnostic) {
            this(supervisorId, groupId, actorId, spawnedBy, kind, failureType,
                    diagnostic, Optional.empty(), 0);
        }
    }

    /**
     * Match native bounded_control_diagnostic: 1,024 UTF-8 bytes, not UTF-16
     * code units. Never split a Unicode scalar at the boundary. Java strings
     * may contain lone surrogate code units, so replace those with '?' rather
     * than leaking malformed UTF-8 into a cross-runtime Failure envelope.
     */
    private static String bounded(String text) {
        final int maxBytes = 1024;
        int used = 0;
        StringBuilder result = new StringBuilder(Math.min(text.length(), maxBytes));
        for (int i = 0; i < text.length();) {
            char first = text.charAt(i);
            int codePoint;
            int consumed = 1;
            if (Character.isHighSurrogate(first)) {
                if (i + 1 < text.length()
                        && Character.isLowSurrogate(text.charAt(i + 1))) {
                    codePoint = Character.toCodePoint(first, text.charAt(i + 1));
                    consumed = 2;
                } else {
                    codePoint = '?';
                }
            } else if (Character.isLowSurrogate(first)) {
                codePoint = '?';
            } else {
                codePoint = first;
            }
            final int width = codePoint <= 0x7F ? 1
                    : codePoint <= 0x7FF ? 2
                    : codePoint <= 0xFFFF ? 3 : 4;
            if (width > maxBytes - used) break;
            result.appendCodePoint(codePoint);
            used += width;
            i += consumed;
        }
        return result.toString();
    }

    private final ActorRuntime.SupervisorId supervisorId;
    private final ActorRuntime.ActorGroupId groupId;
    private final Executor dispatcher;
    private final int capacity;
    private final ArrayDeque<Notice> pending = new ArrayDeque<>();
    private final AtomicLong undelivered = new AtomicLong();
    private Consumer<Notice> handler;
    private boolean draining;
    private boolean drainActive;
    /** Fences duplicate and late scheduler callbacks across CONTROL batches. */
    private long dispatchGeneration;
    private Throwable handlerFailure;

    public SupervisorFailureControl(
            ActorRuntime.SupervisorId supervisorId,
            ActorRuntime.ActorGroupId groupId,
            Executor dispatcher,
            int capacity) {
        this.supervisorId = Objects.requireNonNull(supervisorId);
        this.groupId = Objects.requireNonNull(groupId);
        this.dispatcher = Objects.requireNonNull(dispatcher);
        if (capacity < 1) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public ActorRuntime.SupervisorId supervisorId() { return supervisorId; }

    /** Install exactly once; a pre-handler failure stays queued until installed. */
    public synchronized void install(Consumer<Notice> handler) {
        Objects.requireNonNull(handler);
        if (this.handler != null) {
            throw new IllegalStateException("supervisor failure handler already installed");
        }
        if (handlerFailure != null) {
            throw new IllegalStateException("supervisor control lane failed", handlerFailure);
        }
        this.handler = handler;
        scheduleLocked();
    }

    /** Child termination never invokes guest/supervisor code on this thread. */
    public synchronized void publish(Notice notice) {
        Objects.requireNonNull(notice);
        if (!supervisorId.equals(notice.supervisorId())
                || !groupId.equals(notice.groupId())) {
            throw new SecurityException("foreign supervisor CONTROL notice");
        }
        if (handlerFailure != null || pending.size() >= capacity) {
            undelivered.incrementAndGet(); // fail closed, never pretend delivered
            return;
        }
        pending.addLast(notice);
        scheduleLocked();
    }

    /**
     * A child failed but its physical retirement barrier did not complete.
     * Do not forge a finalized Failure; account for the loss and stop
     * presenting this supervisor CONTROL lane as healthy.
     */
    synchronized void rejectUnfinalizedFailure() {
        undelivered.incrementAndGet();
        if (handlerFailure == null) {
            handlerFailure = new IllegalStateException(
                    "actor failure could not cross physical finalization barrier");
        }
        undelivered.addAndGet(pending.size());
        pending.clear();
    }

    public synchronized int queued() { return pending.size(); }
    public long undelivered() { return undelivered.get(); }
    public synchronized Optional<String> failure() {
        return Optional.ofNullable(handlerFailure).map(Throwable::toString);
    }
    public synchronized boolean healthy() {
        return handlerFailure == null && undelivered.get() == 0;
    }

    private void scheduleLocked() {
        if (draining || handler == null || pending.isEmpty() || handlerFailure != null) return;
        if (dispatchGeneration == Long.MAX_VALUE) {
            handlerFailure = new IllegalStateException("supervisor CONTROL dispatch generation exhausted");
            undelivered.addAndGet(pending.size());
            pending.clear();
            return;
        }
        final long generation = ++dispatchGeneration;
        draining = true;
        try {
            SupervisorDispatchGuard.enqueue(dispatcher, () -> drain(generation));
        } catch (Throwable dispatchFailure) {
            handlerFailure = dispatchFailure;
            draining = false;
            undelivered.addAndGet(pending.size());
            pending.clear(); // Failed CONTROL lane must release owned notices.
        }
    }

    private void drain(long generation) {
        // Even an executor that invokes the same submitted Runnable twice
        // cannot overlap handlers or use an old batch callback on a newer one.
        synchronized (this) {
            if (!draining || drainActive || generation != dispatchGeneration
                    || handlerFailure != null) return;
            drainActive = true;
        }
        // Bounded bursts: release the shared dispatcher between batches.
        int processed = 0;
        while (processed < 32) {
            Notice next;
            Consumer<Notice> callback;
            synchronized (this) {
                if (handlerFailure != null || pending.isEmpty()) {
                    draining = false;
                    drainActive = false;
                    return;
                }
                next = pending.peekFirst();
                callback = handler;
            }
            try {
                callback.accept(next);
            } catch (Throwable failure) {
                synchronized (this) {
                    handlerFailure = failure;
                    undelivered.addAndGet(pending.size());
                    pending.clear(); // No further delivery is possible.
                    draining = false;
                    drainActive = false;
                }
                return;
            }
            synchronized (this) {
                // A concurrently failed child-finalization barrier may have
                // retired the entire queue while this host callback ran.
                // The active notice was already counted as undelivered;
                // never remove from an empty retired queue or count success.
                if (handlerFailure != null) {
                    draining = false;
                    drainActive = false;
                    return;
                }
                if (pending.peekFirst() != next) {
                    handlerFailure = new IllegalStateException(
                            "supervisor CONTROL queue changed during delivery");
                    undelivered.addAndGet(pending.size());
                    pending.clear();
                    draining = false;
                    drainActive = false;
                    return;
                }
                pending.removeFirst();
            }
            processed++;
        }
        synchronized (this) {
            draining = false;
            drainActive = false;
            scheduleLocked();
        }
    }
}
