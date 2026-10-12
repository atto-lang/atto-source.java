package dev.oreslang.runtime;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime backing for the source-level actor-local Ref<T> / WeakRef<T> types.
 *
 * Identity belongs to one actor execution domain. Strong handles own a cell;
 * weak handles refer only through java.lang.ref.WeakReference, so unreachable
 * cycles are reclaimable by the JVM GC. These are NOT mailbox capabilities
 * and must not cross actor or async-task transport boundaries.
 *
 * No method returns an unchecked mutable T alias. readCopy() only exposes a
 * JVM scalar; swap() transfers the stored value out of the cell. A future
 * scoped-borrow API must provide lexical leases before exposing non-Copy T.
 */
public final class ActorLocalReference {
    private ActorLocalReference() { }

    private static Object currentDomain() {
        Object domain = ActorRuntime.currentActorExecutionDomain();
        if (domain == null) {
            throw new IllegalStateException("Ref<T>/WeakRef<T> can only be used inside an actor turn");
        }
        return domain;
    }

    private static void requireOwner(Object owner, Object current) {
        if (owner != current) {
            throw new SecurityException(
                    "actor-local Ref<T>/WeakRef<T> cannot be accessed by another actor or execution domain");
        }
    }

    /**
     * Validate actor identity transitively before publishing a new cell value.
     * Guest class/Option/Result values expose their children through SharedState.
     * Cycles and repeated DAG nodes are valid locally, but foreign actor handles
     * must never be hidden behind those aggregate objects.
     */
    private static void requireLocalGraph(Object value, Object domain) {
        requireLocalGraph(value, domain, new java.util.IdentityHashMap<>(), 0, new int[1]);
    }

    private static void requireLocalGraph(
            Object value, Object domain,
            java.util.IdentityHashMap<Object, Boolean> visited,
            int depth, int[] nodes) {
        if (value == null) return;
        if (value instanceof Strong<?> strong) {
            strong.checkedCell(domain);
            return;
        }
        if (value instanceof Weak<?> weak) {
            weak.checkDomain(domain);
            return;
        }
        if (value instanceof String || value instanceof Number
                || value instanceof Boolean || value instanceof Character
                || value instanceof Enum<?>) return;
        if (depth > 96 || ++nodes[0] > 100_000) {
            throw new IllegalArgumentException("actor-local Ref value graph exceeds traversal limits");
        }
        if (visited.put(value, Boolean.TRUE) != null) return;
        if (value instanceof java.util.Map<?, ?> map) {
            for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                requireLocalGraph(entry.getKey(), domain, visited, depth + 1, nodes);
                requireLocalGraph(entry.getValue(), domain, visited, depth + 1, nodes);
            }
        } else if (value instanceof java.lang.Iterable<?> iterable) {
            for (Object child : iterable) {
                requireLocalGraph(child, domain, visited, depth + 1, nodes);
            }
        } else if (value instanceof OresMutex.SharedState state) {
            for (Object child : state.sharedStateChildren()) {
                requireLocalGraph(child, domain, visited, depth + 1, nodes);
            }
        } else if (value.getClass().isArray()) {
            int n = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < n; i++) {
                requireLocalGraph(java.lang.reflect.Array.get(value, i),
                        domain, visited, depth + 1, nodes);
            }
        }
    }

    private static final class Cell<T> {
        private final Object owner;
        private T value;

        private Cell(Object owner, T initial) {
            this.owner = Objects.requireNonNull(owner, "owner domain");
            this.value = Objects.requireNonNull(initial, "Ref<T> cannot contain null; use Option<T>");
        }
    }

    /** Strong local handle; use cloneHandle() for another owning handle. */
    public static final class Strong<T> {
        private final Object owner;
        private Cell<T> cell;

        private Strong(Cell<T> cell) {
            this.owner = cell.owner;
            this.cell = cell;
        }

        private Cell<T> checkedCell(Object domain) {
            requireOwner(owner, domain);
            if (cell == null) throw new IllegalStateException("Ref<T> handle has been dropped");
            return cell;
        }

        public Strong<T> cloneHandle() {
            return new Strong<>(checkedCell(currentDomain()));
        }

        public Weak<T> downgrade() {
            return new Weak<>(checkedCell(currentDomain()));
        }

        public void set(T replacement) {
            Object domain = currentDomain();
            Cell<T> state = checkedCell(domain);
            T next = Objects.requireNonNull(replacement, "Ref<T>.set does not accept null; use Option<T>");
            requireLocalGraph(next, domain);
            state.value = next;
        }

        /** Transfers the former owned value out, replacing it atomically within the actor turn. */
        public T swap(T replacement) {
            Object domain = currentDomain();
            Cell<T> state = checkedCell(domain);
            T next = Objects.requireNonNull(replacement, "Ref<T>.swap does not accept null");
            requireLocalGraph(next, domain);
            T previous = state.value;
            state.value = next;
            return previous;
        }

        /** Copies only immutable scalar values; never silently aliases aggregate state. */
        public T getCopy() {
            return scalarCopy(checkedCell(currentDomain()).value);
        }

        private static <V> V scalarCopy(V result) {
            if (!(result instanceof String || result instanceof Boolean
                    || result instanceof Character || result instanceof Byte
                    || result instanceof Short || result instanceof Integer
                    || result instanceof Long || result instanceof Float
                    || result instanceof Double || result instanceof java.math.BigInteger
                    || result instanceof java.math.BigDecimal)) {
                throw new IllegalArgumentException(
                        "Ref<T>.get_copy requires a Copy scalar; use swap for owned aggregate state");
            }
            return result;
        }

        public void drop() {
            checkedCell(currentDomain());
            cell = null;
        }

        public boolean isDropped() {
            requireOwner(owner, currentDomain());
            return cell == null;
        }

        @Override public String toString() { return "Ref<actor-local>"; }
    }

    /** Weak handle holds no strong pointer to the stored cell. */
    public static final class Weak<T> {
        // Weak handles must not keep an actor's execution domain (and its
        // retained runtime state) alive after all strong handles have vanished.
        private final WeakReference<Object> owner;
        private final WeakReference<Cell<T>> pointer;

        private Weak(Cell<T> cell) {
            this.owner = new WeakReference<>(cell.owner);
            this.pointer = new WeakReference<>(cell);
        }

        private void checkDomain(Object domain) {
            Object expected = owner.get();
            if (expected == null || expected != domain) {
                throw new SecurityException("WeakRef<T> actor execution domain expired or does not match");
            }
        }

        public Optional<Strong<T>> upgrade() {
            checkDomain(currentDomain());
            Cell<T> state = pointer.get();
            return state == null ? Optional.empty() : Optional.of(new Strong<>(state));
        }

        public boolean isAlive() {
            checkDomain(currentDomain());
            return pointer.get() != null;
        }

        @Override public String toString() { return "WeakRef<actor-local>"; }
    }

    public static <T> Strong<T> create(T initial) {
        Object domain = currentDomain();
        requireLocalGraph(initial, domain);
        return new Strong<>(new Cell<>(domain, initial));
    }

    // Package-private hooks exercise ownership without forging an actor context
    // or relaxing the public runtime's actor-turn admission check.
    static <T> Strong<T> createForDomain(Object domain, T initial) {
        requireLocalGraph(initial, domain);
        return new Strong<>(new Cell<>(domain, initial));
    }

    static <T> Strong<T> cloneForDomain(Strong<T> handle, Object domain) {
        return new Strong<>(handle.checkedCell(domain));
    }

    static <T> Weak<T> downgradeForDomain(Strong<T> handle, Object domain) {
        return new Weak<>(handle.checkedCell(domain));
    }

    static <T> void setForDomain(Strong<T> handle, Object domain, T replacement) {
        Cell<T> cell = handle.checkedCell(domain);
        T next = Objects.requireNonNull(replacement);
        requireLocalGraph(next, domain);
        cell.value = next;
    }

    static <T> T getCopyForDomain(Strong<T> handle, Object domain) {
        return Strong.scalarCopy(handle.checkedCell(domain).value);
    }

    static <T> T swapForDomain(Strong<T> handle, Object domain, T replacement) {
        Cell<T> cell = handle.checkedCell(domain);
        T next = Objects.requireNonNull(replacement);
        requireLocalGraph(next, domain);
        T previous = cell.value;
        cell.value = next;
        return previous;
    }

    static <T> Optional<Strong<T>> upgradeForDomain(Weak<T> handle, Object domain) {
        handle.checkDomain(domain);
        Cell<T> state = handle.pointer.get();
        return state == null ? Optional.empty() : Optional.of(new Strong<>(state));
    }
}
