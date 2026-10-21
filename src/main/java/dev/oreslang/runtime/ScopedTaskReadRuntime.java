package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.ScopedTaskReadAuthority;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runtime bridge for the compiler-proven structured immutable Task READ gate.
 *
 * <p>This is deliberately not guest-callable syntax lowering. The compiler
 * supplies an exact {@link ScopedTaskReadAuthority}; this runtime retains a
 * same-generation read claim until the scheduler reports physical quiescence.
 * Future completion or cancellation alone never retires the claim.
 */
public final class ScopedTaskReadRuntime {
    private ScopedTaskReadRuntime() { }

    private record AllocationKey(
            String domain,
            String ownerId,
            long allocationId,
            long generation) { }

    private static final ConcurrentHashMap<AllocationKey, AtomicInteger> READERS =
            new ConcurrentHashMap<>();

    public static <T> OresFuture<T> start(
            OresScheduler scheduler,
            OresScheduler.Task<T> task,
            ScopedTaskReadAuthority authority,
            Ast.Program program,
            Ast.TaskExpr taskBoundary,
            long ownershipEpoch,
            long allocationId,
            long generation) {
        Objects.requireNonNull(scheduler, "scheduler");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(authority, "scoped READ authority");
        Objects.requireNonNull(program, "checked Program");
        Objects.requireNonNull(taskBoundary, "checked Task boundary");

        authority.require(
                program,
                taskBoundary,
                ownershipEpoch,
                allocationId,
                generation);

        ScopedTaskReadAuthority.AllocationIdentity allocation =
                authority.allocation();
        AllocationKey key = new AllocationKey(
                allocation.domain(),
                allocation.ownerId(),
                allocationId,
                generation);

        Lease lease = register(key);
        try {
            // The scheduler callback is the physical lifetime edge. A Future
            // may settle/cancel earlier; the read claim intentionally survives.
            return scheduler.start(task, lease::retireAtPhysicalQuiescence);
        } catch (RuntimeException | Error failure) {
            // start() can throw only before a tracked Task is successfully
            // admitted. If publication happened, the scheduler owns callback
            // retirement and start() returns the failed/cancelled Future.
            lease.retireBeforeAdmission();
            throw failure;
        }
    }

    /**
     * Parent/runtime write barrier for the exact allocation generation.
     * A replacement allocation with a new generation is a distinct authority.
     */
    public static void requireWritable(
            ScopedTaskReadAuthority.AllocationIdentity allocation) {
        Objects.requireNonNull(allocation, "allocation");
        AllocationKey key = new AllocationKey(
                allocation.domain(),
                allocation.ownerId(),
                allocation.allocationId(),
                allocation.generation());
        AtomicInteger readers = READERS.get(key);
        if (readers != null && readers.get() > 0) {
            throw new IllegalStateException(
                    "allocation has live scoped Task READ authority");
        }
    }

    static int activeReadersForTesting(
            ScopedTaskReadAuthority.AllocationIdentity allocation) {
        AllocationKey key = new AllocationKey(
                allocation.domain(),
                allocation.ownerId(),
                allocation.allocationId(),
                allocation.generation());
        AtomicInteger readers = READERS.get(key);
        return readers == null ? 0 : readers.get();
    }

    private static Lease register(AllocationKey key) {
        AtomicInteger readers = READERS.compute(key, (ignored, current) -> {
            AtomicInteger counter = current == null
                    ? new AtomicInteger()
                    : current;
            int next = counter.incrementAndGet();
            if (next <= 0) {
                throw new IllegalStateException(
                        "scoped Task READ reader count overflow");
            }
            return counter;
        });
        if (readers == null || readers.get() <= 0) {
            throw new IllegalStateException(
                    "scoped Task READ registration failed");
        }
        return new Lease(key);
    }

    private static final class Lease {
        private final AllocationKey key;
        private final AtomicBoolean retired = new AtomicBoolean();

        private Lease(AllocationKey key) {
            this.key = key;
        }

        private void retireBeforeAdmission() {
            retire("pre-admission");
        }

        private void retireAtPhysicalQuiescence() {
            retire("physical quiescence");
        }

        private void retire(String edge) {
            if (!retired.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "scoped Task READ lease retired more than once at " + edge);
            }
            READERS.compute(key, (ignored, counter) -> {
                if (counter == null) {
                    throw new IllegalStateException(
                            "scoped Task READ ledger entry missing at " + edge);
                }
                int remaining = counter.decrementAndGet();
                if (remaining < 0) {
                    throw new IllegalStateException(
                            "scoped Task READ reader count underflow");
                }
                return remaining == 0 ? null : counter;
            });
        }
    }
}
