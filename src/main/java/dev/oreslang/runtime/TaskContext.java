package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Task-local context: immutable key/value bindings inherited by every task
 * started while they are in effect (Loom {@code ScopedValue}, Swift
 * {@code TaskLocal}). Source form:
 *
 * <pre>
 * task context(#trace_id, "abc") {
 *   ... task_context(#trace_id) ...   // Some("abc"), also inside child tasks
 * }
 * </pre>
 *
 * <p>A binding is a persistent linked node, so capturing the current context
 * for a new task is O(1) and children can never mutate a parent's view.
 * Values are immutable strings in v1, so context never becomes a way to
 * share mutable state around the ownership checker.</p>
 *
 * <p>The thread-local is only a per-turn cache. Each source task stores its
 * own context and re-installs it at the start of every scheduler turn, so it
 * survives await and carrier migration.</p>
 */
public final class TaskContext {
    /** Bound nesting depth; guards against runaway recursion building chains. */
    static final int MAX_DEPTH = 256;

    private static final ThreadLocal<TaskContext> CURRENT = new ThreadLocal<>();

    private final String key;
    private final String value;
    private final TaskContext parent;
    private final int depth;

    private TaskContext(String key, String value, TaskContext parent) {
        this.key = key;
        this.value = value;
        this.parent = parent;
        this.depth = parent == null ? 1 : parent.depth + 1;
    }

    /** The context in effect on this carrier turn, or null for none. */
    public static TaskContext current() {
        return CURRENT.get();
    }

    /** Install a task's context for one turn; returns the previous value to restore. */
    public static TaskContext install(TaskContext context) {
        TaskContext previous = CURRENT.get();
        if (context == null) CURRENT.remove();
        else CURRENT.set(context);
        return previous;
    }

    /** {@code task context(#key, value) { }} entry. */
    public static void enter(String key, String value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        TaskContext parent = CURRENT.get();
        if (parent != null && parent.depth >= MAX_DEPTH) {
            throw new IllegalStateException("task context nesting exceeds " + MAX_DEPTH);
        }
        CURRENT.set(new TaskContext(key, value, parent));
    }

    /** Block exit (via defer): pop exactly the innermost binding. */
    public static void exit() {
        TaskContext top = CURRENT.get();
        if (top == null) throw new IllegalStateException("task context exit without a binding");
        install(top.parent);
    }

    /** Innermost value for {@code key}, or null when unbound. */
    public static String lookup(String key) {
        for (TaskContext node = CURRENT.get(); node != null; node = node.parent) {
            if (node.key.equals(key)) return node.value;
        }
        return null;
    }
}
