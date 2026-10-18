package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime-only structured cancellation authority for Oreslang Tasks.
 *
 * <p>Ownership forms an acyclic, single-parent tree. A cancellation request
 * atomically marks an entire currently owned subtree under the graph lock,
 * before running any revokers or observers. Callback execution is outside that
 * lock: guest code must never be called from these hooks. Source-language
 * access to cancellation remains subject to actor/tenant capability checks.
 * This class does not itself stop executing code or witness physical teardown.
 */
public final class TaskCancellation {
    private static final Object GRAPH_LOCK = new Object();
    private static final String DEFAULT_REASON = "cancelled";
    private final Set<TaskCancellation> children =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Registration> revokers = new LinkedHashSet<>();
    private final Set<Registration> observers = new LinkedHashSet<>();
    private TaskCancellation owner;
    private volatile boolean cancelled;
    private volatile String cancellationReason;

    /** Create a root cancellation authority, owned by the runtime or actor. */
    public TaskCancellation() { }

    /** Create a structurally owned child. A cancelled parent yields a cancelled child. */
    public TaskCancellation child() {
        synchronized (GRAPH_LOCK) {
            TaskCancellation child = new TaskCancellation();
            child.owner = this;
            if (cancelled) {
                child.cancelled = true;
                child.cancellationReason = cancellationReason;
            } else {
                children.add(child);
            }
            return child;
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /** Null until cancellation was requested. The first reason wins. */
    public String cancellationReason() {
        return cancellationReason;
    }

    public boolean isOwnedBy(TaskCancellation expectedOwner) {
        synchronized (GRAPH_LOCK) {
            return owner == expectedOwner;
        }
    }

    /**
     * Atomic ownership transfer. Never changes a cancellation decision already
     * made, never permits cycles, and never leaves an orphaned active child.
     * A source-language transfer must separately prove authority for BOTH owners.
     */
    public void adoptInto(TaskCancellation newOwner) {
        Objects.requireNonNull(newOwner, "newOwner");
        synchronized (GRAPH_LOCK) {
            if (cancelled) throw new IllegalStateException("cannot transfer cancelled task");
            if (newOwner.cancelled) throw new IllegalStateException("cannot adopt into cancelled owner");
            if (owner == null) throw new IllegalStateException("cannot transfer a root authority");
            TaskCancellation cursor = newOwner;
            while (cursor != null) {
                if (cursor == this) throw new IllegalArgumentException("ownership cycle");
                cursor = cursor.owner;
            }
            if (owner == newOwner) return;
            owner.children.remove(this);
            newOwner.children.add(this);
            owner = newOwner;
        }
    }

    /**
     * Idempotently request cancellation. First reason wins. All owned
     * descendants are revoked in the same atomic graph transition. Runtime
     * revokers run before observers, outside the lock. No completion/cleanup
     * guarantee is implied; callers must await a separate teardown barrier.
     */
    public boolean cancel(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (reason.isBlank()) throw new IllegalArgumentException("reason must not be blank");
        List<Registration> revoke = new ArrayList<>();
        List<Registration> notify = new ArrayList<>();
        synchronized (GRAPH_LOCK) {
            if (cancelled) return false;
            ArrayDeque<TaskCancellation> queue = new ArrayDeque<>();
            queue.add(this);
            while (!queue.isEmpty()) {
                TaskCancellation node = queue.removeFirst();
                if (node.cancelled) continue;
                node.cancelled = true;
                node.cancellationReason = reason;
                queue.addAll(node.children);
                node.children.clear();
                if (node.owner != null) node.owner.children.remove(node);
                revoke.addAll(node.revokers);
                notify.addAll(node.observers);
                node.revokers.clear();
                node.observers.clear();
            }
        }
        for (Registration hook : revoke) hook.deliver();
        for (Registration hook : notify) hook.deliver();
        return true;
    }

    public boolean cancel() {
        return cancel(DEFAULT_REASON);
    }

    /** Runtime-only nonblocking revocation hook, such as a scheduler cancel. */
    public Subscription onRevoke(Runnable revoker) {
        return register(revoker, true);
    }

    /** Runtime-only observation hook, never a guest continuation callback. */
    public Subscription onCancel(Runnable observer) {
        return register(observer, false);
    }

    /**
     * Directional link: cancelling source requests cancellation here. This does
     * NOT transfer ownership and does NOT create a bidirectional Erlang link.
     * Closing the returned subscription unlinks future notifications, never
     * reverses cancellation which has already been requested.
     */
    public Subscription linkCancellationFrom(TaskCancellation source) {
        Objects.requireNonNull(source, "source");
        if (source == this) throw new IllegalArgumentException("self cancellation link");
        return source.onCancel(() -> cancel("linked:" +
                Objects.requireNonNullElse(source.cancellationReason(), DEFAULT_REASON)));
    }

    private Subscription register(Runnable hook, boolean revoke) {
        Objects.requireNonNull(hook, "hook");
        Registration registration = new Registration(this, hook, revoke);
        boolean alreadyCancelled;
        synchronized (GRAPH_LOCK) {
            alreadyCancelled = cancelled;
            if (!alreadyCancelled) {
                (revoke ? revokers : observers).add(registration);
            }
        }
        if (alreadyCancelled) registration.deliver();
        return registration;
    }

    public interface Subscription extends AutoCloseable {
        @Override void close();
    }

    private static final class Registration implements Subscription {
        private final TaskCancellation owner;
        private final Runnable hook;
        private final boolean revoke;
        private final AtomicBoolean active = new AtomicBoolean(true);

        private Registration(TaskCancellation owner, Runnable hook, boolean revoke) {
            this.owner = owner;
            this.hook = hook;
            this.revoke = revoke;
        }

        private void deliver() {
            if (!active.compareAndSet(true, false)) return;
            try {
                hook.run();
            } catch (RuntimeException | Error ignored) {
                // Cancellation is irreversible, including with faulty callbacks.
                // Callbacks are trusted runtime hooks; diagnostics are handled
                // by their owning runtime component.
            }
        }

        @Override public void close() {
            if (!active.compareAndSet(true, false)) return;
            synchronized (GRAPH_LOCK) {
                (revoke ? owner.revokers : owner.observers).remove(this);
            }
        }
    }
}
