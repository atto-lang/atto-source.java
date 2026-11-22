package dev.oreslang.runtime;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Bounded, stackless host prototype for compiler-owned recovery dispatch.
 *
 * <p>Handlers are stored as two compact parallel arrays (a SoA snapshot):
 * callable references and effect masks. This is a synchronous implementation
 * substrate; it does not enable guest-source recover syntax, physical Task
 * unwinding, or actor supervision.
 *
 * <p>Only a trusted VM/compiler path may classify a failure as THROW or RAISE.
 * Dispatch accepts only a compiler-authenticated {@link GuestFailure}; there
 * is no raw kind/Throwable admission path. The envelope carries immutable
 * logical execution/source provenance while the wrapped host Throwable remains
 * an untrusted prototype payload whose mutable graph is revalidated defensively.
 */
final class RecoveryHandlerChain<T> {
    public enum FailureKind { THROW, RAISE, PANIC, CANCELLATION, HOST_FATAL }

    public sealed interface Outcome<T> permits Recovered, Unhandled, HandlerFailure, TerminalCallbackFailure { }

    public record Recovered<T>(T value) implements Outcome<T> { }

    public record Unhandled<T>(GuestFailure pendingFailure) implements Outcome<T> {
        public Unhandled {
            Objects.requireNonNull(pendingFailure);
        }
        public FailureKind kind() { return pendingFailure.kind(); }
        public Throwable error() { return pendingFailure.error(); }
        public GuestFailure.Provenance provenance() { return pendingFailure.provenance(); }
    }

    /** A callback's host failure cannot silently replace an in-flight raise. */
    public record HandlerFailure<T>(GuestFailure pendingFailure,
                                    RuntimeException callbackError) implements Outcome<T> {
        public HandlerFailure {
            Objects.requireNonNull(pendingFailure);
            Objects.requireNonNull(callbackError);
        }
        public FailureKind pendingKind() { return pendingFailure.kind(); }
        public Throwable pendingError() { return pendingFailure.error(); }
        public GuestFailure.Provenance provenance() { return pendingFailure.provenance(); }
    }

    /**
     * A terminal host signal observed while running a handler is never a
     * recoverable handler error. Keep the in-flight guest failure available
     * for supervisor diagnostics while the host cancellation/fatal signal
     * takes precedence. Runtime Error subclasses still escape directly.
     */
    public record TerminalCallbackFailure<T>(GuestFailure pendingFailure,
                                             RuntimeException terminalError) implements Outcome<T> {
        public TerminalCallbackFailure {
            Objects.requireNonNull(pendingFailure);
            Objects.requireNonNull(terminalError);
        }
        public FailureKind pendingKind() { return pendingFailure.kind(); }
        public Throwable pendingError() { return pendingFailure.error(); }
        public GuestFailure.Provenance provenance() { return pendingFailure.provenance(); }
    }

    @FunctionalInterface
    public interface Handler<T> {
        void handle(Throwable error, Continuation<T> continuation);
    }

    public interface Continuation<T> {
        /** Delegates to an earlier eligible handler; terminal in guest lowering. */
        void next();

        /** Resolves a failed callable with a compatible result value. */
        void resolve(T value);
    }

    private static final int THROW = 1;
    private static final int RAISE = 2;
    private static final int BOTH = THROW | RAISE;
    public static final int MAX_HANDLERS = 1024;
    private static final int MAX_FAILURE_GRAPH_NODES = 64;
    private static final int MAX_NESTED_DISPATCHES = 64;

    /**
     * Temporary synchronous host recursion fence. The guest runtime must
     * eventually track authenticated logical Task/actor continuations, not
     * carrier-thread identity, because guest tasks may suspend and migrate.
     */
    private static final ThreadLocal<DispatchScope> CURRENT_DISPATCH = new ThreadLocal<>();

    private static final class DispatchScope {
        private final IdentityHashMap<RecoveryHandlerChain<?>, Boolean> active =
                new IdentityHashMap<>();
        private DispatchAbort aborted;
    }

    /**
     * Fail closed instead of re-entering a handler or consuming a newly raised
     * failure. The original nested failure is kept as the cause. It is an Error
     * so the ordinary RuntimeException callback catch cannot downgrade it.
     */
    private static final class DispatchAbort extends Error {
        private DispatchAbort(String reason, Throwable pending) {
            super(reason, pending, false, false);
        }
    }

    /**
     * The host Throwable graph is not an authenticated guest exception graph.
     * getCause() is virtual: an overriding foreign implementation can lie,
     * alternate between values, or throw. Reject such implementations rather
     * than trusting a sampled view to authorize recovery. Cache metadata per
     * class to avoid repeated reflection during dispatch.
     */
    private static final ClassValue<Boolean> STANDARD_CAUSE_ACCESSOR = new ClassValue<>() {
        @Override protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("getCause").getDeclaringClass() == Throwable.class;
            } catch (ReflectiveOperationException | SecurityException inaccessible) {
                return false;
            }
        }
    };

    private final Handler<T>[] handlers;
    private final byte[] effectMasks;
    private final ExecutionContext.ExecutionId ownerExecutionId;
    private final ExecutionContext.AllocationDomain ownerAllocationDomain;
    private final long ownerParentExecution;
    private final long ownerContinuation;
    private final CodeImageIdentity ownerCodeImage;

    private RecoveryHandlerChain(
            Handler<T>[] handlers,
            byte[] effectMasks,
            ExecutionContext.ExecutionId ownerExecutionId,
            ExecutionContext.AllocationDomain ownerAllocationDomain,
            long ownerParentExecution,
            long ownerContinuation,
            CodeImageIdentity ownerCodeImage) {
        this.handlers = handlers;
        this.effectMasks = effectMasks;
        this.ownerExecutionId = Objects.requireNonNull(ownerExecutionId, "ownerExecutionId");
        this.ownerAllocationDomain =
                Objects.requireNonNull(ownerAllocationDomain, "ownerAllocationDomain");
        this.ownerParentExecution = ownerParentExecution;
        this.ownerContinuation = ownerContinuation;
        this.ownerCodeImage = Objects.requireNonNull(ownerCodeImage, "ownerCodeImage");
        if (!ownerCodeImage.runtimeAuthenticated()) {
            throw new SecurityException("recovery chain requires a runtime-issued code image");
        }
    }

    public static <T> Builder<T> builder(
            ExecutionContext ownerExecution, CodeImageIdentity codeImageIdentity) {
        return new Builder<>(ownerExecution, codeImageIdentity);
    }

    public int size() { return handlers.length; }

    /**
     * Returns a resolved value or the original unhandled failure. Each handler
     * runs at most once in reverse registration order. No recursive delegation
     * or Java stack growth is used. A distinct one-shot continuation is minted
     * for each visited handler, invalidated even when its handler throws.
     */
    // Package-private: source execution enters only with a VM/compiler-minted
    // immutable GuestFailure envelope. There is intentionally no raw
    // (FailureKind, Throwable) dispatch overload.
    Outcome<T> dispatch(GuestFailure pendingFailure) {
        Objects.requireNonNull(pendingFailure, "pendingFailure");
        if (!pendingFailure.compilerAuthenticated()) {
            throw new SecurityException("recovery requires a compiler-authenticated guest failure");
        }
        pendingFailure.requireDispatchable();
        if (!pendingFailure.codeImageActive()) {
            pendingFailure.markTerminated();
            return new Unhandled<>(pendingFailure);
        }
        if (!pendingFailure.belongsTo(
                ownerExecutionId,
                ownerAllocationDomain,
                ownerParentExecution,
                ownerContinuation,
                ownerCodeImage)) {
            // A valid envelope is not portable across logical Task/actor
            // invocations or actor generations. Preserve it unchanged for
            // outward propagation rather than invoking a foreign callback.
            return new Unhandled<>(pendingFailure);
        }
        final FailureKind kind = pendingFailure.kind();
        final Throwable failure = pendingFailure.error();
        final int mask = switch (kind) {
            case THROW -> THROW;
            case RAISE -> RAISE;
            case PANIC, CANCELLATION, HOST_FATAL -> 0;
        };
        // GuestFailure issuance admits only THROW/RAISE. Keep this terminal
        // branch as defense in depth if the envelope contract evolves.
        if (mask == 0 || handlers.length == 0) {
            return new Unhandled<>(pendingFailure);
        }
        // Find the first eligible callback before doing a possibly expensive
        // cause-graph preflight; a type-mismatched recovery chain must not
        // traverse an untrusted host error graph unnecessarily.
        int firstEligible = handlers.length - 1;
        while (firstEligible >= 0 && (effectMasks[firstEligible] & mask) == 0) {
            firstEligible--;
        }
        if (firstEligible < 0) {
            return new Unhandled<>(pendingFailure);
        }
        // A failure may move outward to distinct lexical recovery chains, but
        // an exhausted/rejected chain must not be replayed for a second pass.
        if (!pendingFailure.enterRecoveryChain(this)) {
            return new Unhandled<>(pendingFailure);
        }

        DispatchScope scope = CURRENT_DISPATCH.get();
        if (scope == null) {
            scope = new DispatchScope();
            CURRENT_DISPATCH.set(scope);
        }
        if (scope.aborted != null) {
            pendingFailure.markTerminated();
            throw scope.aborted;
        }
        if (scope.active.containsKey(this) || scope.active.size() >= MAX_NESTED_DISPATCHES) {
            pendingFailure.markTerminated();
            scope.aborted = new DispatchAbort(
                    "reentrant or excessively nested recovery dispatch", failure);
            throw scope.aborted;
        }
        scope.active.put(this, Boolean.TRUE);
        try {
        // GuestFailure authenticates effect kind and logical provenance.
        // Its host-prototype Throwable payload is still mutable and untrusted,
        // so graph admission remains fail-closed defense in depth.
        if (untrustedOrTerminalGraph(failure)) {
            pendingFailure.leaveRecoveryChain();
            return new Unhandled<>(pendingFailure);
        }

        for (int i = firstEligible; i >= 0; i--) {
            if ((effectMasks[i] & mask) == 0) continue;
            // NEVER recycle this capability for the next handler: an escaped
            // reference must not acquire authority over another handler.
            Cursor<T> cursor = new Cursor<>();
            byte decision;
            T resolved;
            try {
                try {
                    handlers[i].handle(failure, cursor);
                } catch (RuntimeException callbackFailure) {
                    if (scope.aborted != null) throw scope.aborted;
                    // Callback-supplied cancellation, security denial and
                    // shutdown signals must not masquerade as ordinary
                    // handler errors. Retain both error identities.
                    if (untrustedOrTerminalGraph(callbackFailure)) {
                        pendingFailure.markTerminated();
                        return new TerminalCallbackFailure<>(pendingFailure, callbackFailure);
                    }
                    if (untrustedOrTerminalGraph(failure)) {
                        pendingFailure.leaveRecoveryChain();
                        return new Unhandled<>(pendingFailure);
                    }
                    pendingFailure.markTerminated();
                    return new HandlerFailure<>(pendingFailure, callbackFailure);
                } catch (Error callbackFatal) {
                    pendingFailure.markTerminated();
                    throw callbackFatal;
                }
                decision = cursor.state;
                resolved = cursor.value;
            } finally {
                cursor.invalidate();
            }
            // Even a handler that catches DispatchAbort and attempts to
            // resolve/delegate must not turn a nested failure into success.
            if (scope.aborted != null) {
                pendingFailure.markTerminated();
                throw scope.aborted;
            }
            // A callback can mutate an arbitrary host Throwable while
            // delegating or resolving. Revalidate before either action becomes
            // observable, including before invoking the next handler.
            if (untrustedOrTerminalGraph(failure)) {
                pendingFailure.leaveRecoveryChain();
                return new Unhandled<>(pendingFailure);
            }
            if (decision == Cursor.OPEN) {
                // Fail closed while preserving the pending original error as
                // diagnostic context, but do not leave it replayable.
                pendingFailure.markTerminated();
                return new HandlerFailure<>(pendingFailure,
                        new IllegalStateException("recovery handler must resolve or delegate"));
            }
            if (decision == Cursor.RESOLVED) {
                pendingFailure.markRecovered();
                return new Recovered<>(resolved);
            }
        }
        pendingFailure.leaveRecoveryChain();
        return new Unhandled<>(pendingFailure);
        } catch (Error hostFatal) {
            // Any host-fatal escape after admission permanently revokes the
            // pending recovery capability. This also clears an in-flight lease
            // if a hostile accessor throws Error during graph inspection.
            pendingFailure.terminateIfActive();
            throw hostFatal;
        } finally {
            scope.active.remove(this);
            if (scope.active.isEmpty()) {
                CURRENT_DISPATCH.remove();
            }
        }
    }

    /**
     * Conservative payload preflight for this host-only prototype. GuestFailure
     * now authenticates the effect kind and immutable logical provenance, but
     * the wrapped Java Throwable graph remains mutable/untrusted. Bounds prevent
     * hostile cyclic or huge cause graphs from becoming an unbounded preflight
     * denial-of-service.
     */
    private static boolean untrustedOrTerminalGraph(Throwable failure) {
        try {
            return containsTerminalControl(failure);
        } catch (RuntimeException malformedHostGraph) {
            // A foreign Throwable accessor is not an authenticated guest signal.
            return true;
        }
    }

    private static boolean isTerminal(Throwable failure) {
        return failure instanceof Error
                // Stack exhaustion is panic-class: no recover callback may consume it.
                || failure instanceof LogicalStackOverflowException
                || failure instanceof CancellationException
                || failure instanceof InterruptedException
                || failure instanceof RejectedExecutionException
                || failure instanceof TimeoutException
                || failure instanceof SecurityException;
    }

    private static boolean containsTerminalControl(Throwable failure) {
        // Most guest failures are ordinary leaves. Avoid allocating graph-walk
        // containers on this common exceptional path.
        if (isTerminal(failure)) return true;
        // Reject a foreign virtual accessor before calling it even once:
        // checking two successive reads is not sufficient against a stateful
        // override, and a later callback could exploit inconsistent reads.
        if (!STANDARD_CAUSE_ACCESSOR.get(failure.getClass())) return true;
        Throwable rootCause = failure.getCause();
        Throwable[] rootSuppressed = failure.getSuppressed();
        if (rootCause == null && rootSuppressed.length == 0) {
            return false;
        }
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current) || seen.size() > MAX_FAILURE_GRAPH_NODES) return true;
            if (isTerminal(current)) return true;
            if (!STANDARD_CAUSE_ACCESSOR.get(current.getClass())) return true;
            Throwable cause = current == failure ? rootCause : current.getCause();
            if (cause != null) {
                if (pending.size() + seen.size() >= MAX_FAILURE_GRAPH_NODES) return true;
                pending.addLast(cause);
            }
            Throwable[] suppressed = current == failure ? rootSuppressed : current.getSuppressed();
            // Bound breadth during insertion as well as traversal. A malicious
            // Throwable with thousands of suppressed failures may not allocate
            // an unbounded work queue on the actor recovery path.
            if (suppressed.length > MAX_FAILURE_GRAPH_NODES - seen.size() - pending.size()) {
                return true;
            }
            for (Throwable secondary : suppressed) pending.addLast(secondary);
        }
        return false;
    }

    private static final class Cursor<T> implements Continuation<T> {
        private static final byte OPEN = 0;
        private static final byte DELEGATED = 1;
        private static final byte RESOLVED = 2;
        private static final byte INVALID = 3;
        private final Thread ownerThread = Thread.currentThread();
        private byte state = OPEN;
        private T value;

        void invalidate() { state = INVALID; value = null; }

        private void checkOpen() {
            // Temporary synchronous host fence. The eventual guest continuation
            // must use authenticated *logical Task/actor* identity rather than
            // carrier-thread identity across await.
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException("recovery continuation used from another thread");
            }
            if (state != OPEN) {
                throw new IllegalStateException("recovery continuation already consumed");
            }
        }

        @Override public void next() { checkOpen(); state = DELEGATED; }

        @Override public void resolve(T resolved) {
            checkOpen();
            value = resolved;
            state = RESOLVED;
        }
    }

    public static final class Builder<T> {
        private final ExecutionContext.ExecutionId ownerExecutionId;
        private final ExecutionContext.AllocationDomain ownerAllocationDomain;
        private final long ownerParentExecution;
        private final long ownerContinuation;
        private final CodeImageIdentity ownerCodeImage;
        @SuppressWarnings("unchecked")
        private Handler<T>[] handlers = (Handler<T>[]) new Handler<?>[4];
        private byte[] effectMasks = new byte[4];
        private int size;

        private Builder(
                ExecutionContext ownerExecution,
                CodeImageIdentity codeImageIdentity) {
            Objects.requireNonNull(ownerExecution, "ownerExecution");
            this.ownerCodeImage = Objects.requireNonNull(codeImageIdentity, "codeImageIdentity");
            if (!ownerCodeImage.isActive()) {
                throw new IllegalStateException("cannot register recovery on retired code image");
            }
            this.ownerExecutionId = ownerExecution.id();
            this.ownerAllocationDomain = ownerExecution.allocationDomain();
            this.ownerParentExecution = ownerExecution.parentExecution();
            this.ownerContinuation = ownerExecution.continuation();
        }

        public Builder<T> register(Handler<T> handler) { return add(BOTH, handler); }
        public Builder<T> onThrow(Handler<T> handler) { return add(THROW, handler); }
        public Builder<T> onRaise(Handler<T> handler) { return add(RAISE, handler); }

        private Builder<T> add(int mask, Handler<T> handler) {
            Objects.requireNonNull(handler, "handler");
            if (size == MAX_HANDLERS) {
                throw new IllegalStateException("recovery handler quota exceeded");
            }
            if (size == handlers.length) {
                int newSize = Math.min(MAX_HANDLERS, size * 2);
                handlers = Arrays.copyOf(handlers, newSize);
                effectMasks = Arrays.copyOf(effectMasks, newSize);
            }
            handlers[size] = handler;
            effectMasks[size++] = (byte) mask;
            return this;
        }

        /** Immutable snapshot. The caller enforces ownership of captured data. */
        public RecoveryHandlerChain<T> freeze() {
            return new RecoveryHandlerChain<>(
                    Arrays.copyOf(handlers, size),
                    Arrays.copyOf(effectMasks, size),
                    ownerExecutionId,
                    ownerAllocationDomain,
                    ownerParentExecution,
                    ownerContinuation,
                    ownerCodeImage);
        }
    }
}
