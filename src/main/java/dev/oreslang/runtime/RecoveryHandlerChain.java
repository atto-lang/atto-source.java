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
 * Never construct an admitted kind from guest strings or an arbitrary host
 * Throwable. This helper does not mint classification authority: the source
 * interpreter still needs a sealed guest-effect carrier. Fatal/cancellation
 * indicators in bounded host cause/suppressed graphs bypass handlers even
 * when a caller accidentally supplies THROW or RAISE.
 */
public final class RecoveryHandlerChain<T> {
    public enum FailureKind { THROW, RAISE, PANIC, CANCELLATION, HOST_FATAL }

    public sealed interface Outcome<T> permits Recovered, Unhandled, HandlerFailure { }

    public record Recovered<T>(T value) implements Outcome<T> { }

    public record Unhandled<T>(FailureKind kind, Throwable error) implements Outcome<T> {
        public Unhandled {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(error);
        }
    }

    /** A callback's host failure cannot silently replace an in-flight raise. */
    public record HandlerFailure<T>(FailureKind pendingKind, Throwable pendingError,
                                    RuntimeException callbackError) implements Outcome<T> {
        public HandlerFailure {
            Objects.requireNonNull(pendingKind);
            Objects.requireNonNull(pendingError);
            Objects.requireNonNull(callbackError);
        }
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

    private final Handler<T>[] handlers;
    private final byte[] effectMasks;

    private RecoveryHandlerChain(Handler<T>[] handlers, byte[] effectMasks) {
        this.handlers = handlers;
        this.effectMasks = effectMasks;
    }

    public static <T> Builder<T> builder() { return new Builder<>(); }

    public int size() { return handlers.length; }

    /**
     * Returns a resolved value or the original unhandled failure. Each handler
     * runs at most once in reverse registration order. No recursive delegation
     * or Java stack growth is used. A distinct one-shot continuation is minted
     * for each visited handler, invalidated even when its handler throws.
     */
    // Package-private: source execution must enter via a future trusted,
    // compiler-owned guest-effect admission bridge, not a guest/FFI method.
    Outcome<T> dispatch(FailureKind kind, Throwable failure) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(failure, "failure");
        final int mask = switch (kind) {
            case THROW -> THROW;
            case RAISE -> RAISE;
            case PANIC, CANCELLATION, HOST_FATAL -> 0;
        };
        // Kind and underlying host cause must both be eligible: never allow a
        // mislabeled THROW/RAISE to downgrade terminal cancellation, security
        // denial, a native/VM fatal error or an excessively complex causal graph.
        if (mask == 0 || handlers.length == 0) {
            return new Unhandled<>(kind, failure);
        }
        // Find the first eligible callback before doing a possibly expensive
        // cause-graph preflight; a type-mismatched recovery chain must not
        // traverse an untrusted host error graph unnecessarily.
        int firstEligible = handlers.length - 1;
        while (firstEligible >= 0 && (effectMasks[firstEligible] & mask) == 0) {
            firstEligible--;
        }
        if (firstEligible < 0 || containsTerminalControl(failure)) {
            return new Unhandled<>(kind, failure);
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
                    // Do not downgrade or discard the pending signal. A higher
                    // layer must classify the callback error as a separate
                    // runtime/control failure and preserve both identities.
                    return new HandlerFailure<>(kind, failure, callbackFailure);
                }
                decision = cursor.state;
                resolved = cursor.value;
            } finally {
                cursor.invalidate();
            }
            if (decision == Cursor.OPEN) {
                // Fail closed while preserving the pending original error.
                return new HandlerFailure<>(kind, failure,
                        new IllegalStateException("recovery handler must resolve or delegate"));
            }
            if (decision == Cursor.RESOLVED) return new Recovered<>(resolved);
        }
        return new Unhandled<>(kind, failure);
    }

    /**
     * Conservative preflight for this host-only prototype. This is NOT a
     * substitute for compiler-minted GuestEffect provenance: unrecognized host
     * Throwable types must never be admitted as guest source errors later.
     * Bounds also prevent hostile cyclic or huge cause graphs from becoming
     * an unbounded preflight denial-of-service.
     */
    private static boolean isTerminal(Throwable failure) {
        return failure instanceof Error
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
        if (failure.getCause() == null && failure.getSuppressed().length == 0) {
            return false;
        }
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current) || seen.size() > MAX_FAILURE_GRAPH_NODES) return true;
            if (isTerminal(current)) return true;
            Throwable cause = current.getCause();
            if (cause != null) {
                if (pending.size() + seen.size() >= MAX_FAILURE_GRAPH_NODES) return true;
                pending.addLast(cause);
            }
            Throwable[] suppressed = current.getSuppressed();
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
        @SuppressWarnings("unchecked")
        private Handler<T>[] handlers = (Handler<T>[]) new Handler<?>[4];
        private byte[] effectMasks = new byte[4];
        private int size;

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
                    Arrays.copyOf(handlers, size), Arrays.copyOf(effectMasks, size));
        }
    }
}
