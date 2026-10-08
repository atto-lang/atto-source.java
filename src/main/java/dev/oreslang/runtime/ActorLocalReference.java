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
            checkedCell(currentDomain()).value =
                    Objects.requireNonNull(replacement, "Ref<T>.set does not accept null; use Option<T>");
        }

        /** Transfers the former owned value out, replacing it atomically within the actor turn. */
        public T swap(T replacement) {
            Cell<T> state = checkedCell(currentDomain());
            T previous = state.value;
            state.value = Objects.requireNonNull(replacement, "Ref<T>.swap does not accept null");
            return previous;
        }

        /** Copies only immutable scalar values; never silently aliases aggregate state. */
        public T getCopy() {
            T result = checkedCell(currentDomain()).value;
            if (!(result instanceof String || result instanceof Number || result instanceof Boolean
                    || result instanceof Character || result instanceof Enum<?>)) {
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
        private final Object owner;
        private final WeakReference<Cell<T>> pointer;

        private Weak(Cell<T> cell) {
            this.owner = cell.owner;
            this.pointer = new WeakReference<>(cell);
        }

        public Optional<Strong<T>> upgrade() {
            requireOwner(owner, currentDomain());
            Cell<T> state = pointer.get();
            return state == null ? Optional.empty() : Optional.of(new Strong<>(state));
        }

        public boolean isAlive() {
            requireOwner(owner, currentDomain());
            return pointer.get() != null;
        }

        @Override public String toString() { return "WeakRef<actor-local>"; }
    }

    public static <T> Strong<T> create(T initial) {
        return new Strong<>(new Cell<>(currentDomain(), initial));
    }

    // Package-private hooks exercise ownership without forging an actor context
    // or relaxing the public runtime's actor-turn admission check.
    static <T> Strong<T> createForDomain(Object domain, T initial) {
        return new Strong<>(new Cell<>(domain, initial));
    }

    static <T> Strong<T> cloneForDomain(Strong<T> handle, Object domain) {
        return new Strong<>(handle.checkedCell(domain));
    }

    static <T> Weak<T> downgradeForDomain(Strong<T> handle, Object domain) {
        return new Weak<>(handle.checkedCell(domain));
    }

    static <T> void setForDomain(Strong<T> handle, Object domain, T replacement) {
        handle.checkedCell(domain).value = Objects.requireNonNull(replacement);
    }

    static <T> T swapForDomain(Strong<T> handle, Object domain, T replacement) {
        Cell<T> cell = handle.checkedCell(domain);
        T previous = cell.value;
        cell.value = Objects.requireNonNull(replacement);
        return previous;
    }

    static <T> Optional<Strong<T>> upgradeForDomain(Weak<T> handle, Object domain) {
        requireOwner(handle.owner, domain);
        Cell<T> state = handle.pointer.get();
        return state == null ? Optional.empty() : Optional.of(new Strong<>(state));
    }
}
