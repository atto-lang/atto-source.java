package dev.oreslang.nodes;

import dev.oreslang.runtime.OresContext;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import dev.oreslang.runtime.OresFuture;
import org.graalvm.polyglot.Value;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;

/**
 * Host-only public async invocation handoff.
 *
 * A Truffle Value.asHostObject() call re-enters the guest context and can race
 * the just-started CONTROL task. This bridge captures the runtime Future while
 * the public guest call is already entered, and returns the actual Java Future
 * to the host after Value.execute() exits. No raw guest mutable state, guest
 * capability, or arbitrary host argument is transferred to an async task.
 *
 * The scope is strictly thread-local and nested calls cannot steal a parent
 * result. Only the dedicated internal control command can publish a result.
 */
public final class OresHostFutureBridge {
    private static final ThreadLocal<ArrayDeque<Slot>> CAPTURES =
            new ThreadLocal<>();
    // Bindings are bootstrap-only and never published as guest Values.
    // The caller may register only its own, already parsed source.
    private static final ThreadLocal<SourceBinding> SOURCE_BINDING =
            new ThreadLocal<>();

    private static final class SourceBinding {
        final String nonce = UUID.randomUUID().toString();
        OresContext owner;
        Object root;
        boolean claimed;

        void claim(String presentedNonce, OresContext context, Object sourceRoot) {
            if (!nonce.equals(presentedNonce) || claimed
                    || context == null || sourceRoot == null) {
                throw new SecurityException("invalid or reused host source binding");
            }
            claimed = true;
            owner = context;
            root = sourceRoot;
        }
    }

    /**
     * Explicitly mediated embedder handle. Unlike the legacy unbound bridge,
     * this reserves host entry BEFORE Value.execute. It fails closed if a
     * scheduler carrier already owns the physical guest-entry boundary.
     * The Value itself and its root authority are not exposed to guest code.
     */
    public static final class BoundSource implements AutoCloseable {
        private final Value parsedSource;
        private final OresContext context;
        private final AtomicBoolean closed = new AtomicBoolean();

        private BoundSource(Value parsedSource, OresContext context) {
            this.parsedSource = Objects.requireNonNull(parsedSource);
            this.context = Objects.requireNonNull(context);
        }

        private void requireOpen() {
            if (closed.get()) {
                throw new java.util.concurrent.RejectedExecutionException(
                        "bound host source is closed");
            }
        }

        /** Convert the Polyglot Value while the host-entry lease is held. */
        public <T> T executeAndConvert(Function<Value, T> converter, Object... command) {
            requireOpen();
            Objects.requireNonNull(converter, "converter");
            Objects.requireNonNull(command, "command");
            context.beginScopedHostFutureHandoff();
            try {
                requireOpen();
                return converter.apply(parsedSource.execute(command));
            } finally {
                context.endScopedHostFutureHandoff();
            }
        }

        public void executeCommand(Object... command) {
            executeAndConvert(ignored -> null, command);
        }

        public <T> OresFuture<T> invokePublicFuture(
                String function, Object... arguments) {
            requireOpen();
            context.beginScopedHostFutureHandoff();
            try {
                requireOpen();
                return OresHostFutureBridge.invokePublicFuture(
                        parsedSource, function, arguments);
            } finally {
                context.endScopedHostFutureHandoff();
            }
        }

        @Override public void close() {
            closed.set(true);
        }
    }

    /**
     * Bootstrap handshake. Call immediately after Context.parse and before
     * starting ANY guest carriers. This one-time registration itself enters
     * Polyglot and cannot retroactively protect an already-running context.
     * Do not use this to attach to an arbitrary live unmediated Value.
     */
    public static BoundSource bindBeforeGuestStart(Value parsedSource) {
        Objects.requireNonNull(parsedSource, "parsedSource");
        if (SOURCE_BINDING.get() != null) {
            throw new SecurityException("nested host source binding is forbidden");
        }
        SourceBinding binding = new SourceBinding();
        SOURCE_BINDING.set(binding);
        try {
            parsedSource.execute(
                    OresEvalRootNode.BIND_SCOPED_HOST_SOURCE_COMMAND,
                    binding.nonce);
            if (!binding.claimed || binding.owner == null || binding.root == null) {
                throw new SecurityException(
                        "host source failed to authenticate its context");
            }
            return new BoundSource(parsedSource, binding.owner);
        } finally {
            SOURCE_BINDING.remove();
        }
    }

    /** A one-use binding can be claimed only by the source's real root. */
    static void claimBoundSource(String nonce, OresContext context, Object root) {
        SourceBinding binding = SOURCE_BINDING.get();
        if (binding == null) {
            throw new SecurityException("unregistered host source binding command");
        }
        binding.claim(nonce, context, root);
    }

    /**
     * A one-use ticket. Claim happens before any guest dispatch; publication
     * is permitted only by the same physical context and source root that
     * claimed it. In particular, a reentrant command cannot claim this ticket
     * again while the first call is still starting its task.
     */
    static final class Slot {
        private enum State { OPEN, CLAIMED, PUBLISHED, CLOSED }

        private final String function;
        // Never supplied to the guest callable or exposed through a Value.
        private final String nonce = UUID.randomUUID().toString();
        private State state = State.OPEN;
        private Object ownerContext;
        private Object ownerRoot;
        private OresFuture<?> future;
        private boolean contextHandoffAcquired;

        Slot(String function) {
            this.function = Objects.requireNonNull(function, "function");
        }

        String nonce() {
            return nonce;
        }

        void claim(String function, String nonce, Object context, Object root) {
            if (!this.function.equals(function) || !this.nonce.equals(nonce)) {
                throw new SecurityException(
                        "host Future handoff requires a matching scoped invocation");
            }
            if (state != State.OPEN) {
                throw new SecurityException("host Future handoff ticket already consumed");
            }
            // Validate both owners before publishing either strong reference:
            // a rejected claim must not retain a partially staged context.
            Object validatedContext = Objects.requireNonNull(context, "context");
            Object validatedRoot = Objects.requireNonNull(root, "root");
            // This callback executes while the host's outer Value.execute
            // still owns guest entry. Protect the gap until its host finally
            // block releases the claim; reject a foreign guest reservation.
            if (validatedContext instanceof OresContext guestContext) {
                guestContext.beginScopedHostFutureHandoff();
                contextHandoffAcquired = true;
            }
            ownerContext = validatedContext;
            ownerRoot = validatedRoot;
            state = State.CLAIMED;
        }

        void publish(String function, String nonce, Object context, Object root,
                     OresFuture<?> result) {
            if (!this.function.equals(function) || !this.nonce.equals(nonce)
                    || state != State.CLAIMED
                    || ownerContext != context || ownerRoot != root) {
                throw new SecurityException(
                        "host Future result does not belong to claimed invocation");
            }
            future = Objects.requireNonNull(result, "result");
            state = State.PUBLISHED;
        }

        OresFuture<?> result() {
            if (state != State.PUBLISHED || future == null) {
                throw new IllegalStateException(
                        "Oreslang public async call did not publish its Future");
            }
            return future;
        }

        /**
         * The guest may have already published a producer when the outer
         * Polyglot Value.execute() subsequently fails. If the host did not
         * receive that Future, request source-task cancellation instead of
         * silently discarding its only receiver. Cancellation is a request,
         * not proof of physical cleanup completion.
         */
        void cancelUnreturnedOnHostFailure(Throwable hostFailure) {
            Objects.requireNonNull(hostFailure, "hostFailure");
            if (state != State.PUBLISHED || future == null) return;
            try {
                future.cancel(false);
            } catch (RuntimeException | Error cancellationFailure) {
                if (cancellationFailure != hostFailure) {
                    hostFailure.addSuppressed(cancellationFailure);
                }
            }
        }

        void close() {
            state = State.CLOSED;
            try {
                if (contextHandoffAcquired) {
                    contextHandoffAcquired = false;
                    ((OresContext) ownerContext).endScopedHostFutureHandoff();
                }
            } finally {
                ownerContext = null;
                ownerRoot = null;
                future = null;
            }
        }
    }

    private OresHostFutureBridge() { }

    @SuppressWarnings("unchecked")
    public static <T> OresFuture<T> invokePublicFuture(
            Value parsedSource, String function, Object... arguments) {
        Objects.requireNonNull(parsedSource, "parsedSource");
        if (function == null || function.isBlank()) {
            throw new IllegalArgumentException("public async function name is required");
        }
        Objects.requireNonNull(arguments, "arguments");

        ArrayDeque<Slot> slots = CAPTURES.get();
        if (slots == null) {
            slots = new ArrayDeque<>();
            CAPTURES.set(slots);
        }
        if (slots.size() >= 32) {
            throw new IllegalStateException("nested host Future handoff limit reached");
        }
        Slot slot = new Slot(function);
        slots.push(slot);
        try {
            Object[] command = new Object[arguments.length + 3];
            command[0] = OresEvalRootNode.INVOKE_PUBLIC_FUTURE_BRIDGE_COMMAND;
            command[1] = function;
            command[2] = slot.nonce();
            System.arraycopy(arguments, 0, command, 3, arguments.length);
            // Do not inspect the returned Value. Its primitive acknowledgment
            // is intentionally unused; doing asHostObject() here would enter
            // the guest context again after the scheduler has started.
            parsedSource.execute(command);
            return (OresFuture<T>) slot.result();
        } catch (RuntimeException | Error hostFailure) {
            // Even after successful ticket publication, Polyglot may reject
            // the outer host return. Never abandon that published producer.
            slot.cancelUnreturnedOnHostFailure(hostFailure);
            throw hostFailure;
        } finally {
            Slot popped = slots.pop();
            slot.close();
            if (popped != slot) {
                popped.close();
                for (Slot remaining : slots) remaining.close();
                slots.clear();
                CAPTURES.remove();
                throw new IllegalStateException("host Future handoff scope was corrupted");
            }
            if (slots.isEmpty()) CAPTURES.remove();
        }
    }

    /**
     * Reject forged bridged control commands before any asynchronous work is
     * started. A missing host receiver must not create an orphaned producer.
     */
    static void requireActiveScope(String function, String nonce,
                                   Object context, Object root) {
        ArrayDeque<Slot> slots = CAPTURES.get();
        if (slots == null || slots.isEmpty()) {
            throw new SecurityException(
                    "host Future handoff requires a matching scoped invocation");
        }
        slots.peek().claim(function, nonce, context, root);
    }

    /** Called only by the same root and context that claimed the ticket. */
    static boolean publish(String function, String nonce, Object context, Object root,
                           OresFuture<?> future) {
        ArrayDeque<Slot> slots = CAPTURES.get();
        if (slots == null || slots.isEmpty()) return false;
        slots.peek().publish(function, nonce, context, root, future);
        return true;
    }
}
