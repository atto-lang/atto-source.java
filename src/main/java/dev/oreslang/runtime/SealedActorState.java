package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Interpreter-backed actor-owned state, separate from class/instance fields.
 * The scheduler admits one actor turn; nested synchronous callbacks on that
 * turn obtain distinct ephemeral loans, with the outer loan fenced while the
 * nested loan is live. No JVM monitor is held while guest code executes.
 *
 * This is a synchronous typed-scalar milestone. Async source handle_state,
 * dynamic maps, FFI, and yielding live state loans must fail closed.
 */
public final class SealedActorState {
    private static final int MAX_FIELDS = 256;
    private static final int MAX_STRING_BYTES = 64 * 1024;
    private static final int MAX_TOTAL_STRING_BYTES = 1024 * 1024;
    private static final int MAX_LIST_ELEMENTS = 4096;
    private static final int MAX_TOTAL_LIST_ELEMENTS = 16384;

    private final ActorRuntime.ActorId owner;
    private final ActorRuntime.ActorMemorySlice memorySlice;
    private final ArrayList<ActorRuntime.MemoryReservation> reservations = new ArrayList<>();
    private long reservedPayloadBytes;
    private final LinkedHashMap<String, String> schema;
    private final LinkedHashMap<String, Object> values;
    private Access current;
    private long serial;
    private int retainedStringBytes;
    private int retainedListElements;
    private boolean retired;

    public SealedActorState(ActorRuntime.ActorId owner,
                            Map<String, String> schema,
                            Map<String, Object> defaults) {
        this.owner = Objects.requireNonNull(owner, "actor identity");
        this.memorySlice = ActorRuntime.currentActorMemorySlice().orElseThrow(
                () -> new SecurityException(
                        "actor state may only be initialized inside an admitted actor turn"));
        if (!owner.equals(memorySlice.owner())) {
            throw new SecurityException("cannot initialize a foreign actor state domain");
        }
        this.schema = new LinkedHashMap<>(Objects.requireNonNull(schema, "schema"));
        if (this.schema.isEmpty() || this.schema.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("actor state requires 1..256 typed fields");
        }
        this.values = new LinkedHashMap<>();
        for (var item : this.schema.entrySet()) {
            switch (item.getValue()) {
                case "int", "bool", "string", "String", "List[int]" -> { }
                default -> throw new UnsupportedOperationException(
                        "runtime actor state type is not yet lowered: " + item.getValue());
            }
            values.put(item.getKey(), Uninitialized.INSTANCE);
        }
        for (var entry : Objects.requireNonNull(defaults, "defaults").entrySet()) {
            validateValue(entry.getKey(), entry.getValue());
            Object detached = ownedStateValue(entry.getKey(), entry.getValue());
            int size = stringBytes(detached);
            int listSize = listElements(detached);
            if (size > MAX_TOTAL_STRING_BYTES - retainedStringBytes
                    || listSize > MAX_TOTAL_LIST_ELEMENTS - retainedListElements) {
                throw new IllegalArgumentException("actor state aggregate budget exceeded");
            }
            retainedStringBytes += size;
            retainedListElements += listSize;
            values.put(entry.getKey(), detached);
        }
        // Reserve exactly the constructor's current persistent payload.
        // Later writes reserve increases before publication. This accounts
        // for state quota without pretending Java objects have a native heap.
        reservePayloadGrowth(statePayloadBytes());
    }

    private enum Uninitialized { INSTANCE }

    private void requireOwner() {
        if (retired) throw new IllegalStateException("actor state retired");
        if (!owner.equals(ActorRuntime.currentActorId().orElse(null))) {
            throw new SecurityException("actor state requires the owning actor's admitted turn");
        }
    }

    private static int stringBytes(Object value) {
        if (!(value instanceof String text)) return 0;
        if (text.length() > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("actor state string exceeds per-field quota");
        }
        int bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("actor state UTF-8 bytes exceed per-field quota");
        }
        return bytes;
    }

    private void validateValue(String name, Object value) {
        String expected = schema.get(name);
        if (expected == null) throw new IllegalArgumentException("unknown actor state key " + name);
        boolean valid = switch (expected) {
            case "int" -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long;
            case "bool" -> value instanceof Boolean;
            case "string", "String" -> value instanceof String;
            case "List[int]" -> verifiedIntList(value) != null;
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("actor state key '" + name
                    + "' expects " + expected + ", got "
                    + (value == null ? "null" : value.getClass().getSimpleName()));
        }
    }

    /** Accepted only for compiler-generated List[int] storage/results.
     * Host-controlled List subclasses are rejected before any user-defined
     * iterator/getter could run during the copy. This is intentionally a
     * restrictive implementation until provenance-aware serializers exist. */
    private static List<Long> verifiedIntList(Object value) {
        if (!(value instanceof ArrayList<?> list)
                || value.getClass() != ArrayList.class) {
            throw new IllegalArgumentException(
                    "List[int] state snapshots require a compiler-owned ArrayList");
        }
        if (list.size() > MAX_LIST_ELEMENTS) {
            throw new IllegalArgumentException("actor state List[int] exceeds element quota");
        }
        ArrayList<Long> detached = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof Byte || item instanceof Short
                    || item instanceof Integer || item instanceof Long)) {
                throw new IllegalArgumentException(
                        "actor state List[int] contains non-integer or aliased element");
            }
            detached.add(((Number) item).longValue());
        }
        return detached;
    }

    private static int listElements(Object value) {
        return value instanceof List<?> list ? list.size() : 0;
    }

    /** Stored state contains immutable flat integer elements, no host refs. */
    private Object ownedStateValue(String name, Object value) {
        return schema.get(name).equals("List[int]")
                ? List.copyOf(verifiedIntList(value)) : value;
    }

    /** Persistent payload accounting; excludes transient detached results.
     * Successful shrinks deliberately keep the high-water reservation until
     * actor retirement. Failed callbacks refund all newly reserved bytes. */
    private long statePayloadBytes() {
        return Math.addExact((long) retainedStringBytes,
                Math.multiplyExact((long) retainedListElements, Long.BYTES));
    }

    private void reservePayloadGrowth(long requiredBytes) {
        if (requiredBytes <= reservedPayloadBytes) return;
        ActorRuntime.MemoryReservation acquired =
                memorySlice.reserveHeap(requiredBytes - reservedPayloadBytes);
        reservations.add(acquired);
        reservedPayloadBytes = requiredBytes;
    }

    public final class Access {
        private final long token;
        private final boolean writable;
        private boolean live = true;
        private Access(long token, boolean writable) {
            this.token = token;
            this.writable = writable;
        }
        private void check() {
            requireOwner();
            if (!live || current != this || token <= 0) {
                throw new IllegalStateException("expired or fenced actor state loan");
            }
        }
        public Object read(String name) {
            check();
            if (!values.containsKey(name)) throw new IllegalArgumentException("unknown actor state key " + name);
            Object value = values.get(name);
            if (value == Uninitialized.INSTANCE) {
                throw new IllegalStateException("actor state key '" + name + "' is uninitialized");
            }
            // Never expose the map's stored List identity, even to a
            // short-lived read loan. Each projection has independent storage.
            return value instanceof List<?> list
                    ? new ArrayList<>(list) : value;
        }
        public void write(String name, Object value) {
            check();
            if (!writable) throw new SecurityException("critical read cannot write actor state");
            validateValue(name, value);
            // Prepare and validate before publishing any part of the write.
            Object detached = ownedStateValue(name, value);
            int previousBytes = stringBytes(values.get(name));
            int nextBytes = stringBytes(detached);
            int previousElements = listElements(values.get(name));
            int nextElements = listElements(detached);
            if (nextBytes > MAX_TOTAL_STRING_BYTES
                    - (retainedStringBytes - previousBytes)
                    || nextElements > MAX_TOTAL_LIST_ELEMENTS
                    - (retainedListElements - previousElements)) {
                throw new IllegalArgumentException("actor state aggregate budget exceeded");
            }
            int updatedStringBytes = retainedStringBytes - previousBytes + nextBytes;
            int updatedListElements = retainedListElements - previousElements + nextElements;
            long requiredBytes = Math.addExact((long) updatedStringBytes,
                    Math.multiplyExact((long) updatedListElements, Long.BYTES));
            // Reserve before publishing. A heap-limit rejection leaves the
            // stored value and its accounting unchanged.
            reservePayloadGrowth(requiredBytes);
            values.put(name, detached);
            retainedStringBytes = updatedStringBytes;
            retainedListElements = updatedListElements;
        }
    }

    public <T> T critical(Function<Access, T> callback) {
        return critical(true, callback); // trusted Java-side internal callers
    }

    /** Guest source must supply its verified read/write mode. */
    public <T> T critical(boolean writable, Function<Access, T> callback) {
        Objects.requireNonNull(callback, "critical state callback");
        requireOwner();
        if (serial == Long.MAX_VALUE) throw new IllegalStateException("actor loan serial exhausted");
        Access previous = current;
        // A read grant must never be upgraded while it is live, even through
        // indirect calls that evade source-level interprocedural inference.
        if (previous != null && !previous.writable && writable) {
            throw new SecurityException("cannot upgrade a live critical read loan to write");
        }
        Access access = new Access(++serial, writable);
        // All stored values are immutable scalars or flat immutable integer
        // lists. A shallow map snapshot is consequently sufficient to restore
        // the complete actor state on a synchronous failure. Never publish a
        // partially applied critical write after an exception or result denial.
        LinkedHashMap<String, Object> before = writable
                ? new LinkedHashMap<>(values) : null;
        int priorStringBytes = retainedStringBytes;
        int priorListElements = retainedListElements;
        long priorReservedBytes = reservedPayloadBytes;
        int priorReservations = reservations.size();
        current = access;
        try {
            T result = callback.apply(access);
            // The callback's return value is not the state map's value.
            // A flat List[int] is copied a second time on exit so even a
            // callback-local mutable list cannot remain aliased to its caller.
            @SuppressWarnings("unchecked")
            T detached = (T) detachedResult(result);
            return detached;
        } catch (RuntimeException | Error failure) {
            if (writable) {
                values.clear();
                values.putAll(before);
                retainedStringBytes = priorStringBytes;
                retainedListElements = priorListElements;
                // The transaction may have reserved actor quota for larger
                // state; none of it may survive a failed callback/result.
                for (int i = reservations.size() - 1; i >= priorReservations; i--) {
                    reservations.remove(i).close();
                }
                reservedPayloadBytes = priorReservedBytes;
            }
            throw failure;
        } finally {
            access.live = false;
            current = previous;
        }
    }

    private static Object detachedResult(Object value) {
        if (value instanceof List<?>) {
            // A callback cannot launder an arbitrary host List or object
            // graph through a claimed List[int] result.
            return new ArrayList<>(verifiedIntList(value));
        }
        if (safeScalarResult(value)) return value;
        throw new IllegalArgumentException(
                "critical state access cannot escape in result");
    }

    private static boolean safeScalarResult(Object value) {
        // Never accept an arbitrary Number subclass: AtomicInteger/AtomicLong
        // and user-defined host Number types can be mutable and may retain
        // references to the active state loan through their own fields.
        if (value == null || value instanceof String
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double
                || value instanceof java.math.BigInteger
                || value instanceof java.math.BigDecimal
                || value instanceof Boolean || value instanceof Character) return true;
        // Arbitrary objects/closures, Futures and reference graphs remain
        // forbidden until compiler-proven owned lowering exists.
        return false;
    }

    /**
     * Called once by ActorRuntime after the actor's execution turn has fully
     * quiesced, not by arbitrary guest code. The owner ID fences accidental
     * cross-actor finalization or stale actor incarnation references.
     */
    public void retireFromRuntime(ActorRuntime.ActorId confirmedOwner) {
        if (!owner.equals(confirmedOwner)) {
            throw new SecurityException("cannot retire state for a foreign actor");
        }
        if (current != null) {
            throw new IllegalStateException("cannot retire actor state with live critical loan");
        }
        retired = true;
        values.clear();
        retainedStringBytes = 0;
        retainedListElements = 0;
        for (int i = reservations.size() - 1; i >= 0; i--) {
            reservations.remove(i).close();
        }
        reservedPayloadBytes = 0;
    }

    public Set<String> declaredKeys() { return Set.copyOf(schema.keySet()); }
}
