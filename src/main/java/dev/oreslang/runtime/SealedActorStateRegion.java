package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Physical scalar storage substrate for compiler-sealed actor state.
 *
 * <p>Package-private by design: never a guest-callable allocator, handle,
 * reflection escape hatch or replacement for MIR lowering. The trusted source
 * actor executor may allocate it only while ActorRuntime has installed the
 * actual actor execution context. Future lowering must additionally prove
 * checked critical syntax, field identity and non-suspending effects.</p>
 *
 * <p>Every read/write revalidates the runtime-minted actor identity, execution
 * segment and the active {@link ActorStateLoans} ticket. Retirement fences
 * access immediately and waits for the lexical finally release before
 * reclaiming scalar storage. No mutable state object/array escapes a view.</p>
 */
final class SealedActorStateRegion
        implements ActorRuntime.ActorOwnedGuestState, AutoCloseable {
    private final Object lock = new Object();
    private final ActorRuntime runtime;
    private final ActorRuntime.ActorId actorId;
    private final Object executionDomain;
    private final LinkedHashMap<String, Slot> slots = new LinkedHashMap<>();
    private final ActorStateLoans loans = new ActorStateLoans(0);
    private long generation = 1;
    private boolean retired;
    private boolean physicalTasksQuiesced;
    private boolean reclaimed;

    private record Slot(Class<?> type, Object initial) {
        Slot {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(initial, "initial");
            if ((type != Long.class || !(initial instanceof Long))
                    && (type != Boolean.class || !(initial instanceof Boolean))) {
                throw new IllegalArgumentException("sealed slot must be typed int/bool");
            }
        }
    }

    // Values must remain region-owned; never expose this mutable array.
    private final Object[] values;

    /**
     * Translate only a compiler-owned actor declaration's distinct sealed
     * schema into runtime scalar slots. No ordinary ClassDecl.fields layout
     * is allowed. This does not turn off TypeChecker admission: the checked
     * critical HIR/MIR access path is still unimplemented.
     */
    static SealedActorStateRegion allocateFromSealedDeclaration(Ast.ClassDecl declaration) {
        Objects.requireNonNull(declaration, "sealed actor declaration");
        if (declaration.actorKind() == Ast.ActorKind.NONE
                || !declaration.fields().isEmpty()
                || declaration.sealedStateFields().isEmpty()
                || declaration.sealedStateFields().size() > 64) {
            throw new IllegalArgumentException("not a valid compiler-sealed actor schema");
        }
        Map<String, Object> initialized = new LinkedHashMap<>();
        for (Ast.ProtectedStateField field : declaration.sealedStateFields()) {
            if (!(field.initializer() instanceof Ast.LiteralExpr literal)) {
                throw new IllegalArgumentException("sealed initializer must be literal");
            }
            Object value = literal.value();
            boolean typed = (field.type().name().equals("int") && value instanceof Long)
                    || (field.type().name().equals("bool") && value instanceof Boolean);
            if (!typed || !field.type().arguments().isEmpty()) {
                throw new IllegalArgumentException("sealed schema initializer type mismatch");
            }
            if (initialized.putIfAbsent(field.name(), value) != null) {
                throw new IllegalArgumentException("duplicate compiler-sealed state field");
            }
        }
        return allocate(initialized);
    }

    static SealedActorStateRegion allocate(Map<String, ?> literalInitializers) {
        ActorRuntime current = ActorRuntime.currentActorRuntime();
        ActorRuntime.ActorId id = ActorRuntime.currentActorId().orElseThrow(
                () -> new SecurityException("sealed region allocation requires an actor turn"));
        Object domain = ActorRuntime.currentActorExecutionDomain();
        if (current == null || domain == null) {
            throw new SecurityException("sealed region allocation requires runtime actor authority");
        }
        return new SealedActorStateRegion(current, id, domain, literalInitializers);
    }

    private SealedActorStateRegion(ActorRuntime runtime, ActorRuntime.ActorId actorId,
                                   Object executionDomain, Map<String, ?> initializers) {
        this.runtime = Objects.requireNonNull(runtime);
        this.actorId = Objects.requireNonNull(actorId);
        this.executionDomain = Objects.requireNonNull(executionDomain);
        Objects.requireNonNull(initializers, "sealed schema");
        if (initializers.isEmpty() || initializers.size() > 64) {
            throw new IllegalArgumentException("sealed state requires 1..64 fields");
        }
        for (Map.Entry<String, ?> field : initializers.entrySet()) {
            String name = field.getKey();
            Object initial = field.getValue();
            if (name == null || !name.matches("[A-Za-z_][A-Za-z_0-9]*")) {
                throw new IllegalArgumentException("invalid sealed field identifier");
            }
            if (initial instanceof Long) slots.put(name, new Slot(Long.class, initial));
            else if (initial instanceof Boolean) slots.put(name, new Slot(Boolean.class, initial));
            else throw new IllegalArgumentException("sealed field must initialize int/bool only");
        }
        values = slots.values().stream().map(Slot::initial).toArray();
    }

    @Override
    public ActorRuntime.ActorId ownerActorId() {
        return actorId;
    }

    private Object requireActorTurn() {
        if (ActorRuntime.currentActorRuntime() != runtime
                || !ActorRuntime.currentActorId().filter(actorId::equals).isPresent()
                || ActorRuntime.currentActorExecutionDomain() != executionDomain) {
            throw new SecurityException("sealed actor state accessed outside owner execution");
        }
        return ActorRuntime.currentMutexExecutionSegment();
    }

    /**
     * A callback-scope, non-transferable witness. Even if host code stores
     * the view, its accessors fail after return, after retirement, from a
     * different actor, and after a carrier/turn change. A view is NOT a
     * user-visible state handle.
     */
    final class LoanView {
        private final ActorStateLoans.ExclusiveLoan ticket;
        private final Object executionSegment;
        private final long issuedGeneration;
        private final boolean writable;
        private boolean released;

        private LoanView(ActorStateLoans.ExclusiveLoan ticket, Object executionSegment,
                         long issuedGeneration, boolean writable) {
            this.ticket = ticket;
            this.executionSegment = executionSegment;
            this.issuedGeneration = issuedGeneration;
            this.writable = writable;
        }

        private int index(String field, Class<?> requiredType, boolean mutation) {
            Objects.requireNonNull(field, "sealed field");
            synchronized (lock) {
                if (released || retired || reclaimed || generation != issuedGeneration
                        || !ticket.heldBy(executionSegment)
                        || requireActorTurn() != executionSegment) {
                    throw new IllegalStateException("stale or unauthenticated sealed state loan");
                }
                if (mutation && !writable) {
                    throw new SecurityException("read-only actor state loan cannot mutate");
                }
                int i = 0;
                for (Map.Entry<String, Slot> entry : slots.entrySet()) {
                    if (entry.getKey().equals(field)) {
                        if (entry.getValue().type() != requiredType) {
                            throw new IllegalArgumentException("sealed field type mismatch");
                        }
                        return i;
                    }
                    i++;
                }
                throw new IllegalArgumentException("unknown sealed state field");
            }
        }

        long readInt(String field) {
            synchronized (lock) {
                return (Long) values[index(field, Long.class, false)];
            }
        }

        boolean readBool(String field) {
            synchronized (lock) {
                return (Boolean) values[index(field, Boolean.class, false)];
            }
        }

        void writeInt(String field, long value) {
            synchronized (lock) {
                values[index(field, Long.class, true)] = value;
            }
        }

        void writeBool(String field, boolean value) {
            synchronized (lock) {
                values[index(field, Boolean.class, true)] = value;
            }
        }
    }

    <T> T read(Function<LoanView, T> block) {
        return withLoan(false, block);
    }

    <T> T write(Function<LoanView, T> block) {
        return withLoan(true, block);
    }

    private <T> T withLoan(boolean mutable, Function<LoanView, T> block) {
        Objects.requireNonNull(block, "state loan callback");
        Object segment = requireActorTurn();
        ActorStateLoans.ExclusiveLoan ticket;
        long currentGeneration;
        synchronized (lock) {
            if (retired || reclaimed) {
                throw new IllegalStateException("sealed actor state retired");
            }
            currentGeneration = generation;
            // A critical block must never queue or suspend to acquire a loan.
            // ActorStateLoans(maxPending=0) returns an immediate grant/failure.
            OresFuture<ActorStateLoans.ExclusiveLoan> candidate =
                    loans.acquireAsync(segment);
            if (!candidate.isDone() || candidate.isCompletedExceptionally()) {
                throw new IllegalStateException("critical state loan unavailable or reentrant");
            }
            ticket = candidate.join();
        }
        LoanView view = new LoanView(ticket, segment, currentGeneration, mutable);
        try {
            T result = block.apply(view);
            if (result instanceof OresFuture<?>) {
                throw new IllegalStateException("critical state loan cannot return an async future");
            }
            return result;
        } finally {
            synchronized (lock) {
                view.released = true;
            }
            try {
                ticket.close();
            } finally {
                synchronized (lock) {
                    reclaimIfQuiescent();
                }
            }
        }
    }

    /**
     * First phase of STOP: revoke new admissions and all existing views, but
     * retain storage even when no loan is active. Source continuations, actor
     * children and cleanup Tasks may still be physically running.
     */
    void sealOnStop() {
        synchronized (lock) {
            if (retired) return;
            retired = true;
            if (generation != Long.MAX_VALUE) generation++;
            loans.close();
        }
    }

    /**
     * Called only after ActorRuntime has physically quiesced source work.
     * This second phase can release scalars once the last lexical loan exits.
     */
    @Override
    public void close() {
        synchronized (lock) {
            sealOnStop();
            physicalTasksQuiesced = true;
            reclaimIfQuiescent();
        }
    }

    private void reclaimIfQuiescent() {
        // STOP and zero live loans are NOT sufficient. Physical task cleanup
        // must also be complete before this storage is retired.
        if (retired && physicalTasksQuiesced && !reclaimed
                && loans.activeCount() == 0) {
            java.util.Arrays.fill(values, null);
            slots.clear();
            reclaimed = true;
        }
    }

    boolean isReclaimed() {
        synchronized (lock) {
            return reclaimed;
        }
    }

    int activeLoanCount() {
        return loans.activeCount();
    }
}
