package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
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

    private final ValueDomain valueDomain;
    private final KeyDomain domain;
    private final LinkedHashMap<K, V> entries = new LinkedHashMap<>();
    // Tracked while holding this map's monitor; never expose a mutable view.
    private long storedKeyWireBytes;

    private OresKeyedMap(KeyDomain domain, ValueDomain valueDomain) {
        this.domain = Objects.requireNonNull(domain, "key domain");
        this.valueDomain = Objects.requireNonNull(valueDomain, "value domain");
    }

    /** Host-only constructor defaults to explicit dynamic values. */
    public static <V> OresKeyedMap<String, V> stringKeys() {
        return stringKeys(ValueDomain.ANY);
    }

    public static <V> OresKeyedMap<String, V> stringKeys(ValueDomain values) {
        return new OresKeyedMap<>(KeyDomain.STRING, values);
    }

    /** Host-only constructor defaults to explicit dynamic values. */
    public static <V> OresKeyedMap<OresSymbol, V> symbolKeys() {
        return symbolKeys(ValueDomain.ANY);
    }

    public static <V> OresKeyedMap<OresSymbol, V> symbolKeys(ValueDomain values) {
        return new OresKeyedMap<>(KeyDomain.SYMBOL, values);
    }

    /** Host-only constructor defaults to explicit dynamic values. */
    public static <V> OresKeyedMap<Object, V> stringOrSymbolKeys() {
        return stringOrSymbolKeys(ValueDomain.ANY);
    }

    public static <V> OresKeyedMap<Object, V> stringOrSymbolKeys(ValueDomain values) {
        return new OresKeyedMap<>(KeyDomain.STRING_OR_SYMBOL, values);
    }

    public KeyDomain keyDomain() {
        return domain;
    }

    public ValueDomain valueDomain() {
        return valueDomain;
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    private static void checkStringKey(String text) {
        if (text.length() > MAX_TEXT_KEY_BYTES) {
            throw new IllegalArgumentException("Map String key exceeds byte limit");
        }
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            if (bytes.remaining() > MAX_TEXT_KEY_BYTES) {
                throw new IllegalArgumentException("Map String key exceeds byte limit");
            }
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("Map String key must be valid Unicode", invalid);
        }
    }

    private void checkKey(Object key) {
        Objects.requireNonNull(key, "map key");
        boolean valid = switch (domain) {
            case STRING -> key instanceof String;
            case SYMBOL -> key instanceof OresSymbol;
            case STRING_OR_SYMBOL -> key instanceof String || key instanceof OresSymbol;
        };
        if (!valid) {
            throw new IllegalArgumentException("key is incompatible with Map key domain " + domain);
        }
        if (key instanceof String text) checkStringKey(text);
    }

    public synchronized boolean containsKey(Object key) {
        checkKey(key);
        return entries.containsKey(key);
    }

    public synchronized V get(Object key) {
        checkKey(key);
        return entries.get(key);
    }

    /**
     * Guest indexed reads are total only for present keys. Resolve presence
     * and value under one monitor: containsKey followed by get can otherwise
     * race with remove/clear and leak null into a statically non-null V.
     */
    public synchronized V getRequired(Object key) {
        checkKey(key);
        if (!entries.containsKey(key)) {
            throw new IllegalArgumentException("unknown typed Map key");
        }
        return entries.get(key);
    }

    private void checkValue(Object value) {
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
    }

    private static int wireKeyBytes(Object key) {
        // The canonical tagged-key codec defines the identical logical
        // String/PROCESS/STABLE footprint used by bulk serialization.
        return SymbolPropertyKeyCodec.encode(key).length;
    }

    public synchronized V put(K key, V value) {
        checkKey(key);
        checkValue(value);
        long addedBytes = 0L;
        if (!entries.containsKey(key)) {
            if (entries.size() >= MAX_ENTRIES) {
                throw new IllegalArgumentException("typed Map exceeds maximum key count");
            }
            addedBytes = wireKeyBytes(key);
            if (addedBytes > MAX_KEY_SET_WIRE_BYTES - storedKeyWireBytes) {
                throw new IllegalArgumentException("typed Map exceeds aggregate key byte budget");
            }
        }
        V previous = entries.put(key, value);
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
        Objects.requireNonNull(values, "map entries");
        // Host-supplied Map.size() is not trusted. Enforce the guest bound
        // before using it as an allocation hint, then enforce it again while
        // consuming entries (custom Maps may lie about their size).
        int reportedSize = values.size();
        if (reportedSize < 0 || reportedSize > MAX_ENTRIES) {
            throw new IllegalArgumentException("typed Map batch exceeds maximum key count");
        }
        List<Map.Entry<K, V>> prepared = new ArrayList<>(reportedSize);
        // A caller may supply IdentityHashMap or a custom Map whose equality
        // differs from the guest Map's nominal String/Symbol equality. Reject
        // duplicate *logical* keys before any writes rather than letting
        // iteration order silently determine the winning value.
        Set<K> incomingKeys = new HashSet<>();
        for (Map.Entry<? extends K, ? extends V> entry : values.entrySet()) {
            if (prepared.size() >= MAX_ENTRIES) {
                throw new IllegalArgumentException("typed Map batch exceeds maximum key count");
            }
            K key = Objects.requireNonNull(entry, "map entry").getKey();
            V value = entry.getValue();
            checkKey(key);
            checkValue(value);
            if (!incomingKeys.add(key)) {
                throw new IllegalArgumentException("duplicate logical key in Map batch");
            }
            prepared.add(Map.entry(key, value));
        }
        if (prepared.size() != reportedSize) {
            throw new IllegalArgumentException("host Map size changed during batch snapshot");
        }
        Set<K> additions = new HashSet<>();
        long additionalKeyWireBytes = 0L;
        for (Map.Entry<K, V> entry : prepared) {
            K key = entry.getKey();
            if (!entries.containsKey(key) && additions.add(key)) {
                additionalKeyWireBytes += wireKeyBytes(key);
                if (additionalKeyWireBytes > MAX_KEY_SET_WIRE_BYTES - storedKeyWireBytes) {
                    throw new IllegalArgumentException("typed Map exceeds aggregate key byte budget");
                }
            }
        }
        if (additions.size() > MAX_ENTRIES - entries.size()) {
            throw new IllegalArgumentException("typed Map exceeds maximum key count");
        }
        for (Map.Entry<K, V> entry : prepared) {
            entries.put(entry.getKey(), entry.getValue());
        }
        storedKeyWireBytes += additionalKeyWireBytes;
    }

    public synchronized V remove(Object key) {
        checkKey(key);
        V removed = entries.remove(key);
        if (removed != null) storedKeyWireBytes -= wireKeyBytes(key);
        return removed;
    }

    public synchronized void clear() {
        entries.clear();
        storedKeyWireBytes = 0L;
    }

    /**
     * Immutable, independently owned mapping of already-immutable keys.
     * Values are NOT recursively frozen: callers may not use this for actor
     * transport or untrusted serialization without additional checks.
     */
    public synchronized Map<K, V> shallowSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** Detached immutable entry pairs, in deterministic insertion order. */
    public synchronized List<Map.Entry<K, V>> entrySnapshot() {
        List<Map.Entry<K, V>> result = new ArrayList<>(entries.size());
        for (Map.Entry<K, V> entry : entries.entrySet()) {
            result.add(Map.entry(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(result);
    }
}
