package dev.oreslang.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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
    private static final int MAX_OWNED_FIELD_BYTES = 64 * 1024;
    private static final int MAX_TOTAL_OWNED_BYTES = 1024 * 1024;
    private static final int MAX_PENDING_OWNED = 256;

    private final ActorRuntime.ActorId owner;
    private final LinkedHashMap<String, String> schema;
    private final LinkedHashMap<String, Object> values;
    private Access current;
    private long serial;
    private int retainedStringBytes;
    private int retainedOwnedBytes;
    private int pendingOwnedBytes;
    private final Set<OwnedBytes> pendingOwned =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean retired;

    public SealedActorState(ActorRuntime.ActorId owner,
                            Map<String, String> schema,
                            Map<String, Object> defaults) {
        this.owner = Objects.requireNonNull(owner, "actor identity");
        this.schema = new LinkedHashMap<>(Objects.requireNonNull(schema, "schema"));
        if (this.schema.isEmpty() || this.schema.size() > MAX_FIELDS) {
            throw new IllegalArgumentException("actor state requires 1..256 typed fields");
        }
        this.values = new LinkedHashMap<>();
        for (var item : this.schema.entrySet()) {
            switch (item.getValue()) {
                case "int", "bool", "string", "String", "owned:bytes" -> { }
                default -> throw new UnsupportedOperationException(
                        "runtime actor state type is not yet lowered: " + item.getValue());
            }
            values.put(item.getKey(), Uninitialized.INSTANCE);
        }
        for (var entry : Objects.requireNonNull(defaults, "defaults").entrySet()) {
            validateValue(entry.getKey(), entry.getValue());
            int size = stringBytes(entry.getValue());
            if (size > MAX_TOTAL_STRING_BYTES - retainedStringBytes) {
                throw new IllegalArgumentException("actor state string byte budget exceeded");
            }
            retainedStringBytes += size;
            values.put(entry.getKey(), entry.getValue());
        }
    }


    /**
     * Internal proof-of-transfer capsule, deliberately NOT a source-level
     * Owned<T> or public heap alias. The issuer owns the backing bytes until
     * an exclusive loan consumes the capsule into exactly one state field.
     * No method ever returns the raw backing array.
     */
    public final class OwnedBytes {
        private byte[] bytes;
        private OwnedBytes(int size) { bytes = new byte[size]; }
        private SealedActorState issuer() { return SealedActorState.this; }
        private void check() {
            requireOwner();
            if (bytes == null || !pendingOwned.contains(this))
                throw new IllegalStateException("actor-owned bytes already moved or revoked");
        }
        public int length() { check(); return bytes.length; }
        public int read(int index) {
            check();
            return Byte.toUnsignedInt(bytes[Objects.checkIndex(index, bytes.length)]);
        }
        public void write(int index, int unsignedByte) {
            check();
            requireOwnedRegionMutationAuthority();
            if (unsignedByte < 0 || unsignedByte > 255)
                throw new IllegalArgumentException("owned byte value must be 0..255");
            bytes[Objects.checkIndex(index, bytes.length)] = (byte) unsignedByte;
        }
    }

    private static final class OwnedRegion {
        private final byte[] bytes;
        private OwnedRegion(byte[] bytes) { this.bytes = bytes; }
        private void erase() { Arrays.fill(bytes, (byte) 0); }
    }

    /**
     * Trusted Java runtime substrate only. Source-level reference transfers
     * stay rejected until compiler-verified allocation provenance, moves,
     * and LLVM codegen share a documented ABI.
     */
    public OwnedBytes allocateOwnedBytes(int size) {
        requireOwnedRegionMutationAuthority();
        if (size < 0 || size > MAX_OWNED_FIELD_BYTES)
            throw new IllegalArgumentException("invalid actor-owned byte region size");
        if (pendingOwned.size() >= MAX_PENDING_OWNED
                || size > MAX_TOTAL_OWNED_BYTES - retainedOwnedBytes - pendingOwnedBytes)
            throw new IllegalArgumentException("actor-owned allocation budget exceeded");
        OwnedBytes capsule = new OwnedBytes(size);
        pendingOwned.add(capsule);
        pendingOwnedBytes += size;
        return capsule;
    }

    private enum Uninitialized { INSTANCE }

    private void requireOwner() {
        if (retired) throw new IllegalStateException("actor state retired");
        if (!owner.equals(ActorRuntime.currentActorId().orElse(null))) {
            throw new SecurityException("actor state requires the owning actor's admitted turn");
        }
    }

    private void requireOwnedRegionMutationAuthority() {
        requireOwner();
        // The capsule must not become a back door to mutate actor-owned
        // memory during a nested or top-level read-only state grant.
        if (current != null && !current.writable)
            throw new SecurityException("critical read cannot allocate or mutate owned state");
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
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("actor state key '" + name
                    + "' expects " + expected + ", got "
                    + (value == null ? "null" : value.getClass().getSimpleName()));
        }
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
            if (value instanceof OwnedRegion)
                throw new SecurityException("raw actor-owned reference cannot escape state loan");
            return value;
        }

        /**
         * Consumes an issuer-bound allocation exactly once under a WRITE loan.
         * Failed validation leaves the source live and its destination intact.
         */
        public void takeOwnedBytes(String name, OwnedBytes source) {
            check();
            if (!writable) throw new SecurityException("critical read cannot transfer owned state");
            if (!"owned:bytes".equals(schema.get(name)))
                throw new IllegalArgumentException("actor state key is not owned:bytes: " + name);
            Objects.requireNonNull(source, "owned source");
            if (source.getClass() != OwnedBytes.class || source.issuer() != SealedActorState.this)
                throw new SecurityException("foreign actor-owned allocation issuer");
            source.check();
            byte[] moved = source.bytes;
            Object prior = values.get(name);
            int released = prior instanceof OwnedRegion region ? region.bytes.length : 0;
            // The allocation was accounted for when created and cannot
            // exceed the total live+pending budget on this actor incarnation.
            values.put(name, new OwnedRegion(moved));
            source.bytes = null;
            pendingOwned.remove(source);
            pendingOwnedBytes -= moved.length;
            retainedOwnedBytes = retainedOwnedBytes - released + moved.length;
            if (prior instanceof OwnedRegion region) region.erase();
        }

        private OwnedRegion ownedRegion(String name) {
            if (!"owned:bytes".equals(schema.get(name)))
                throw new IllegalArgumentException("actor state key is not owned:bytes: " + name);
            Object value = values.get(name);
            if (value == Uninitialized.INSTANCE)
                throw new IllegalStateException("actor-owned state field is uninitialized: " + name);
            return (OwnedRegion) value;
        }

        public int readOwnedByte(String name, int index) {
            check();
            byte[] data = ownedRegion(name).bytes;
            return Byte.toUnsignedInt(data[Objects.checkIndex(index, data.length)]);
        }

        public void writeOwnedByte(String name, int index, int unsignedByte) {
            check();
            if (!writable) throw new SecurityException("critical read cannot write actor state");
            if (unsignedByte < 0 || unsignedByte > 255)
                throw new IllegalArgumentException("owned byte value must be 0..255");
            byte[] data = ownedRegion(name).bytes;
            data[Objects.checkIndex(index, data.length)] = (byte) unsignedByte;
        }
        public void write(String name, Object value) {
            check();
            if (!writable) throw new SecurityException("critical read cannot write actor state");
            validateValue(name, value);
            int previousBytes = stringBytes(values.get(name));
            int nextBytes = stringBytes(value);
            if (nextBytes > MAX_TOTAL_STRING_BYTES
                    - (retainedStringBytes - previousBytes)) {
                throw new IllegalArgumentException("actor state string byte budget exceeded");
            }
            values.put(name, value);
            retainedStringBytes = retainedStringBytes - previousBytes + nextBytes;
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
        current = access;
        try {
            T result = callback.apply(access);
            if (!safeResult(result, 0)) {
                throw new IllegalArgumentException("critical state access cannot escape in result");
            }
            return result;
        } finally {
            access.live = false;
            current = previous;
        }
    }

    private boolean safeResult(Object value, int depth) {
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
        // Avoid arbitrary mutable guest/Java wrappers and every possible
        // deferred object; the source ownership checker must prove complex
        // returned values before this boundary can be broadened.
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
        for (OwnedBytes capsule : pendingOwned) {
            if (capsule.bytes != null) Arrays.fill(capsule.bytes, (byte) 0);
            capsule.bytes = null;
        }
        pendingOwned.clear();
        for (Object value : values.values()) {
            if (value instanceof OwnedRegion region) region.erase();
        }
        values.clear();
        retainedStringBytes = 0;
        retainedOwnedBytes = 0;
        pendingOwnedBytes = 0;
    }

    public Set<String> declaredKeys() { return Set.copyOf(schema.keySet()); }
}
