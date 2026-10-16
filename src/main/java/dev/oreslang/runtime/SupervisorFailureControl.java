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
            String diagnostic) {
        public Notice {
            Objects.requireNonNull(supervisorId);
            Objects.requireNonNull(groupId);
            Objects.requireNonNull(actorId);
            spawnedBy = Objects.requireNonNull(spawnedBy);
            Objects.requireNonNull(kind);
            failureType = bounded(Objects.requireNonNull(failureType));
            diagnostic = bounded(Objects.requireNonNull(diagnostic));
        }
    }

    private static String bounded(String text) {
        // A Java-char limit is not a UTF-8 wire budget: 1024 supplementary
        // characters can encode to 4096 bytes. Also reject log-line/control
        // injection and invalid isolated surrogate code units.
        StringBuilder safe = new StringBuilder(Math.min(text.length(), 1024));
        int bytes = 0;
        for (int offset = 0; offset < text.length();) {
            int cp = text.codePointAt(offset);
            offset += Character.charCount(cp);
            if (cp >= 0xd800 && cp <= 0xdfff
                    || Character.isISOControl(cp)
                    || Character.getType(cp) == Character.LINE_SEPARATOR
                    || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR) {
                cp = ' ';
            }
            int width = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + width > 1024) break;
            safe.appendCodePoint(cp);
            bytes += width;
        }
        return safe.toString();
    }

    private final ActorRuntime.SupervisorId supervisorId;
    private final ActorRuntime.ActorGroupId groupId;
    private final Executor dispatcher;
    private final int capacity;
    private final ArrayDeque<Notice> pending = new ArrayDeque<>();
    private final AtomicLong undelivered = new AtomicLong();
    private Consumer<Notice> handler;
    private boolean draining;
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

    /** Runtime-only publication; public callers cannot forge terminal CONTROL. */
    synchronized void publish(Notice notice) {
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
     * The owning actor polls immutable notices on its own single-flight turn.
     * A host-installed handler and actor polling are mutually exclusive: two
     * consumers must never split or duplicate one supervisor CONTROL stream.
     */
    synchronized Optional<Notice> poll() {
        if (handler != null) {
            throw new IllegalStateException(
                    "supervisor CONTROL has a registered process/handler");
        }
        if (handlerFailure != null) {
            throw new IllegalStateException("supervisor CONTROL lane failed", handlerFailure);
        }
        return Optional.ofNullable(pending.pollFirst());
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
        draining = true;
        try {
            SupervisorDispatchGuard.enqueue(dispatcher, this::drain);
        } catch (Throwable dispatchFailure) {
            handlerFailure = dispatchFailure;
            draining = false;
            undelivered.addAndGet(pending.size());
        }
    }

    private void drain() {
        // Bounded bursts: release the shared dispatcher between batches.
        int processed = 0;
        while (processed < 32) {
            Notice next;
            Consumer<Notice> callback;
            synchronized (this) {
                if (handlerFailure != null || pending.isEmpty()) {
                    draining = false;
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
                    draining = false;
                }
                return;
            }
            synchronized (this) {
                pending.removeFirst();
            }
            processed++;
        }
        synchronized (this) {
            draining = false;
            scheduleLocked();
        }
    }
}
