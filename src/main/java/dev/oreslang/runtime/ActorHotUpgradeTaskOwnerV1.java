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
    private final Set<ActorHotUpgradeTaskBridgeV1.PinnedTask<?>> active =
            new HashSet<>();
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
            // The router pins the current physical actor under routeLock;
            // ActorRuntime freezes ingress under the cell lifecycle lock.
            var work = router.startHostPinnedTaskTrackedForUpgrade(
                    scheduler, frame);
            active.add(work);
            work.physicallyExited().whenCompleteRuntime((ignored, failure) -> {
                // A failed trusted generation release is NOT a successful
                // retirement. Keep the record to make close() fail sealed.
                if (failure == null) {
                    synchronized (gate) {
                        active.remove(work);
                    }
                }
            });
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
        ArrayDeque<ActorHotUpgradeTaskOwnerV1> pending =
                new ArrayDeque<>();
        pending.add(this);
        // Seal the entire child tree before notifying any Future listener.
        // Iterative traversal avoids stack overflow for deep child ownership.
        while (!pending.isEmpty()) {
            ActorHotUpgradeTaskOwnerV1 scope = pending.removeLast();
            synchronized (scope.gate) {
                scope.closed = true;
                records.addAll(scope.active);
                pending.addAll(scope.children);
            }
        }
        Throwable failure = null;
        for (var record : records) {
            try {
                record.result().cancel(true);
            } catch (RuntimeException | Error cancellationFailure) {
                if (failure == null) failure = cancellationFailure;
                else if (failure != cancellationFailure)
                    failure.addSuppressed(cancellationFailure);
            }
        }

        // Do not treat result.cancel() as a physical-exit receipt.
        long deadline = System.nanoTime() + CLOSE_TIMEOUT_NANOS;
        boolean interrupted = false;
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
        if (interrupted) Thread.currentThread().interrupt();
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException error) throw error;
    }
}
