package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/**
 * Dedicated guest Map storage foundation. This is NOT a record/struct and does
 * not implement java.util.Map, intentionally avoiding implicit structural-field
 * dispatch and mutable host-map escape hatches.
 *
 * <p>Key domains are explicit and disjoint. Both PROCESS and STABLE Symbols
 * have nominal (kind, normalized-name) identity; no #string conversion or
 * reference-identity/intern-table hashing is performed. Guest typing, actor
 * transport, and public construction are staged in separate compiler/runtime
 * integrations: this class alone does not expose Map<K,V> to guest code.
 *
 * <p>Operations are synchronized on this instance so concurrent guest tasks
 * cannot corrupt key order or race quota admission. The map is still mutable,
 * and snapshots do not make referenced value graphs immutable.
 *
 * <p>Snapshots detach the key/value mapping, not mutable value graphs.
 * Actor/interop transport must independently freeze and authorize every key
 * and recursively copy values before crossing isolation boundaries.
 */
public final class OresKeyedMap<K, V> {
    // First-stage per-object budget, identical to the opt-in tagged key codec.
    // Existing String-keyed records and ordinary guest Strings are unaffected.
    public static final int MAX_ENTRIES = 4_096;
    public static final int MAX_TEXT_KEY_BYTES = 16_384;
    // Include each key's six-byte tag/version/length envelope, matching v1
    // SymbolPropertyKeyCodec. Entry count alone permits ~64 MiB of key text.
    public static final long MAX_KEY_SET_WIRE_BYTES = 1_048_576L;

    public enum KeyDomain {
        STRING, SYMBOL, STRING_OR_SYMBOL
    }

    /**
     * V1 guest runtime reifies value types instead of assuming the typechecker
     * protects dynamic/foreign callers. ANY is explicitly dynamic only.
     */
    public enum ValueDomain {
        ANY, INT, STRING, SYMBOL, BOOL
    }

    /**
     * Atomic presence result for non-throwing guest lookup/take lowering.
     *
     * <p>The outer presence bit belongs to the Map operation.  The value is
     * deliberately opaque here, so a stored guest Option/Result remains a
     * distinct inner value rather than being collapsed into map absence.
     */
    public record Lookup<V>(boolean present, V value) {
        public Lookup {
            if (present && value == null) {
                throw new IllegalArgumentException("present Map lookup requires a non-null value");
            }
            if (!present && value != null) {
                throw new IllegalArgumentException("missing Map lookup cannot carry a value");
            }
        }

        public static <V> Lookup<V> missing() {
            return new Lookup<>(false, null);
        }

        public static <V> Lookup<V> present(V value) {
            return new Lookup<>(true, Objects.requireNonNull(value, "map lookup value"));
        }
    }

    @FunctionalInterface
    public interface EntryValidator {
        void validate(Object key, Object value);
    }

    private static final EntryValidator NO_ENTRY_VALIDATOR = (key, value) -> { };

    /**
     * Internal guest-key identity. Equality is defined only by the canonical
     * tagged codec frame; guest key host equals/hashCode never select a slot.
     */
    private static final class CanonicalKey {
        private final byte[] frame;
        private final Object guestKey;

        private CanonicalKey(Object source) {
            byte[] encoded = SymbolPropertyKeyCodec.encode(source);
            this.frame = encoded;
            this.guestKey = SymbolPropertyKeyCodec.decode(encoded);
        }

        private int wireBytes() {
            return frame.length;
        }

        private Object guestKey() {
            return guestKey;
        }

        @Override
        public boolean equals(Object other) {
            return this == other
                    || other instanceof CanonicalKey that
                    && Arrays.equals(frame, that.frame);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(frame);
        }
    }

    private final ValueDomain valueDomain;
    private final KeyDomain domain;
    private final EntryValidator entryValidator;
    // Replaced atomically after bulk admission; all callers hold this monitor.
    private LinkedHashMap<CanonicalKey, V> entries = new LinkedHashMap<>();
    // Tracked while holding this map's monitor; never expose a mutable view.
    private long storedKeyWireBytes;
    // Java monitors are reentrant. A key-aware validator must never be able
    // to mutate this same map while an outer mutation is still validating.
    private boolean entryValidationActive;
    // A caller-supplied host Map can invoke this map's methods from size(),
    // entrySet(), iterator.next() or Entry getters during a putAll snapshot.
    // Reject such reentrant mutation until the whole batch is committed.
    private boolean bulkMutationActive;

    private OresKeyedMap(
            KeyDomain domain,
            ValueDomain valueDomain,
            EntryValidator entryValidator) {
        this.domain = Objects.requireNonNull(domain, "key domain");
        this.valueDomain = Objects.requireNonNull(valueDomain, "value domain");
        this.entryValidator = Objects.requireNonNull(entryValidator, "entry validator");
    }

    /** Host-only constructor defaults to explicit dynamic values. */
    public static <V> OresKeyedMap<String, V> stringKeys() {
        return stringKeys(ValueDomain.ANY);
    }

    public static <V> OresKeyedMap<String, V> stringKeys(ValueDomain values) {
        return new OresKeyedMap<>(KeyDomain.STRING, values, NO_ENTRY_VALIDATOR);
    }

    /**
     * String-keyed storage with an additional key-aware value policy.
     * The validator runs after generic key/value-domain checks and before
     * every mutation, including each entry of transactional putAll.
     */
    public static <V> OresKeyedMap<String, V> stringKeys(
            ValueDomain values,
            EntryValidator validator) {
        return new OresKeyedMap<>(
                KeyDomain.STRING,
                values,
                Objects.requireNonNull(validator, "entry validator"));
    }

    /** Host-only constructor defaults to explicit dynamic values. */
    public static <V> OresKeyedMap<OresSymbol, V> symbolKeys() {
        return symbolKeys(ValueDomain.ANY);
    }

    public static <V> OresKeyedMap<OresSymbol, V> symbolKeys(ValueDomain values) {
        return new OresKeyedMap<>(KeyDomain.SYMBOL, values, NO_ENTRY_VALIDATOR);
    }

    public static <V> OresKeyedMap<OresSymbol, V> symbolKeys(
            ValueDomain values,
            EntryValidator validator) {
        return new OresKeyedMap<>(
                KeyDomain.SYMBOL,
                values,
                Objects.requireNonNull(validator, "entry validator"));
    }

    /** Host-only constructor defaults to explicit dynamic values. */
    public static <V> OresKeyedMap<Object, V> stringOrSymbolKeys() {
        return stringOrSymbolKeys(ValueDomain.ANY);
    }

    public static <V> OresKeyedMap<Object, V> stringOrSymbolKeys(ValueDomain values) {
        return new OresKeyedMap<>(KeyDomain.STRING_OR_SYMBOL, values, NO_ENTRY_VALIDATOR);
    }

    public static <V> OresKeyedMap<Object, V> stringOrSymbolKeys(
            ValueDomain values,
            EntryValidator validator) {
        return new OresKeyedMap<>(
                KeyDomain.STRING_OR_SYMBOL,
                values,
                Objects.requireNonNull(validator, "entry validator"));
    }

    public KeyDomain keyDomain() {
        return domain;
    }

    public ValueDomain valueDomain() {
        return valueDomain;
    }

    /**
     * Snapshot canonical key frames under this map's monitor. Equality and
     * hashing therefore never invoke guest-key host equality while locks are held.
     */
    private synchronized LinkedHashMap<CanonicalKey, V> equalitySnapshot() {
        return new LinkedHashMap<>(entries);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OresKeyedMap<?, ?> that)) return false;
        if (domain != that.domain || valueDomain != that.valueDomain) return false;
        return equalitySnapshot().equals(that.equalitySnapshot());
    }

    @Override
    public int hashCode() {
        LinkedHashMap<CanonicalKey, V> snapshot = equalitySnapshot();
        int result = domain.hashCode();
        result = 31 * result + valueDomain.hashCode();
        return 31 * result + snapshot.hashCode();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    private CanonicalKey canonicalKey(Object key) {
        Objects.requireNonNull(key, "map key");
        boolean valid = switch (domain) {
            case STRING -> key instanceof String;
            case SYMBOL -> key instanceof OresSymbol;
            case STRING_OR_SYMBOL -> key instanceof String || key instanceof OresSymbol;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "key is incompatible with Map key domain " + domain);
        }
        return new CanonicalKey(key);
    }

    @SuppressWarnings("unchecked")
    private K guestKey(CanonicalKey key) {
        return (K) key.guestKey();
    }

    public synchronized boolean containsKey(Object key) {
        return entries.containsKey(canonicalKey(key));
    }

    public synchronized V get(Object key) {
        return entries.get(canonicalKey(key));
    }

    /**
     * Resolve absence and value under one monitor without sentinel/null
     * ambiguity. The outer presence bit belongs to the Map operation.
     */
    public synchronized Lookup<V> lookup(Object key) {
        V value = entries.get(canonicalKey(key));
        return value == null ? Lookup.missing() : Lookup.present(value);
    }

    /**
     * Required indexed read. Missing keys fail instead of leaking null.
     */
    public synchronized V getRequired(Object key) {
        CanonicalKey canonical = canonicalKey(key);
        V value = entries.get(canonical);
        if (value == null) {
            throw new IllegalArgumentException("unknown typed Map key");
        }
        return value;
    }

    private void checkValue(Object key, Object value) {
        Objects.requireNonNull(value, "map value");
        boolean valid = switch (valueDomain) {
            case ANY -> true;
            case INT -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long
                    || value instanceof java.math.BigInteger;
            case STRING -> value instanceof String;
            case SYMBOL -> value instanceof OresSymbol;
            case BOOL -> value instanceof Boolean;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "value is incompatible with Map value domain " + valueDomain);
        }
        if (entryValidationActive) {
            throw new IllegalStateException(
                    "typed Map entry validator cannot recursively validate the same map");
        }
        entryValidationActive = true;
        try {
            entryValidator.validate(key, value);
        } finally {
            entryValidationActive = false;
        }
    }

    private void rejectReentrantMutation() {
        if (entryValidationActive || bulkMutationActive) {
            throw new IllegalStateException(
                    "typed Map entry validator or batch input cannot mutate the map being validated");
        }
    }

    public synchronized V put(K key, V value) {
        rejectReentrantMutation();
        CanonicalKey canonical = canonicalKey(key);
        K canonicalGuestKey = guestKey(canonical);
        checkValue(canonicalGuestKey, value);
        long addedBytes = 0L;
        if (!entries.containsKey(canonical)) {
            if (entries.size() >= MAX_ENTRIES) {
                throw new IllegalArgumentException(
                        "typed Map exceeds maximum key count");
            }
            addedBytes = canonical.wireBytes();
            if (addedBytes > MAX_KEY_SET_WIRE_BYTES - storedKeyWireBytes) {
                throw new IllegalArgumentException(
                        "typed Map exceeds aggregate key byte budget");
            }
        }
        V previous = entries.put(canonical, value);
        storedKeyWireBytes += addedBytes;
        return previous;
    }

    /**
     * Validate the entire incoming batch before modifying the destination.
     * No partial insertion occurs if any key or value is invalid. Entries are
     * copied to immutable pairs before validation so mutable source Map.Entry
     * objects cannot change after the validation pass.
     */
    public synchronized void putAll(Map<? extends K, ? extends V> values) {
        rejectReentrantMutation();
        Objects.requireNonNull(values, "map entries");
        bulkMutationActive = true;
        try {
            int reportedSize = values.size();
            if (reportedSize < 0 || reportedSize > MAX_ENTRIES) {
                throw new IllegalArgumentException(
                        "typed Map batch exceeds maximum key count");
            }

            List<Map.Entry<CanonicalKey, V>> prepared =
                    new ArrayList<>(reportedSize);
            Set<CanonicalKey> incomingKeys = new HashSet<>();
            int visited = 0;
            for (Map.Entry<? extends K, ? extends V> entry : values.entrySet()) {
                if (++visited > MAX_ENTRIES) {
                    throw new IllegalArgumentException(
                            "typed Map batch exceeds maximum key count");
                }
                Map.Entry<? extends K, ? extends V> pair =
                        Objects.requireNonNull(entry, "map entry");
                CanonicalKey key = canonicalKey(pair.getKey());
                V value = pair.getValue();
                checkValue(guestKey(key), value);
                if (!incomingKeys.add(key)) {
                    throw new IllegalArgumentException(
                            "duplicate logical key in Map batch");
                }
                prepared.add(Map.entry(key, value));
            }
            if (visited != reportedSize) {
                throw new IllegalArgumentException(
                        "host Map size changed during batch snapshot");
            }

            Set<CanonicalKey> additions = new HashSet<>();
            long additionalKeyWireBytes = 0L;
            for (Map.Entry<CanonicalKey, V> entry : prepared) {
                CanonicalKey key = entry.getKey();
                if (!entries.containsKey(key) && additions.add(key)) {
                    additionalKeyWireBytes += key.wireBytes();
                    if (additionalKeyWireBytes
                            > MAX_KEY_SET_WIRE_BYTES - storedKeyWireBytes) {
                        throw new IllegalArgumentException(
                                "typed Map exceeds aggregate key byte budget");
                    }
                }
            }
            if (additions.size() > MAX_ENTRIES - entries.size()) {
                throw new IllegalArgumentException(
                        "typed Map exceeds maximum key count");
            }

            LinkedHashMap<CanonicalKey, V> staged =
                    new LinkedHashMap<>(entries);
            for (Map.Entry<CanonicalKey, V> entry : prepared) {
                staged.put(entry.getKey(), entry.getValue());
            }
            entries = staged;
            storedKeyWireBytes += additionalKeyWireBytes;
        } finally {
            bulkMutationActive = false;
        }
    }

    /**
     * Transactionally ingest nominal Oreslang Entry values.
     *
     * <p>This is deliberately separate from putAll(Map). Converting an Entry
     * sequence to a host Map first would silently collapse duplicate logical
     * keys according to host-map policy. Guest Map.from_entries is strict:
     * every input Entry is validated, duplicate logical keys are rejected,
     * quotas are checked, and only then is a detached mapping published.
     */
    public synchronized void putEntries(List<OresEntry> values) {
        rejectReentrantMutation();
        Objects.requireNonNull(values, "Map.from_entries values");
        bulkMutationActive = true;
        try {
            int reportedSize = values.size();
            if (reportedSize < 0 || reportedSize > MAX_ENTRIES) {
                throw new IllegalArgumentException(
                        "Map.from_entries exceeds maximum entry count");
            }

            List<Map.Entry<CanonicalKey, V>> prepared =
                    new ArrayList<>(reportedSize);
            Set<CanonicalKey> incomingKeys = new HashSet<>();
            int visited = 0;
            for (OresEntry source : values) {
                if (++visited > MAX_ENTRIES) {
                    throw new IllegalArgumentException(
                            "Map.from_entries exceeds maximum entry count");
                }
                OresEntry pair = Objects.requireNonNull(
                        source, "Map.from_entries requires Entry<K,V> values");
                CanonicalKey key = canonicalKey(pair.key());
                @SuppressWarnings("unchecked")
                V value = (V) pair.value();
                checkValue(guestKey(key), value);
                if (!incomingKeys.add(key) || entries.containsKey(key)) {
                    throw new IllegalArgumentException(
                            "duplicate logical key in Map.from_entries");
                }
                prepared.add(Map.entry(key, value));
            }
            if (visited != reportedSize) {
                throw new IllegalArgumentException(
                        "Map.from_entries collection size changed during snapshot");
            }

            long additionalKeyWireBytes = 0L;
            for (Map.Entry<CanonicalKey, V> entry : prepared) {
                additionalKeyWireBytes += entry.getKey().wireBytes();
                if (additionalKeyWireBytes
                        > MAX_KEY_SET_WIRE_BYTES - storedKeyWireBytes) {
                    throw new IllegalArgumentException(
                            "typed Map exceeds aggregate key byte budget");
                }
            }
            if (prepared.size() > MAX_ENTRIES - entries.size()) {
                throw new IllegalArgumentException(
                        "typed Map exceeds maximum key count");
            }

            LinkedHashMap<CanonicalKey, V> staged =
                    new LinkedHashMap<>(entries);
            for (Map.Entry<CanonicalKey, V> entry : prepared) {
                staged.put(entry.getKey(), entry.getValue());
            }
            entries = staged;
            storedKeyWireBytes += additionalKeyWireBytes;
        } finally {
            bulkMutationActive = false;
        }
    }

    /**
     * Atomically remove and transfer a value while preserving key absence as
     * an explicit outer state.  No public has/get/remove sequence is needed.
     */
    public synchronized Lookup<V> take(Object key) {
        rejectReentrantMutation();
        CanonicalKey canonical = canonicalKey(key);
        V removed = entries.remove(canonical);
        if (removed == null) return Lookup.missing();
        storedKeyWireBytes -= canonical.wireBytes();
        return Lookup.present(removed);
    }

    /** Host compatibility API; guest consuming lowering should prefer take(). */
    public synchronized V remove(Object key) {
        return take(key).value();
    }

    public synchronized void clear() {
        rejectReentrantMutation();
        entries.clear();
        storedKeyWireBytes = 0L;
    }

    /**
     * Immutable, independently owned mapping of already-immutable keys.
     * Values are NOT recursively frozen: callers may not use this for actor
     * transport or untrusted serialization without additional checks.
     */
    public synchronized Map<K, V> shallowSnapshot() {
        LinkedHashMap<K, V> snapshot = new LinkedHashMap<>();
        for (Map.Entry<CanonicalKey, V> entry : entries.entrySet()) {
            snapshot.put(guestKey(entry.getKey()), entry.getValue());
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /** Detached immutable entry pairs, in deterministic insertion order. */
    public synchronized List<Map.Entry<K, V>> entrySnapshot() {
        List<Map.Entry<K, V>> result = new ArrayList<>(entries.size());
        for (Map.Entry<CanonicalKey, V> entry : entries.entrySet()) {
            result.add(Map.entry(
                    guestKey(entry.getKey()),
                    entry.getValue()));
        }
        return List.copyOf(result);
    }

}
