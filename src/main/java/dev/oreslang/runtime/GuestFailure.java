package dev.oreslang.runtime;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * Compiler/runtime-issued exceptional effect envelope with immutable provenance
 * and runtime-owned one-unwind replay state.
 *
 * <p>This type is deliberately package-private and has no public constructor.
 * Guest source and FFI code must never be able to manufacture or relabel one.
 * The compiler/runtime bridge mints an envelope only after it has established
 * the exceptional effect kind and the logical execution that owns the unwind.
 *
 * <p>The wrapped {@link Throwable} is retained only as a host-prototype payload
 * identity. Its mutable Java cause graph is not provenance; recovery still
 * treats that graph as untrusted and revalidates it defensively. Source-level
 * lowering must eventually replace arbitrary host Throwable payloads with
 * immutable typed guest error data.</p>
 */
final class GuestFailure {
    static final int MAX_RECOVERY_CHAIN_VISITS = 1024;
    /**
     * Immutable logical provenance captured when the compiler/runtime mints the
     * failure. It is independent of the current carrier thread.
     */
    record Provenance(
            ExecutionContext.ExecutionId executionId,
            ExecutionContext.AllocationDomain allocationDomain,
            long parentExecution,
            long continuation,
            long sourceToken) {
        Provenance {
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(allocationDomain, "allocationDomain");
            if (parentExecution < 0 || continuation < 0) {
                throw new IllegalArgumentException(
                        "logical ancestry handles cannot be negative");
            }
            if (sourceToken <= 0) {
                throw new IllegalArgumentException(
                        "compiler source provenance token must be positive");
            }
        }
    }

    /*
     * Identity-only issuer seal. Because GuestFailure is package-private and its
     * constructor is private, ordinary guest/FFI Java code cannot synthesize
     * this authority or deserialize a lookalike into an admitted envelope.
     */
    private static final Object COMPILER_ISSUER = new Object();

    private final RecoveryHandlerChain.FailureKind kind;
    private final Throwable error;
    private final Provenance provenance;
    private final Object issuerSeal;

    /*
     * Runtime-owned one-unwind lifecycle state. Provenance and payload identity
     * are immutable; this ledger only prevents a valid failure capability from
     * being replayed through the same lexical recovery chain or reused after a
     * successful recovery.
     */
    private enum Lifecycle { ACTIVE, RECOVERED, TERMINATED }

    private final Set<RecoveryHandlerChain<?>> visitedChains =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private Lifecycle lifecycle = Lifecycle.ACTIVE;

    private GuestFailure(
            RecoveryHandlerChain.FailureKind kind,
            Throwable error,
            Provenance provenance,
            Object issuerSeal) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.error = Objects.requireNonNull(error, "error");
        this.provenance = Objects.requireNonNull(provenance, "provenance");
        this.issuerSeal = Objects.requireNonNull(issuerSeal, "issuerSeal");
    }

    /**
     * Trusted compiler/runtime bridge. This is package-private intentionally:
     * source/FFI surfaces must not expose it.
     */
    static GuestFailure issueFromCompiler(
            RecoveryHandlerChain.FailureKind kind,
            Throwable error,
            ExecutionContext executionContext,
            long sourceToken) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(executionContext, "executionContext");
        if (kind != RecoveryHandlerChain.FailureKind.THROW
                && kind != RecoveryHandlerChain.FailureKind.RAISE) {
            throw new IllegalArgumentException(
                    "local recovery envelope admits only authenticated THROW or RAISE");
        }
        return new GuestFailure(
                kind,
                error,
                new Provenance(
                        executionContext.id(),
                        executionContext.allocationDomain(),
                        executionContext.parentExecution(),
                        executionContext.continuation(),
                        sourceToken),
                COMPILER_ISSUER);
    }

    RecoveryHandlerChain.FailureKind kind() {
        return kind;
    }

    Throwable error() {
        return error;
    }

    Provenance provenance() {
        return provenance;
    }

    boolean compilerAuthenticated() {
        return issuerSeal == COMPILER_ISSUER;
    }

    boolean belongsTo(
            ExecutionContext.ExecutionId executionId,
            ExecutionContext.AllocationDomain allocationDomain,
            long parentExecution,
            long continuation) {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(allocationDomain, "allocationDomain");
        return provenance.executionId().equals(executionId)
                && provenance.allocationDomain().equals(allocationDomain)
                && provenance.parentExecution() == parentExecution
                && provenance.continuation() == continuation;
    }

    synchronized boolean enterRecoveryChain(RecoveryHandlerChain<?> chain) {
        Objects.requireNonNull(chain, "chain");
        if (lifecycle != Lifecycle.ACTIVE) {
            throw new IllegalStateException(
                    "completed guest failure cannot be dispatched again: " + lifecycle);
        }
        if (!visitedChains.add(chain)) return false;
        if (visitedChains.size() > MAX_RECOVERY_CHAIN_VISITS) {
            lifecycle = Lifecycle.TERMINATED;
            visitedChains.clear();
            throw new IllegalStateException(
                    "recovery chain visit quota exceeded");
        }
        return true;
    }

    synchronized void markRecovered() {
        complete(Lifecycle.RECOVERED);
    }

    synchronized void markTerminated() {
        complete(Lifecycle.TERMINATED);
    }

    private void complete(Lifecycle terminal) {
        if (lifecycle != Lifecycle.ACTIVE) {
            throw new IllegalStateException(
                    "guest failure already completed: " + lifecycle);
        }
        lifecycle = terminal;
        visitedChains.clear();
    }
}
