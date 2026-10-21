package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.ScopedTaskReadAuthority;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private static final int WRITE_HELD = -1;
    private static final int POISONED = Integer.MIN_VALUE;
    private static final ConcurrentHashMap<AllocationKey, Integer> ACCESS =
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
     * Atomically reserve parent/runtime WRITE authority for the exact
     * allocation generation. The returned lease MUST bracket the physical
     * write. A check-only API is insufficient because a reader could register
     * immediately after the check and race the write.
     */
    public static WriteLease acquireWrite(
            ScopedTaskReadAuthority.AllocationIdentity allocation) {
        Objects.requireNonNull(allocation, "allocation");
        AllocationKey key = key(allocation);
        ACCESS.compute(key, (ignored, current) -> {
            if (current != null) {
                if (current == WRITE_HELD) {
                    throw new IllegalStateException(
                            "allocation already has live WRITE authority");
                }
                if (current == POISONED) {
                    throw new IllegalStateException(
                            "allocation Task access authority is poisoned");
                }
                if (current > 0) {
                    throw new IllegalStateException(
                            "allocation has live scoped Task READ authority");
                }
                throw new IllegalStateException(
                        "scoped Task access ledger is corrupt");
            }
            return WRITE_HELD;
        });
        return new WriteLease(key);
    }

    /**
     * Check-only diagnostic helper. Callers that will actually mutate storage
     * must use {@link #acquireWrite(ScopedTaskReadAuthority.AllocationIdentity)}
     * so reader admission and WRITE acquisition are linearized.
     */
    public static void requireWritable(
            ScopedTaskReadAuthority.AllocationIdentity allocation) {
        Objects.requireNonNull(allocation, "allocation");
        Integer state = ACCESS.get(key(allocation));
        if (state == null) return;
        if (state == WRITE_HELD) {
            throw new IllegalStateException(
                    "allocation already has live WRITE authority");
        }
        if (state == POISONED) {
            throw new IllegalStateException(
                    "allocation Task access authority is poisoned");
        }
        if (state > 0) {
            throw new IllegalStateException(
                    "allocation has live scoped Task READ authority");
        }
        throw new IllegalStateException(
                "scoped Task access ledger is corrupt");
    }

    static int activeReadersForTesting(
            ScopedTaskReadAuthority.AllocationIdentity allocation) {
        Integer state = ACCESS.get(key(allocation));
        return state != null && state > 0 ? state : 0;
    }

    static boolean writeHeldForTesting(
            ScopedTaskReadAuthority.AllocationIdentity allocation) {
        return Integer.valueOf(WRITE_HELD).equals(ACCESS.get(key(allocation)));
    }


    private static AllocationKey key(
            ScopedTaskReadAuthority.AllocationIdentity allocation) {
        return new AllocationKey(
                allocation.domain(),
                allocation.ownerId(),
                allocation.allocationId(),
                allocation.generation());
    }

    private static Lease register(AllocationKey key) {
        Integer readers = ACCESS.compute(key, (ignored, current) -> {
            if (current != null && current == WRITE_HELD) {
                throw new IllegalStateException(
                        "cannot admit scoped Task READ while WRITE authority is live");
            }
            if (current != null && current == POISONED) {
                throw new IllegalStateException(
                        "cannot admit scoped Task READ on poisoned allocation authority");
            }
            int count = current == null ? 0 : current;
            if (count < 0) {
                throw new IllegalStateException(
                        "scoped Task access ledger is corrupt");
            }
            if (count == Integer.MAX_VALUE) {
                throw new IllegalStateException(
                        "scoped Task READ reader count overflow");
            }
            return count + 1;
        });
        if (readers == null || readers <= 0) {
            throw new IllegalStateException(
                    "scoped Task READ registration failed");
        }
        return new Lease(key);
    }

    public static final class WriteLease implements AutoCloseable {
        private final AllocationKey key;
        private final AtomicBoolean released = new AtomicBoolean();

        private WriteLease(AllocationKey key) {
            this.key = key;
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "scoped Task WRITE lease released more than once");
            }
            Integer after = ACCESS.compute(key, (ignored, state) -> {
                if (state == null || state != WRITE_HELD) {
                    return POISONED;
                }
                return null;
            });
            if (Integer.valueOf(POISONED).equals(after)) {
                throw new IllegalStateException(
                        "scoped Task WRITE ledger entry missing/corrupt at release");
            }
        }
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
            Integer after = ACCESS.compute(key, (ignored, counter) -> {
                if (counter == null || counter <= 0) {
                    return POISONED;
                }
                int remaining = counter - 1;
                return remaining == 0 ? null : remaining;
            });
            if (Integer.valueOf(POISONED).equals(after)) {
                throw new IllegalStateException(
                        "scoped Task READ ledger entry missing/corrupt at " + edge);
            }
        }
    }
}
