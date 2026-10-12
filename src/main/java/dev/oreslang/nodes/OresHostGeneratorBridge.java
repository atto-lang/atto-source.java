package dev.oreslang.nodes;

import dev.oreslang.runtime.HostGeneratorSession;
import org.graalvm.polyglot.Value;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Java-only scoped handoff for a typechecked, scheduler-backed source
 * generator. The opaque handle is published while the original Polyglot
 * Value.execute call is entered, and retrieved without a second Truffle
 * Value.asHostObject entry competing with scheduler CONTROL carriers.
 *
 * Host-controlled unresolved test dependencies remain in this scope; they
 * are never forwarded as unsafe async guest callable parameters.
 */
public final class OresHostGeneratorBridge {
    private static final ThreadLocal<ArrayDeque<Slot>> SLOTS = new ThreadLocal<>();

    private static final class Slot {
        private final String function;
        private final String nonce = UUID.randomUUID().toString();
        private final Map<String, ?> dependencies;
        private Object context;
        private Object root;
        private HostGeneratorSession session;
        private int state; // 0 open, 1 claimed, 2 published, 3 closed

        Slot(String function, Map<String, ?> dependencies) {
            this.function = function;
            this.dependencies = Map.copyOf(dependencies);
        }

        void claim(String function, String nonce, Object context, Object root) {
            if (state != 0 || !this.function.equals(function)
                    || !this.nonce.equals(nonce)) {
                throw new SecurityException("host generator admission scope is invalid or replayed");
            }
            this.context = Objects.requireNonNull(context, "context");
            this.root = Objects.requireNonNull(root, "root");
            state = 1;
        }

        void publish(String function, String nonce, Object context, Object root,
                     HostGeneratorSession result) {
            if (state != 1 || !this.function.equals(function)
                    || !this.nonce.equals(nonce)
                    || this.context != context || this.root != root) {
                throw new SecurityException("host generator publication lost its owner");
            }
            session = Objects.requireNonNull(result, "result");
            state = 2;
        }

        HostGeneratorSession consume() {
            if (state != 2 || session == null) {
                throw new IllegalStateException("host generator was not published");
            }
            return session;
        }

        void close() {
            state = 3;
            context = null;
            root = null;
            session = null;
        }
    }

    private OresHostGeneratorBridge() { }

    public static HostGeneratorSession open(
            Value parsedSource, String function, Map<String, ?> dependencies) {
        Objects.requireNonNull(parsedSource, "parsedSource");
        if (function == null || function.isBlank()) {
            throw new IllegalArgumentException("generator function name required");
        }
        Objects.requireNonNull(dependencies, "dependencies");
        ArrayDeque<Slot> stack = SLOTS.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            SLOTS.set(stack);
        }
        if (stack.size() >= 32) {
            throw new IllegalStateException("nested host generator bridge limit");
        }
        Slot slot = new Slot(function, dependencies);
        stack.push(slot);
        try {
            parsedSource.execute(
                    OresEvalRootNode.OPEN_PUBLIC_GENERATOR_BRIDGE_COMMAND,
                    function, slot.nonce);
            return slot.consume();
        } catch (RuntimeException | Error failure) {
            // The published session may own a producer turn even if the host
            // failed to receive it. Seal new admission; physical cleanup is
            // still owned by OresContextLifecycle, not this catch block.
            if (slot.session != null) slot.session.seal();
            throw failure;
        } finally {
            Slot popped = stack.pop();
            slot.close();
            if (popped != slot) {
                for (Slot remaining : stack) remaining.close();
                stack.clear();
                SLOTS.remove();
                throw new IllegalStateException("host generator capture stack corrupted");
            }
            if (stack.isEmpty()) SLOTS.remove();
        }
    }

    static void requireActiveScope(String function, String nonce,
                                   Object context, Object root) {
        ArrayDeque<Slot> stack = SLOTS.get();
        if (stack == null || stack.isEmpty()) {
            throw new SecurityException("host generator command has no scoped receiver");
        }
        stack.peek().claim(function, nonce, context, root);
    }

    static Map<String, ?> dependencies(String function, String nonce,
                                       Object context, Object root) {
        ArrayDeque<Slot> stack = SLOTS.get();
        if (stack == null || stack.isEmpty()) {
            throw new SecurityException("host generator dependencies have no receiver");
        }
        Slot slot = stack.peek();
        if (slot.state != 1 || !slot.function.equals(function)
                || !slot.nonce.equals(nonce)
                || slot.context != context || slot.root != root) {
            throw new SecurityException("host generator dependencies lost source scope");
        }
        return slot.dependencies;
    }

    static void publish(String function, String nonce, Object context, Object root,
                        HostGeneratorSession session) {
        ArrayDeque<Slot> stack = SLOTS.get();
        if (stack == null || stack.isEmpty()) {
            throw new SecurityException("host generator result has no receiver");
        }
        stack.peek().publish(function, nonce, context, root, session);
    }
}
