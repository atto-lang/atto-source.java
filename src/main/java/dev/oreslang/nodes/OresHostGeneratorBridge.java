package dev.oreslang.nodes;

import dev.oreslang.runtime.OresContext;
import org.graalvm.polyglot.Value;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed Java-only handoff for a restricted, compiler-lowered Oreslang generator.
 *
 * The Polyglot command returns a primitive acknowledgment, never a guest
 * Value wrapping a live generator. This is an explicitly EXPERIMENTAL bridge:
 * whole-context entry serialization has not been proven for all actors.
 */
public final class OresHostGeneratorBridge {
    private static final ThreadLocal<ArrayDeque<Slot>> SCOPES = new ThreadLocal<>();

    static final class Slot {
        private final String function;
        private final String nonce = UUID.randomUUID().toString();
        private Object context;
        private Object root;
        private boolean claimed;
        private boolean published;
        private OresContext.SourceHostGenerator generator;

        Slot(String function) { this.function = Objects.requireNonNull(function); }
        String nonce() { return nonce; }

        void claim(String requested, String ticket, Object context, Object root) {
            if (claimed || !function.equals(requested) || !nonce.equals(ticket)) {
                throw new SecurityException("host generator ticket is missing, stale, or reused");
            }
            this.context = Objects.requireNonNull(context);
            this.root = Objects.requireNonNull(root);
            claimed = true;
        }

        void publish(String requested, String ticket, Object context, Object root,
                     OresContext.SourceHostGenerator candidate) {
            if (!claimed || published || this.context != context || this.root != root
                    || !function.equals(requested) || !nonce.equals(ticket)) {
                throw new SecurityException("host generator result is not owned by this scope");
            }
            generator = Objects.requireNonNull(candidate);
            published = true;
        }

        OresContext.SourceHostGenerator take() {
            if (!published || generator == null) {
                throw new IllegalStateException("source generator handle was not published");
            }
            return generator;
        }

        void close() {
            context = null;
            root = null;
            generator = null;
        }

        void cancelOnFailedHostReturn() {
            if (generator != null) generator.close();
        }
    }

    private OresHostGeneratorBridge() { }

    /**
     * Experimental opt-in is required, but does not relax Truffle permissions.
     * No guest body is started by this invocation. All pulls use the original
     * scheduler root and its authenticated, one-use physical DONE certificate.
     */
    public static OresContext.SourceHostGenerator invokePublicGenerator(
            Value parsedSource, String function) {
        Objects.requireNonNull(parsedSource, "parsedSource");
        if (function == null || function.isBlank()) {
            throw new IllegalArgumentException("public generator function is required");
        }
        if (!Boolean.getBoolean("ores.host.generator.experimental")) {
            throw new SecurityException("host generator bridge is disabled by default");
        }
        ArrayDeque<Slot> stack = SCOPES.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            SCOPES.set(stack);
        }
        if (stack.size() >= 32) throw new IllegalStateException("nested generator scope limit");
        Slot slot = new Slot(function);
        stack.push(slot);
        try {
            // The returned Value is deliberately NOT inspected: no
            // Value.asHostObject() guest reentry after host turn dispatch.
            parsedSource.execute(OresEvalRootNode.INVOKE_PUBLIC_GENERATOR_BRIDGE_COMMAND,
                    function, slot.nonce());
            return slot.take();
        } catch (RuntimeException | Error failure) {
            slot.cancelOnFailedHostReturn();
            throw failure;
        } finally {
            Slot popped = stack.pop();
            slot.close();
            if (popped != slot) {
                stack.clear();
                SCOPES.remove();
                throw new IllegalStateException("host generator scope stack was corrupted");
            }
            if (stack.isEmpty()) SCOPES.remove();
        }
    }

    static void requireActiveScope(String function, String nonce,
                                   Object context, Object root) {
        ArrayDeque<Slot> stack = SCOPES.get();
        if (stack == null || stack.isEmpty()) {
            throw new SecurityException("host generator command requires an active host scope");
        }
        stack.peek().claim(function, nonce, context, root);
    }

    static void publish(String function, String nonce, Object context, Object root,
                        OresContext.SourceHostGenerator handle) {
        ArrayDeque<Slot> stack = SCOPES.get();
        if (stack == null || stack.isEmpty()) {
            handle.close();
            throw new SecurityException("missing generator host receiver");
        }
        stack.peek().publish(function, nonce, context, root, handle);
    }
}
