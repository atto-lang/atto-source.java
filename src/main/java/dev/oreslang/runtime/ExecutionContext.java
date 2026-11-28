package dev.oreslang.runtime;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stable logical execution identity for one Oreslang task.
 *
 * <p>An execution context is not a Java thread, scheduler dispatch, actor heap,
 * or allocation arena. It survives await/cooperate carrier migration while the
 * physical dispatch id changes. Allocation ownership is represented separately
 * so many executions may legitimately share one actor allocation domain.</p>
 *
 * <p>The value is intentionally pointer-free at the semantic boundary. Backend
 * runtimes may store large populations in AoS, SoA, or AoSoA layouts without
 * changing these identities.</p>
 */
public final class ExecutionContext {
    public enum AllocationKind {
        NONE,
        ACTOR,
        TASK,
        RUNTIME
    }

    /** Scheduler/runtime domain plus a stable execution number in that domain. */
    public record ExecutionId(long runtimeDomain, long execution) {
        public ExecutionId {
            if (runtimeDomain <= 0 || execution <= 0) {
                throw new IllegalArgumentException(
                        "execution identity components must be positive");
            }
        }
    }

    /**
     * Allocation ownership identity. NONE is valid for tasks that do not own a
     * persistent heap/arena. ACTOR/TASK/RUNTIME domains are generation-fenced.
     */
    public record AllocationDomain(
            AllocationKind kind,
            long domain,
            long owner,
            long generation) {
        public AllocationDomain {
            Objects.requireNonNull(kind, "kind");
            if (kind == AllocationKind.NONE) {
                if (domain != 0 || owner != 0 || generation != 0) {
                    throw new IllegalArgumentException(
                            "unbound allocation domain must contain only zeros");
                }
            } else if (domain <= 0 || owner < 0 || generation <= 0) {
                throw new IllegalArgumentException(
                        "bound allocation domain requires positive domain/generation and nonnegative owner");
            }
        }

        public static AllocationDomain none() {
            return new AllocationDomain(AllocationKind.NONE, 0, 0, 0);
        }

        public boolean isBound() {
            return kind != AllocationKind.NONE;
        }
    }

    /**
     * One VM-wide space for {@link ExecutionId#runtimeDomain()} values. Both
     * OresScheduler task domains and ActorRuntime actor domains draw from it,
     * so an execution id never collides across the two execution kinds.
     */
    private static final AtomicLong NEXT_RUNTIME_DOMAIN = new AtomicLong();

    static long allocateRuntimeDomain() {
        return nextPositive(NEXT_RUNTIME_DOMAIN, "execution runtime-domain");
    }

    /**
     * Allocate a monotonically increasing positive identity without mutating
     * the counter on exhaustion. A plain incrementAndGet() wraps Long.MAX_VALUE
     * to a negative value before throwing; after a complete wraparound that
     * could eventually reissue old positive identities. Saturation is
     * permanent and therefore fail-closed.
     */
    static long nextPositive(AtomicLong counter, String kind) {
        Objects.requireNonNull(counter, "counter");
        Objects.requireNonNull(kind, "kind");
        for (;;) {
            long current = counter.get();
            if (current < 0 || current == Long.MAX_VALUE) {
                throw new IllegalStateException(kind + " id space exhausted");
            }
            long next = current + 1;
            if (counter.compareAndSet(current, next)) {
                return next;
            }
        }
    }

    /**
     * Reserve a positive, non-overlapping range and return its exclusive upper
     * bound. The counter stores the last reserved ID; saturation is checked
     * before CAS so an exhausted range allocator can never wrap and reuse IDs.
     */
    static long reservePositiveRangeExclusive(
            AtomicLong counter, int count, String kind) {
        Objects.requireNonNull(counter, "counter");
        Objects.requireNonNull(kind, "kind");
        if (count <= 0) {
            throw new IllegalArgumentException("range size must be positive");
        }
        for (;;) {
            long current = counter.get();
            if (current < 0 || current > Long.MAX_VALUE - (long) count - 1L) {
                throw new IllegalStateException(kind + " id space exhausted");
            }
            long last = current + count;
            if (counter.compareAndSet(current, last)) {
                return last + 1L;
            }
        }
    }

    private final ExecutionId id;
    private final AllocationDomain allocationDomain;
    private final long parentExecution;
    private final long continuation;

    private ExecutionContext(
            ExecutionId id,
            AllocationDomain allocationDomain,
            long parentExecution,
            long continuation) {
        this.id = Objects.requireNonNull(id, "id");
        this.allocationDomain = Objects.requireNonNull(allocationDomain, "allocationDomain");
        if (parentExecution < 0 || continuation < 0) {
            throw new IllegalArgumentException("logical ancestry handles cannot be negative");
        }
        this.parentExecution = parentExecution;
        this.continuation = continuation;
    }

    static ExecutionContext task(long runtimeDomain, long execution) {
        return task(runtimeDomain, execution, 0);
    }

    /**
     * Mint a scheduler-owned Task identity with optional same-scheduler
     * parentage. Parentage is immutable diagnostic lineage, never inherited
     * allocation, cancellation, actor-state or scheduler authority.
     */
    static ExecutionContext task(long runtimeDomain, long execution,
            long parentExecution) {
        if (parentExecution < 0 || parentExecution == execution) {
            throw new IllegalArgumentException(
                    "task parent execution must be nonnegative and distinct");
        }
        return new ExecutionContext(
                new ExecutionId(runtimeDomain, execution),
                AllocationDomain.none(),
                parentExecution,
                0);
    }

    static ExecutionContext actor(
            long runtimeDomain,
            long execution,
            long actorDomain,
            long actorOwner,
            long actorGeneration,
            long parentExecution,
            long continuation) {
        return new ExecutionContext(
                new ExecutionId(runtimeDomain, execution),
                new AllocationDomain(
                        AllocationKind.ACTOR,
                        actorDomain,
                        actorOwner,
                        actorGeneration),
                parentExecution,
                continuation);
    }

    public ExecutionId id() {
        return id;
    }

    public AllocationDomain allocationDomain() {
        return allocationDomain;
    }

    /** Scheduler-admitted same-domain parent Task, or zero for a root/unrelated task. */
    public long parentExecution() {
        return parentExecution;
    }

    /** Zero until compiler-lowered continuation storage is attached. */
    public long continuation() {
        return continuation;
    }

    @Override
    public String toString() {
        return "ExecutionContext[" + id.runtimeDomain() + ":" + id.execution()
                + ", allocation=" + allocationDomain.kind() + "]";
    }
}
