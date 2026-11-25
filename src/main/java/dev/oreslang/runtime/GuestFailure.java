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
            CodeImageIdentity codeImageIdentity,
            long sourceToken) {
        Provenance {
            Objects.requireNonNull(executionId, "executionId");
            Objects.requireNonNull(allocationDomain, "allocationDomain");
            if (parentExecution < 0 || continuation < 0) {
                throw new IllegalArgumentException(
                        "logical ancestry handles cannot be negative");
            }
            Objects.requireNonNull(codeImageIdentity, "codeImageIdentity");
            if (!codeImageIdentity.runtimeAuthenticated()) {
                throw new SecurityException("guest failure requires a runtime-issued code image");
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
    private boolean dispatching;

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
            CodeImageIdentity codeImageIdentity,
            long sourceToken) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(error, "error");
        Objects.requireNonNull(executionContext, "executionContext");
        Objects.requireNonNull(codeImageIdentity, "codeImageIdentity");
        if (!codeImageIdentity.isActive()) {
            throw new IllegalStateException("cannot mint guest failure for retired code image");
        }
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
                        codeImageIdentity,
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
            long continuation,
            CodeImageIdentity codeImageIdentity) {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(allocationDomain, "allocationDomain");
        Objects.requireNonNull(codeImageIdentity, "codeImageIdentity");
        return provenance.executionId().equals(executionId)
                && provenance.allocationDomain().equals(allocationDomain)
                && provenance.parentExecution() == parentExecution
                && provenance.continuation() == continuation
                && provenance.codeImageIdentity().sameActiveImage(codeImageIdentity);
    }

    boolean codeImageActive() {
        return provenance.codeImageIdentity().isActive();
    }

    synchronized void requireDispatchable() {
        if (lifecycle != Lifecycle.ACTIVE) {
            throw new IllegalStateException(
                    "completed guest failure cannot be dispatched again: " + lifecycle);
        }
        if (dispatching) {
            throw new IllegalStateException(
                    "guest failure is already in an active recovery dispatch");
        }
    }

    synchronized boolean enterRecoveryChain(RecoveryHandlerChain<?> chain) {
        Objects.requireNonNull(chain, "chain");
        if (lifecycle != Lifecycle.ACTIVE) {
            throw new IllegalStateException(
                    "completed guest failure cannot be dispatched again: " + lifecycle);
        }
        if (dispatching) {
            throw new IllegalStateException(
                    "guest failure is already in an active recovery dispatch");
        }
        if (!visitedChains.add(chain)) return false;
        if (visitedChains.size() > MAX_RECOVERY_CHAIN_VISITS) {
            lifecycle = Lifecycle.TERMINATED;
            visitedChains.clear();
            throw new IllegalStateException(
                    "recovery chain visit quota exceeded");
        }
        dispatching = true;
        return true;
    }

    synchronized void leaveRecoveryChain() {
        if (lifecycle != Lifecycle.ACTIVE || !dispatching) {
            throw new IllegalStateException(
                    "guest failure has no active recoverable dispatch");
        }
        dispatching = false;
    }

    synchronized void markRecovered() {
        complete(Lifecycle.RECOVERED);
    }

    synchronized void markTerminated() {
        complete(Lifecycle.TERMINATED);
    }

    synchronized void terminateIfActive() {
        if (lifecycle != Lifecycle.ACTIVE) return;
        lifecycle = Lifecycle.TERMINATED;
        dispatching = false;
        visitedChains.clear();
    }

    private void complete(Lifecycle terminal) {
        if (lifecycle != Lifecycle.ACTIVE) {
            throw new IllegalStateException(
                    "guest failure already completed: " + lifecycle);
        }
        lifecycle = terminal;
        dispatching = false;
        visitedChains.clear();
    }
}
