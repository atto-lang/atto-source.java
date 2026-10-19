package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Host/compiler-only actor-affine structured owner for the V1 source actor
 * hot-upgrade prototype. It is intentionally NOT a second upgrade authority:
 * every frame is admitted by the existing logical router and pinned by the
 * ActorRuntime lifecycle fence. The compiler's OresTaskScope branch must
 * ultimately absorb this adapter after cross-branch reconciliation.
 *
 * <p>Result Future settlement, including cancellation, never removes an owned
 * frame. Only successful physical exit AND generation-lease retirement does.
 * An unsuccessful retirement stays owned, fails close, and retains the actor's
 * generation pin for supervisor reconciliation.</p>
 *
 * <p>This class is not guest-visible. Guest child Task capture/authorization,
 * mutable actor-state loans and untrusted fuel must be checked by the compiler
 * before enabling a source-level path.</p>
 */
final class ActorHotUpgradeTaskOwnerV1 implements AutoCloseable {
    private static final long CLOSE_TIMEOUT_NANOS =
            TimeUnit.SECONDS.toNanos(5);
    private final LogicalSourceActorRouterV1 router;
    private final Object gate = new Object();
    // A scope must reserve its ownership record BEFORE asking the router
    // to admit/pin a physical Task: HashSet growth can fail with OOME.
    private static final class OwnershipSlot {
        private ActorHotUpgradeTaskBridgeV1.PinnedTask<?> task;
    }

    private final Set<OwnershipSlot> active = new HashSet<>();
    private final Set<ActorHotUpgradeTaskOwnerV1> children = new HashSet<>();
    private boolean closed;

    ActorHotUpgradeTaskOwnerV1(LogicalSourceActorRouterV1 router) {
        this.router = Objects.requireNonNull(router, "router");
    }

    ActorHotUpgradeTaskOwnerV1 childScope() {
        requireHost();
        synchronized (gate) {
            ensureOpen();
            ActorHotUpgradeTaskOwnerV1 child =
                    new ActorHotUpgradeTaskOwnerV1(router);
            children.add(child);
            return child;
        }
    }

    <T> OresFuture<T> start(
            OresScheduler scheduler, OresScheduler.Task<T> frame) {
        requireHost();
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(frame, "frame");
        synchronized (gate) {
            ensureOpen();
            // Reserve ownership first: failure to grow the set must occur
            // before actor generation pinning and scheduler admission.
            OwnershipSlot slot = new OwnershipSlot();
            active.add(slot);
            // The router pins the current physical actor under routeLock;
            // ActorRuntime freezes ingress under the cell lifecycle lock.
            final ActorHotUpgradeTaskBridgeV1.PinnedTask<T> work;
            try {
                work = router.startHostPinnedTaskTrackedForUpgrade(
                        scheduler, frame);
            } catch (RuntimeException | Error admissionFailure) {
                // Scheduler.start only throws before registering its runner;
                // the actor bridge must retire that rejected generation pin.
                active.remove(slot);
                throw admissionFailure;
            }
            // No allocating ownership insertion remains after admission.
            slot.task = work;
            try {
                work.physicallyExited().whenCompleteRuntime((ignored, failure) -> {
                    // Result settlement is NOT physical retirement; only
                    // successful onQuiescent generation release removes a slot.
                    if (failure == null) {
                        synchronized (gate) {
                            active.remove(slot);
                        }
                    }
                });
            } catch (RuntimeException | Error callbackFailure) {
                // An admitted frame remains in its pre-reserved slot. Seal
                // further starts and require supervisor close/reconciliation.
                closed = true;
                throw callbackFailure;
            }
            return work.result();
        }
    }

    boolean physicallyQuiescent() {
        synchronized (gate) {
            if (!active.isEmpty()) return false;
            for (ActorHotUpgradeTaskOwnerV1 child : children) {
                if (!child.physicallyQuiescent()) return false;
            }
            return true;
        }
    }

    private void ensureOpen() {
        if (closed)
            throw new RejectedExecutionException(
                    "actor generation Task scope has closed admission");
    }

    private static void requireHost() {
        if (ActorRuntime.inActorExecution()
                || OresScheduler.current() != null
                || OresScheduler.isSchedulerCarrierThread()
                || AsyncRuntime.isAsyncCarrierThread()) {
            throw new SecurityException(
                    "actor generation Task scope requires trusted host control");
        }
    }

    /**
     * Cancel every owned frame BEFORE waiting for any physical exit. An exit
     * fence may settle long after the result becomes cancelled. This host
     * barrier has a finite deadline, and a timeout permanently closes new
     * admission but leaves the old generation pinned and cleanup retryable.
     */
    @Override public void close() {
        requireHost();
        List<ActorHotUpgradeTaskBridgeV1.PinnedTask<?>> records =
                new ArrayList<>();
        List<ActorHotUpgradeTaskOwnerV1> visited = new ArrayList<>();
        ArrayDeque<ActorHotUpgradeTaskOwnerV1> pending =
                new ArrayDeque<>();
        pending.add(this);
        // Seal the entire child tree before notifying any Future listener.
        // Iterative traversal avoids stack overflow for deep child ownership.
        while (!pending.isEmpty()) {
            ActorHotUpgradeTaskOwnerV1 scope = pending.removeLast();
            visited.add(scope);
            synchronized (scope.gate) {
                scope.closed = true;
                for (OwnershipSlot slot : scope.active) {
                    if (slot.task == null)
                        throw new IllegalStateException(
                                "actor Task owner has an uncommitted admission slot");
                    records.add(slot.task);
                }
                pending.addAll(scope.children);
            }
        }
        Throwable failure = null;
        List<Runnable> notifications = new ArrayList<>();
        for (var record : records) {
            try {
                // Revoke EVERY owned scheduler producer before any subscriber
                // can stall cancellation of its siblings. This is the same
                // two-phase protocol used by the canonical TaskScope branch.
                Runnable notify = record.result().cancelDeferredNotification();
                if (notify != null) notifications.add(notify);
            } catch (RuntimeException | Error cancellationFailure) {
                if (failure == null) failure = cancellationFailure;
                else if (failure != cancellationFailure)
                    failure.addSuppressed(cancellationFailure);
            }
        }
        for (Runnable notify : notifications) {
            try {
                notify.run();
            } catch (RuntimeException | Error notificationFailure) {
                if (failure == null) failure = notificationFailure;
                else if (failure != notificationFailure)
                    failure.addSuppressed(notificationFailure);
            }
        }

        // Do not treat result cancellation as a physical-exit receipt.
        long deadline = System.nanoTime() + CLOSE_TIMEOUT_NANOS;
        boolean interrupted = false;
        Set<ActorHotUpgradeTaskBridgeV1.PinnedTask<?>> exited =
                new HashSet<>();
        for (var record : records) {
            for (;;) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    var timeout = new IllegalStateException(
                            "actor Task generation retirement timed out; old image stays pinned");
                    if (failure == null) failure = timeout;
                    else failure.addSuppressed(timeout);
                    break;
                }
                try {
                    record.physicallyExited().get(
                            remaining, TimeUnit.NANOSECONDS);
                    exited.add(record);
                    break;
                } catch (InterruptedException interruptedHost) {
                    interrupted = true;
                } catch (ExecutionException | TimeoutException exitFailure) {
                    var unsafe = new IllegalStateException(
                            "actor Task generation did not retire safely",
                            exitFailure);
                    if (failure == null) failure = unsafe;
                    else failure.addSuppressed(unsafe);
                    break;
                }
            }
        }
        // The physical-exit Future's state can become visible to get()
        // before its callback removes the owner record. Reconcile successful
        // exits synchronously so close() returning is also a strict scope
        // bookkeeping barrier. Failed retirement records remain owned.
        for (ActorHotUpgradeTaskOwnerV1 scope : visited) {
            synchronized (scope.gate) {
                scope.active.removeAll(exited);
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) throw error;
    }
}
