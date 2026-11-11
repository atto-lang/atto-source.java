package dev.oreslang.runtime;

import java.util.IdentityHashMap;
import java.util.Objects;

/**
 * Runtime-minted, actor-owned state RW delegation substrate.
 *
 * <p>Ownership stays with the allocating actor. A one-use host/CONTROL grant
 * delegates only a scoped read or write admission, never an actor state
 * pointer, an ActorRef lifecycle privilege, or ownership itself. This
 * boundary is NOT a source-language borrowing or authenticated guest
 * supervisor implementation until compiler lowering supplies those proofs.</p>
 *
 * <p>Instances can only be registered through ActorRuntime's owning actor
 * turn, which checks the live actor allocation and physical turn lease.
 * No public constructor accepts caller-created identity records.</p>
 */
public final class ActorStateSupervisorLoans {
    private static final int MAX_OUTSTANDING_GRANTS = 64;

    private final ActorRuntime runtime;
    private final ActorRuntime.ActorId actorId;
    private final long actorGeneration;
    private final ExecutionContext.AllocationDomain allocation;
    private final ActorStateRwLoans arbiter;
    private final Object gate = new Object();
    private final IdentityHashMap<Grant, Boolean> issued = new IdentityHashMap<>();
    private long nextExecution;
    private boolean revoked;

    // Package-private: ActorRuntime is the sole production issuer.
    ActorStateSupervisorLoans(
            ActorRuntime runtime, ActorRuntime.ActorId actorId,
            long actorGeneration, ExecutionContext.AllocationDomain allocation,
            int maxPending, int maxReaders) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        if (actorGeneration <= 0) {
            throw new IllegalArgumentException("actor generation must be positive");
        }
        this.actorGeneration = actorGeneration;
        this.allocation = Objects.requireNonNull(allocation, "allocation");
        if (allocation.kind() != ExecutionContext.AllocationKind.ACTOR
                || allocation.generation() != actorGeneration) {
            throw new IllegalArgumentException("invalid runtime-minted actor allocation");
        }
        this.arbiter = new ActorStateRwLoans(
                allocation, maxPending, maxReaders,
                () -> runtime.onStateLoanRetired(actorId));
    }

    /** Opaque, one-use host/CONTROL grant; equality is object identity. */
    public final class Grant {
        private final ActorRuntime.ActorSupervisor target;
        private final ActorStateRwLoans.Access access;

        private Grant(
                ActorRuntime.ActorSupervisor target,
                ActorStateRwLoans.Access access) {
            this.target = target;
            this.access = access;
        }

        public ActorStateRwLoans.Access access() { return access; }
    }

    /**
     * No state or raw region reference escapes. Releasing on the host is
     * permitted only outside a guest actor turn; owner loans must unwind on
     * the original actor's physical execution lease.
     */
    public final class Loan implements AutoCloseable {
        private final ActorStateRwLoans.StateLoan delegate;
        private final boolean ownerLoan;

        private Loan(ActorStateRwLoans.StateLoan delegate, boolean ownerLoan) {
            this.delegate = delegate;
            this.ownerLoan = ownerLoan;
        }

        public ActorStateRwLoans.Access access() { return delegate.access(); }
        public long epoch() { return delegate.epoch(); }

        @Override
        public void close() {
            if (ownerLoan) {
                runtime.requireStateRegionOwnerReleaseTurn(actorId, actorGeneration);
            } else {
                runtime.requireSupervisorStateHostContext();
            }
            delegate.close();
        }
    }

    /** Only the owning physical actor turn can issue a delegated read. */
    public Grant delegateRead(ActorRuntime.ActorSupervisor supervisor) {
        return delegate(supervisor, ActorStateRwLoans.Access.READ);
    }

    /** Only the owning physical actor turn can issue a delegated write. */
    public Grant delegateWrite(ActorRuntime.ActorSupervisor supervisor) {
        return delegate(supervisor, ActorStateRwLoans.Access.WRITE);
    }

    private Grant delegate(
            ActorRuntime.ActorSupervisor supervisor, ActorStateRwLoans.Access access) {
        runtime.requireStateRegionOwnerTurn(actorId, actorGeneration);
        runtime.requireDirectStateRegionSupervisor(actorId, actorGeneration, supervisor);
        synchronized (gate) {
            requireOpenLocked();
            if (issued.size() >= MAX_OUTSTANDING_GRANTS) {
                throw new IllegalStateException("actor supervisor loan grant quota exhausted");
            }
            Grant grant = new Grant(supervisor, access);
            issued.put(grant, Boolean.TRUE);
            return grant;
        }
    }

    /**
     * Host CONTROL adapter. The exact designated supervisor object and its
     * immutable managed-group relationship must still be valid at redemption.
     * A grant is consumed once even if physical admission later fails.
     *
     * <p>Host context exclusion is not authenticated source-level CONTROL
     * identity. Do not expose this adapter to an AttoLang guest or FFI
     * import; compiler and CONTROL-identity lowering remain mandatory.</p>
     */
    public OresFuture<Loan> redeem(
            Grant grant, ActorRuntime.ActorSupervisor supervisor) {
        runtime.requireSupervisorStateHostContext();
        Objects.requireNonNull(grant, "grant");
        Objects.requireNonNull(supervisor, "supervisor");
        if (grant.getClass() != Grant.class || grant.target != supervisor) {
            throw new SecurityException("delegation was not issued to this supervisor");
        }
        runtime.requireDirectStateRegionSupervisor(actorId, actorGeneration, supervisor);
        ExecutionContext execution;
        synchronized (gate) {
            requireOpenLocked();
            if (!issued.containsKey(grant)) {
                throw new SecurityException("foreign, stale, or already consumed state grant");
            }
            issued.remove(grant);
            execution = nextMintedExecutionLocked();
        }
        OresFuture<ActorStateRwLoans.StateLoan> pending =
                grant.access == ActorStateRwLoans.Access.READ
                        ? arbiter.acquireReadAsync(execution)
                        : arbiter.acquireWriteAsync(execution);
        return wrap(pending, false);
    }

    public OresFuture<Loan> ownerReadAsync() {
        return ownerAcquire(ActorStateRwLoans.Access.READ);
    }

    public OresFuture<Loan> ownerWriteAsync() {
        return ownerAcquire(ActorStateRwLoans.Access.WRITE);
    }

    private OresFuture<Loan> ownerAcquire(ActorStateRwLoans.Access access) {
        runtime.requireStateRegionOwnerTurn(actorId, actorGeneration);
        ExecutionContext execution;
        synchronized (gate) {
            requireOpenLocked();
            execution = nextMintedExecutionLocked();
        }
        OresFuture<ActorStateRwLoans.StateLoan> pending =
                access == ActorStateRwLoans.Access.READ
                        ? arbiter.acquireReadAsync(execution)
                        : arbiter.acquireWriteAsync(execution);
        return wrap(pending, true);
    }

    private ExecutionContext nextMintedExecutionLocked() {
        if (nextExecution == Long.MAX_VALUE) {
            throw new IllegalStateException("actor state logical execution ids exhausted");
        }
        long serial = ++nextExecution;
        return ExecutionContext.actorBound(
                allocation.domain(), serial, serial, actorGeneration,
                allocation.domain(), allocation.owner(), allocation.generation(),
                0, 0);
    }

    private OresFuture<Loan> wrap(
            OresFuture<ActorStateRwLoans.StateLoan> pending, boolean ownerLoan) {
        OresFuture<Loan> exposed = new OresFuture<>(() -> pending.cancel(false));
        pending.whenCompleteRuntime((physical, failure) -> {
            if (failure != null) {
                exposed.failFromRuntime(OresFuture.unwrap(failure));
            } else if (!exposed.completeFromRuntime(new Loan(physical, ownerLoan))) {
                // Consumer cancelled before delivery. Never leave an
                // undeliverable successful physical loan pinning STOP.
                physical.close();
            }
        });
        return exposed;
    }

    private void requireOpenLocked() {
        if (revoked) {
            throw new IllegalStateException("actor state delegation is revoked");
        }
    }

    public int outstandingGrantCount() {
        synchronized (gate) {
            return issued.size();
        }
    }

    public int activeReaders() { return arbiter.activeReaderCount(); }
    public int activeWriters() { return arbiter.activeWriterCount(); }
    public int pendingCount() { return arbiter.pendingCount(); }

    /** Observational; the actor keeps unique semantic ownership throughout. */
    public boolean physicallyRetired() {
        return arbiter.physicallyRetired();
    }

    /** Runtime STOP only. Not a guest-visible capability or raw state close. */
    void revokeFromRuntimeStop() {
        synchronized (gate) {
            if (revoked) return;
            revoked = true;
            issued.clear();
        }
        arbiter.close();
    }
}
