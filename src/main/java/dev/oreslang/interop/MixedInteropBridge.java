package dev.oreslang.interop;

import java.util.Map;

/** Host-side bridge used by generated Java facades in mixed source files. */
public final class MixedInteropBridge {
    @FunctionalInterface
    public interface Invoker {
        Object invoke(String functionName, Object[] arguments);
    }

    public interface Scope extends AutoCloseable {
        @Override void close();
    }

    private static final ThreadLocal<Map<String, Invoker>> CURRENT = new ThreadLocal<>();

    private MixedInteropBridge() { }

    /** Immutable linkage scope carried across logical scheduler turns. */
    public static Map<String, Invoker> capture() {
        Map<String, Invoker> current = CURRENT.get();
        return current == null ? Map.of() : current;
    }

    /** Closes a scope that was opened over no scope (the common case). */
    private static final Scope CLEAR_ON_CLOSE = CURRENT::remove;

    public static Scope open(Map<String, Invoker> invokers) {
        // Every scheduler turn opens the scope it captured, which is usually
        // empty. Map.copyOf returns an already-immutable map unchanged and
        // still rejects null keys/values, so the defensive copy stays exact.
        Map<String, Invoker> next = Map.copyOf(invokers);
        Map<String, Invoker> previous = CURRENT.get();
        if (previous == null) {
            // invoke() distinguishes "no scope" from "empty scope", so the
            // empty map must still be installed; only the closure is shared.
            CURRENT.set(next);
            return CLEAR_ON_CLOSE;
        }
        CURRENT.set(next);
        return () -> CURRENT.set(previous);
    }

    public static Object invoke(String unitId, String functionName, Object... arguments) {
        if (unitId == null || unitId.isBlank()) {
            throw new IllegalArgumentException("mixed bridge unit id cannot be blank");
        }
        if (functionName == null || functionName.isBlank()) {
            throw new IllegalArgumentException("mixed bridge function name cannot be blank");
        }
        Map<String, Invoker> scope = CURRENT.get();
        if (scope == null) {
            throw new IllegalStateException("Oreslang mixed bridge is not active on this thread");
        }
        Invoker invoker = scope.get(unitId);
        if (invoker == null) {
            throw new IllegalStateException("no linked Oreslang unit for mixed bridge path: " + unitId);
        }
        return invoker.invoke(functionName, arguments == null ? new Object[0] : arguments.clone());
    }
}
