package dev.oreslang.runtime;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
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
    /** The same actor physical turn must not impersonate multiple borrowers. */
    private Object activeOwnerSegment;
    private boolean revoked;
    private boolean arbiterClosed;
    /** Owned primitive proof slot: never return a mutable actor-state alias. */
    private long protectedScalar;
    /** Primitive actor field cells are the canonical storage, not a mirror. */
    private final Map<String, LongField> boundLongFields = new LinkedHashMap<>();
    private static final int MAX_BOUND_LONG_FIELDS = 64;

    /**
     * Nominal typed storage identity, minted only by the owning actor turn.
     * The value lives in this actor-owned region and is accessible only under
     * an authenticated physical RW loan. A handle alone conveys no access.
     */
    public final class LongField {
        private final ActorStateSupervisorLoans region = ActorStateSupervisorLoans.this;
        private final String name;
        private long value;

        private LongField(String name, long initialValue) {
            this.name = name;
            this.value = initialValue;
        }
    }

    /**
     * Compiler/host initialization bridge: bind exactly one named i64 slot
     * while executing the owning actor incarnation. This is the actual slot
     * backing, not an observational copy of separate guest state.
     */
    public LongField declareOwnerLongField(String name, long initialValue) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank() || name.length() > 128) {
            throw new IllegalArgumentException("actor long field requires a bounded nonblank name");
        }
        // Registration is a WRITE, not a free metadata update: even field
        // creation must linearize against outstanding supervisor readers and
        // writers. Actor carriers cannot block; contention fails closed.
        final LongField[] minted = new LongField[1];
        withOwnerWrite(() -> {
            synchronized (gate) {
                requireOpenLocked();
                if (boundLongFields.size() >= MAX_BOUND_LONG_FIELDS) {
                    throw new IllegalStateException("actor typed long field quota exhausted");
                }
                if (boundLongFields.containsKey(name)) {
                    throw new IllegalArgumentException(
                            "duplicate actor long field identity: " + name);
                }
                LongField field = new LongField(name, initialValue);
                boundLongFields.put(name, field);
                minted[0] = field;
            }
        });
        return minted[0];
    }

    private void requireBoundLongFieldLocked(LongField field) {
        if (field == null || field.region != this
                || boundLongFields.get(field.name) != field) {
            throw new SecurityException("foreign or unregistered actor primitive field");
        }
    }

    public long readOwnerLongField(LongField field) {
        final long[] value = new long[1];
        withOwnerRead(() -> {
            synchronized (gate) {
                requireOpenLocked();
                requireBoundLongFieldLocked(field);
                value[0] = field.value;
            }
        });
        return value[0];
    }

    /**
     * One physically coherent owner READ of several typed fields.
     * The supplied names must match each nominal runtime-minted field.
     * Snapshot values are immutable primitives; no region/loan escapes.
     */
    public Map<String, Long> snapshotOwnerLongFields(
            Map<String, LongField> fields) {
        Objects.requireNonNull(fields, "fields");
        final java.util.concurrent.atomic.AtomicReference<Map<String, Long>> snapshot =
                new java.util.concurrent.atomic.AtomicReference<>();
        withOwnerRead(() -> {
            synchronized (gate) {
                requireOpenLocked();
                LinkedHashMap<String, Long> copied = new LinkedHashMap<>();
                for (Map.Entry<String, LongField> entry : fields.entrySet()) {
                    LongField field = entry.getValue();
                    requireBoundLongFieldLocked(field);
                    if (!field.name.equals(entry.getKey())) {
                        throw new SecurityException("actor field identity and layout key differ");
                    }
                    copied.put(field.name, field.value);
                }
                snapshot.set(java.util.Collections.unmodifiableMap(copied));
            }
        });
        return snapshot.get();
    }

    public void updateOwnerLongField(
            LongField field, java.util.function.LongUnaryOperator updater) {
        Objects.requireNonNull(updater, "updater");
        withOwnerWrite(() -> {
            long oldValue;
            synchronized (gate) {
                requireOpenLocked();
                requireBoundLongFieldLocked(field);
                oldValue = field.value;
            }
            long next = updater.applyAsLong(oldValue);
            synchronized (gate) {
                // STOP seals commits on the same gate as the typed cell.
                requireOpenLocked();
                requireBoundLongFieldLocked(field);
                field.value = next;
            }
        });
    }


    // Package-private: ActorRuntime is the sole production issuer.
    ActorStateSupervisorLoans(
            ActorRuntime runtime, Object runtimeMint, ActorRuntime.ActorId actorId,
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
        runtime.claimStateRegionMint(runtimeMint, actorId, actorGeneration, allocation);
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
     * Host-only supervisor loan receipt: no state or raw region reference
     * escapes. Closing is barred inside guest actor/Mailman execution.
     * Owner turns never receive this type: they use withOwnerRead/Write
     * with lexical try-with-resources cleanup instead.
     *
     * This host close is not yet a compiler-proven CONTROL unwind receipt.
     */
    // Trusted legacy host/test handle only. Never exposed to source guests.
    // New compiler lowering uses a scoped CONTROL callback, not this type.
    final class Loan implements AutoCloseable {
        private final ActorStateRwLoans.StateLoan delegate;
        private final ActorRuntime.ActorSupervisor target;
        private final ExecutionContext execution;
        private boolean released;

        private Loan(
                ActorStateRwLoans.StateLoan delegate,
                ActorRuntime.ActorSupervisor target, ExecutionContext execution) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
            this.target = Objects.requireNonNull(target, "target");
            this.execution = Objects.requireNonNull(execution, "execution");
        }

        public ActorStateRwLoans.Access access() { return delegate.access(); }
        public long epoch() { return delegate.epoch(); }

        /** No raw reference escapes; read validates live authority atomically. */
        public long readScalar() {
            runtime.requireSupervisorStateHostContext(target);
            synchronized (gate) {
                requireLiveLocked();
                return protectedScalar;
            }
        }

        /** A read loan never writes even when it has the correct supervisor. */
        public void writeScalar(long value) {
            runtime.requireSupervisorStateHostContext(target);
            synchronized (gate) {
                requireLiveLocked();
                if (delegate.access() != ActorStateRwLoans.Access.WRITE) {
                    throw new SecurityException("protected state mutation requires exclusive writer");
                }
                protectedScalar = value;
            }
        }

        private void requireLiveLocked() {
            if (revoked || released || !delegate.heldBy(execution)) {
                throw new SecurityException("state loan was revoked or physically released");
            }
        }

        @Override
        public void close() {
            runtime.requireSupervisorStateHostContext(target);
            synchronized (gate) {
                if (released) return;
                released = true;
            }
            // Never publish synchronous Future callbacks while holding gate.
            delegate.close();
        }
    }


    /**
     * Scoped primitive access. Constructed only by withSupervisorRead/Write,
     * never by callers. A captured reference expires before the CONTROL turn
     * returns and cannot be replayed even on a recycled CONTROL carrier.
     *
     * No actor state object, field reference, direct buffer or address escapes.
     */
    public final class ScopedScalar {
        private final Loan loan;
        private final Object physicalTurn;
        private boolean active = true;

        private ScopedScalar(Loan loan, Object physicalTurn) {
            this.loan = Objects.requireNonNull(loan, "loan");
            this.physicalTurn = Objects.requireNonNull(physicalTurn, "physicalTurn");
        }

        private void requireActive() {
            runtime.requireSupervisorStateControlTurn(loan.target, physicalTurn);
            synchronized (gate) {
                if (!active) {
                    throw new SecurityException(
                            "actor state view escaped its lexical supervisor CONTROL scope");
                }
                loan.requireLiveLocked();
            }
        }

        public long read() {
            requireActive();
            return loan.readScalar();
        }

        public void write(long value) {
            requireActive();
            loan.writeScalar(value);
        }

        /** Read the actual actor-owned typed field, not the proof scalar. */
        public long read(LongField field) {
            requireActive();
            synchronized (gate) {
                requireOpenLocked();
                if (!active) throw new SecurityException("actor field view expired");
                loan.requireLiveLocked();
                requireBoundLongFieldLocked(field);
                return field.value;
            }
        }

        /** Requires the same exclusive physical CONTROL loan as a scalar write. */
        public void write(LongField field, long value) {
            requireActive();
            synchronized (gate) {
                requireOpenLocked();
                if (!active) throw new SecurityException("actor field view expired");
                loan.requireLiveLocked();
                requireBoundLongFieldLocked(field);
                if (loan.delegate.access() != ActorStateRwLoans.Access.WRITE) {
                    throw new SecurityException("actor field mutation requires exclusive writer");
                }
                field.value = value;
            }
        }

        private void invalidate() {
            synchronized (gate) {
                active = false;
            }
        }
    }

    /**
     * Compiler-facing, non-suspending CONTROL read. The grant must be a READ
     * capability, never an implicit coercion from WRITE. A queued acquisition
     * aborts instead of parking the CONTROL carrier.
     */
    void withSupervisorRead(
            Grant grant, ActorRuntime.ActorSupervisor supervisor,
            java.util.function.Consumer<ScopedScalar> operation) {
        withSupervisorScope(grant, supervisor, ActorStateRwLoans.Access.READ, operation);
    }

    /**
     * Compiler-facing, non-suspending exclusive CONTROL write. Physical
     * release occurs only in our finally after the user callback returns or
     * throws; the callback cannot close its own loan early.
     */
    void withSupervisorWrite(
            Grant grant, ActorRuntime.ActorSupervisor supervisor,
            java.util.function.Consumer<ScopedScalar> operation) {
        withSupervisorScope(grant, supervisor, ActorStateRwLoans.Access.WRITE, operation);
    }

    private void withSupervisorScope(
            Grant grant, ActorRuntime.ActorSupervisor supervisor,
            ActorStateRwLoans.Access required,
            java.util.function.Consumer<ScopedScalar> operation) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(grant, "grant");
        Objects.requireNonNull(supervisor, "supervisor");
        Object physicalTurn = runtime.captureSupervisorStateControlTurn(supervisor);
        if (grant.access != required) {
            throw new SecurityException("supervisor CONTROL loan mode does not match grant");
        }
        OresFuture<Loan> pending = redeem(grant, supervisor);
        if (!pending.isDone()) {
            // The caller must never park a CONTROL carrier. A cancellation
            // racing a grant completion may lose the terminal CAS. Recover
            // the resulting physical loan through its internal close path,
            // without exposing it to a guest continuation.
            if (!pending.cancel(false)) {
                pending.whenCompleteRuntime((loan, failure) -> {
                    if (loan != null) loan.delegate.close();
                });
            }
            throw new IllegalStateException(
                    "supervisor CONTROL scope would suspend awaiting state RW admission");
        }
        Loan loan = pending.join();
        ScopedScalar scope = new ScopedScalar(loan, physicalTurn);
        try {
            operation.accept(scope);
        } finally {
            // Invalidate BEFORE physical release/promotion, including when
            // the callback throws. A retained handle can never touch the
            // following owner's state after this point.
            scope.invalidate();
            loan.close();
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
    // Trusted legacy host adapter for existing runtime-only tests. Never
    // lower guest borrows through a free-standing Loan/Future: use one of
    // the scoped CONTROL methods above to prove physical lexical unwind.
    OresFuture<Loan> redeem(
            Grant grant, ActorRuntime.ActorSupervisor supervisor) {
        Objects.requireNonNull(grant, "grant");
        Objects.requireNonNull(supervisor, "supervisor");
        runtime.requireSupervisorStateHostContext(supervisor);
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
        return wrap(pending, supervisor, execution);
    }

    /**
     * Lexically scoped owner turn operation. An actor is forbidden to park
     * a carrier or retain a loan across await/next mailbox turn. Contention
     * fails fast; future async lowering requires compiler-proven continuation
     * scope and is not exposed by this host substrate.
     */
    public void withOwnerRead(Runnable operation) {
        withOwnerLoan(ActorStateRwLoans.Access.READ, operation);
    }

    public void withOwnerWrite(Runnable operation) {
        withOwnerLoan(ActorStateRwLoans.Access.WRITE, operation);
    }

    /**
     * Actor-owned lexical read of the same primitive proof slot used by
     * delegated supervisor loans. Only the immutable scalar snapshot escapes,
     * never an actor-state pointer or a borrowable mutable field.
     */
    public void withOwnerScalarRead(java.util.function.LongConsumer reader) {
        Objects.requireNonNull(reader, "reader");
        withOwnerRead(() -> {
            long snapshot;
            synchronized (gate) {
                requireOpenLocked();
                snapshot = protectedScalar;
            }
            reader.accept(snapshot);
        });
    }

    /**
     * Actor-owned lexical exclusive mutation. User computation occurs outside
     * the lock; the physical RW loan keeps other readers/writers excluded.
     * STOP between computation and commit aborts rather than writing revoked
     * memory.
     */
    public void withOwnerScalarUpdate(java.util.function.LongUnaryOperator updater) {
        Objects.requireNonNull(updater, "updater");
        withOwnerWrite(() -> {
            long before;
            synchronized (gate) {
                requireOpenLocked();
                before = protectedScalar;
            }
            long after = updater.applyAsLong(before);
            synchronized (gate) {
                requireOpenLocked();
                protectedScalar = after;
            }
        });
    }

    private void withOwnerLoan(
            ActorStateRwLoans.Access access, Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        runtime.requireStateRegionOwnerTurn(actorId, actorGeneration);
        Object segment = ActorRuntime.currentMutexExecutionSegment();
        ExecutionContext execution;
        synchronized (gate) {
            requireOpenLocked();
            if (activeOwnerSegment != null) {
                throw new IllegalStateException(
                        "actor owner cannot recursively acquire a shared-state RW loan");
            }
            // Pin the caller's physical segment before minting synthetic
            // execution identity. A nested callback cannot launder itself
            // into a second reader by requesting a fresh execution id.
            activeOwnerSegment = segment;
            try {
                execution = nextMintedExecutionLocked();
            } catch (RuntimeException | Error failure) {
                activeOwnerSegment = null;
                throw failure;
            }
        }
        try {
            OresFuture<ActorStateRwLoans.StateLoan> pending =
                    access == ActorStateRwLoans.Access.READ
                            ? arbiter.acquireReadAsync(execution)
                            : arbiter.acquireWriteAsync(execution);
            if (!pending.isDone()) {
                // Never park an actor carrier; a promotion that races
                // cancellation must close its physical loan after delivery.
                if (!pending.cancel(false)) {
                    pending.whenCompleteRuntime((loan, failure) -> {
                        if (loan != null) loan.close();
                    });
                }
                throw new IllegalStateException(
                        "actor-owned state RW operation would suspend its physical turn");
            }
            try (ActorStateRwLoans.StateLoan loan = pending.join()) {
                if (!loan.heldBy(execution)) {
                    throw new IllegalStateException(
                            "owner state loan was revoked before entering lexical scope");
                }
                operation.run();
            }
        } finally {
            synchronized (gate) {
                if (activeOwnerSegment != segment) {
                    throw new IllegalStateException(
                            "actor owner execution segment changed during RW scope");
                }
                activeOwnerSegment = null;
            }
        }
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
            OresFuture<ActorStateRwLoans.StateLoan> pending,
            ActorRuntime.ActorSupervisor supervisor, ExecutionContext execution) {
        OresFuture<Loan> exposed = new OresFuture<>(() -> pending.cancel(false));
        pending.whenCompleteRuntime((physical, failure) -> {
            if (failure != null) {
                exposed.failFromRuntime(OresFuture.unwrap(failure));
            } else if (!exposed.completeFromRuntime(new Loan(physical, supervisor, execution))) {
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

    /**
     * STOP admission linearization. Called under ActorCell.lifecycleLock
     * BEFORE publishing stopped=true: this operation takes only the region
     * monitor and never enters an OresFuture callback. A scalar writer
     * and this seal serialize on the same monitor.
     */
    void sealFromRuntimeStop() {
        synchronized (gate) {
            revoked = true;
            issued.clear();
            // Stop queued promotions without publishing their Futures while
            // the actor lifecycle monitor is held. close() below performs
            // terminal host notifications after that monitor is released.
            arbiter.sealAdmissionsWithoutCallbacks();
        }
    }

    /**
     * After the actor has sealed descendant admission, physically close the
     * queued arbiter OUTSIDE lifecycleLock. A prior logical seal must not
     * suppress terminal Future notification/drain publication.
     */
    void revokeFromRuntimeStop() {
        synchronized (gate) {
            if (arbiterClosed) return;
            revoked = true;
            issued.clear();
            arbiterClosed = true;
        }
        arbiter.close();
    }
}
