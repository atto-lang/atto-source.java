package dev.oreslang.nodes;

import dev.oreslang.runtime.GeneratorRuntime;
import org.graalvm.polyglot.Value;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;

/**
 * Scoped, one-use Java host handoff for a typechecked source generator.
 *
 * The host receives a certified session only AFTER Value.execute has returned.
 * Opening the session never starts a source task or creates a guest carrier.
 * Each subsequent pull is separately admitted through the context's root
 * scheduler/CONTROL pump and checked against a physical DONE certificate.
 */
public final class OresHostGeneratorBridge {
    private static final ThreadLocal<ArrayDeque<Slot>> CAPTURES = new ThreadLocal<>();

    private static final class Slot {
        private enum State { OPEN, CLAIMED, PUBLISHED, DELIVERED, CLOSED }

        final Thread thread = Thread.currentThread();
        final String nonce = UUID.randomUUID().toString();
        final String function;
        State state = State.OPEN;
        Object context;
        Object root;
        GeneratorRuntime.HostPullSession session;

        Slot(String function) { this.function = function; }

        void checkThread() {
            if (Thread.currentThread() != thread) {
                throw new SecurityException("generator host handoff belongs to another thread");
            }
        }

        void claim(String function, String nonce, Object context, Object root) {
            checkThread();
            if (state != State.OPEN || !this.function.equals(function)
                    || !this.nonce.equals(nonce)) {
                throw new SecurityException("invalid or replayed generator host ticket");
            }
            this.context = Objects.requireNonNull(context, "context");
            this.root = Objects.requireNonNull(root, "root");
            state = State.CLAIMED;
        }

        void publish(String function, String nonce, Object context, Object root,
                     GeneratorRuntime.HostPullSession handle) {
            checkThread();
            if (state != State.CLAIMED || !this.function.equals(function)
                    || !this.nonce.equals(nonce) || this.context != context
                    || this.root != root) {
                throw new SecurityException("generator session belongs to a different invocation");
            }
            session = Objects.requireNonNull(handle, "generator session");
            state = State.PUBLISHED;
        }

        GeneratorRuntime.HostPullSession deliver() {
            checkThread();
            if (state != State.PUBLISHED || session == null) {
                throw new IllegalStateException("generator host result not published");
            }
            var result = session;
            session = null;
            state = State.DELIVERED;
            return result;
        }

        void close() {
            checkThread();
            if (session != null) session.close();
            session = null;
            context = null;
            root = null;
            state = State.CLOSED;
        }
    }

    private OresHostGeneratorBridge() { }

    public static GeneratorRuntime.HostPullSession open(
            Value parsedSource, String function) {
        return openAfterHostExit(parsedSource, function, () -> { });
    }

    /** Deterministic test hook: runs after host Value.execute returns. */
    static GeneratorRuntime.HostPullSession openAfterHostExit(
            Value parsedSource, String function, Runnable afterHostExit) {
        Objects.requireNonNull(parsedSource, "parsedSource");
        Objects.requireNonNull(afterHostExit, "afterHostExit");
        if (function == null || function.isBlank()) {
            throw new IllegalArgumentException("public generator function name required");
        }
        ArrayDeque<Slot> stack = CAPTURES.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            CAPTURES.set(stack);
        }
        if (stack.size() >= 32) {
            throw new IllegalStateException("generator host handoff nesting limit reached");
        }
        Slot slot = new Slot(function);
        stack.push(slot);
        try {
            // Do not inspect the returned Value. The Truffle root returns an
            // ignored scalar acknowledgment, never a raw guest mutable handle.
            parsedSource.execute(
                    OresEvalRootNode.INVOKE_PUBLIC_GENERATOR_BRIDGE_COMMAND,
                    function, slot.nonce);
            afterHostExit.run();
            return slot.deliver();
        } finally {
            Slot popped = stack.pop();
            slot.close();
            if (popped != slot) {
                popped.close();
                for (Slot remaining : stack) remaining.close();
                stack.clear();
                CAPTURES.remove();
                throw new IllegalStateException("generator host scope corruption");
            }
            if (stack.isEmpty()) CAPTURES.remove();
        }
    }

    static void requireActiveScope(String function, String nonce,
                                   Object context, Object root) {
        ArrayDeque<Slot> stack = CAPTURES.get();
        if (stack == null || stack.isEmpty()) {
            throw new SecurityException("generator bridge requires a scoped host invocation");
        }
        stack.peek().claim(function, nonce, context, root);
    }

    static void publish(String function, String nonce, Object context, Object root,
                        GeneratorRuntime.HostPullSession session) {
        ArrayDeque<Slot> stack = CAPTURES.get();
        if (stack == null || stack.isEmpty()) {
            session.close();
            throw new SecurityException("generator host receiver no longer exists");
        }
        stack.peek().publish(function, nonce, context, root, session);
    }
}
