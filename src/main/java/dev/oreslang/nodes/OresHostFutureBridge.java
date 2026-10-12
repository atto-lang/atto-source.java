package dev.oreslang.nodes;

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

    private static final class Slot {
        private final String function;
        // This nonce is never passed to the guest callable. It binds one
        // public control invocation to the host's current capture scope.
        private final String nonce = UUID.randomUUID().toString();
        private OresFuture<?> future;

        private Slot(String function) {
            this.function = function;
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
            command[2] = slot.nonce;
            System.arraycopy(arguments, 0, command, 3, arguments.length);
            // Do not inspect the returned Value. Its primitive acknowledgment
            // is intentionally unused; doing asHostObject() here would enter
            // the guest context again after the scheduler has started.
            parsedSource.execute(command);
            if (slot.future == null) {
                throw new IllegalStateException(
                        "Oreslang public async call did not publish its Future");
            }
            return (OresFuture<T>) slot.future;
        } finally {
            Slot popped = slots.pop();
            if (popped != slot) {
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
    static void requireActiveScope(String function, String nonce) {
        ArrayDeque<Slot> slots = CAPTURES.get();
        if (slots == null || slots.isEmpty()
                || !slots.peek().function.equals(function)
                || !slots.peek().nonce.equals(nonce)) {
            throw new SecurityException(
                    "host Future handoff requires a matching scoped invocation");
        }
        if (slots.peek().future != null) {
            throw new IllegalStateException("host Future already published");
        }
    }

    /** Called only by the root node's dedicated bridged invoke command. */
    static boolean publish(String function, String nonce, OresFuture<?> future) {
        Objects.requireNonNull(future, "future");
        ArrayDeque<Slot> slots = CAPTURES.get();
        if (slots == null || slots.isEmpty()) return false;
        Slot slot = slots.peek();
        if (!slot.function.equals(function) || !slot.nonce.equals(nonce)) {
            throw new IllegalStateException(
                    "host Future result does not belong to current public invocation");
        }
        if (slot.future != null) {
            throw new IllegalStateException("host Future result published twice");
        }
        slot.future = future;
        return true;
    }
}
