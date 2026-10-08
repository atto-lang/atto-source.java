package dev.oreslang.runtime;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.Optional;

/**
 * Identity-bearing, actor-confined storage. A strong Ref owns reachability of
 * its cell; WeakRef never prolongs reachability. Neither capability is valid
 * outside the actor execution domain that allocated it.
 *
 * <p>These handles intentionally do not implement SharedState or Serializable.
 * They must not cross actor mailboxes, async task boundaries, native/host
 * transports, or shared-mutex publication.</p>
 */
public final class ActorLocalReferences {
    private ActorLocalReferences() { }

    /**
     * Buffered channels and select write registrations may outlive a mailbox turn.
     * Reject actor-local capabilities before retaining a payload, including
     * payloads hidden inside language records/options or host collections.
     * Unlike mailbox transport this permits ordinary local-only objects.
     */
    static void rejectBufferedChannelPayload(Object value) {
        if (value instanceof Ref<?> || value instanceof WeakRef<?>) {
            throw new SecurityException(
                    "actor-local Ref<T>/WeakRef<T> cannot enter a buffered channel or select case");
        }
        if (!(value instanceof OresMutex.SharedState)
                && !(value instanceof java.util.Map<?, ?>)
                && !(value instanceof Iterable<?>)
                && !value.getClass().isArray()) {
            return;
        }
        java.util.ArrayDeque<Object> pending = new java.util.ArrayDeque<>();
        java.util.IdentityHashMap<Object, Boolean> seen = new java.util.IdentityHashMap<>();
        pending.add(value);
        while (!pending.isEmpty()) {
            Object node = pending.removeLast();
            if (node instanceof Ref<?> || node instanceof WeakRef<?>) {
                throw new SecurityException(
                        "actor-local Ref<T>/WeakRef<T> cannot enter a buffered channel or select case");
            }
            if (seen.put(node, Boolean.TRUE) != null) continue;
            if (node instanceof OresMutex.SharedState state) {
                for (Object child : state.sharedStateChildren()) {
                    if (child != null) pending.add(child);
                }
            } else if (node instanceof java.util.Map<?, ?> map) {
                for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() != null) pending.add(entry.getKey());
                    if (entry.getValue() != null) pending.add(entry.getValue());
                }
            } else if (node instanceof Iterable<?> iterable) {
                for (Object child : iterable) {
                    if (child != null) pending.add(child);
                }
            } else if (node.getClass().isArray()) {
                for (int i = 0; i < java.lang.reflect.Array.getLength(node); i++) {
                    Object child = java.lang.reflect.Array.get(node, i);
                    if (child != null) pending.add(child);
                }
            }
        }
    }

    private static Object requireActorDomain() {
        Object domain = ActorRuntime.currentActorExecutionDomain();
        if (domain == null) {
            throw new IllegalStateException("Ref<T> and WeakRef<T> require an active actor turn");
        }
        return domain;
    }

    private static final class Cell<T> {
        private final Object ownerDomain;
        private T value;
        private boolean present;

        private Cell(Object ownerDomain, T initial) {
            this.ownerDomain = ownerDomain;
            this.value = initial;
            this.present = initial != null;
        }

        private void checkOwner() {
            if (requireActorDomain() != ownerDomain) {
                throw new SecurityException(
                        "actor-local Ref<T> or WeakRef<T> accessed outside its owning actor");
            }
        }
    }

    public static <T> Ref<T> create(T initial) {
        return new Ref<>(new Cell<>(requireActorDomain(),
                Objects.requireNonNull(initial, "Ref.new requires an owned value")));
    }

    /** Allocate a cell without a value; initialize it using set() inside the actor. */
    public static <T> Ref<T> empty() {
        return new Ref<>(new Cell<>(requireActorDomain(), null));
    }

    public static final class Ref<T> {
        private final Cell<T> cell;

        private Ref(Cell<T> cell) { this.cell = Objects.requireNonNull(cell); }

        public boolean isSet() {
            cell.checkOwner();
            return cell.present;
        }

        /** Consumes the cell's value, leaving an empty cell; it never duplicates ownership. */
        public Optional<T> take() {
            cell.checkOwner();
            if (!cell.present) return Optional.empty();
            T previous = cell.value;
            cell.value = null;
            cell.present = false;
            return Optional.of(previous);
        }

        /** Discards the prior value, moving a replacement into the cell. */
        public void set(T replacement) {
            cell.checkOwner();
            cell.value = Objects.requireNonNull(replacement, "Ref.set requires owned data");
            cell.present = true;
        }

        /**
         * Copies only known immutable scalar data. For arbitrary owned T, use
         * take() or an eventual lexical read-borrow operation instead.
         */
        public T getCopy() {
            cell.checkOwner();
            if (!cell.present) {
                throw new IllegalStateException("Ref<T> cell is empty");
            }
            if (!isScalar(cell.value)) {
                throw new IllegalArgumentException(
                        "Ref.get_copy only supports immutable scalar values; use take() for owned data");
            }
            return cell.value;
        }

        public WeakRef<T> downgrade() {
            cell.checkOwner();
            return new WeakRef<>(cell);
        }

        public boolean sameIdentity(Ref<?> other) {
            cell.checkOwner();
            Objects.requireNonNull(other, "other").cell.checkOwner();
            return cell == other.cell;
        }

        @Override public String toString() { return "Ref<actor-local>"; }
    }

    public static final class WeakRef<T> {
        private final WeakReference<Object> ownerDomain;
        private final WeakReference<Cell<T>> target;

        private WeakRef(Cell<T> cell) {
            this.ownerDomain = new WeakReference<>(cell.ownerDomain);
            this.target = new WeakReference<>(cell);
        }

        public Optional<Ref<T>> upgrade() {
            if (requireActorDomain() != ownerDomain.get()) {
                throw new SecurityException(
                        "actor-local WeakRef<T> accessed outside its owning actor");
            }
            Cell<T> cell = target.get();
            return cell == null ? Optional.empty() : Optional.of(new Ref<>(cell));
        }

        @Override public String toString() { return "WeakRef<actor-local>"; }
    }

    private static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Boolean
                || value instanceof Character || value instanceof Byte
                || value instanceof Short || value instanceof Integer
                || value instanceof Long || value instanceof Float
                || value instanceof Double || value instanceof java.math.BigInteger
                || value instanceof java.math.BigDecimal
                || value instanceof java.util.UUID || value instanceof Enum<?>;
    }
}
