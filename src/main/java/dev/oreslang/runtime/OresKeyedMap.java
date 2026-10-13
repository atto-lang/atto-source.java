package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * <p>Snapshots detach the key/value mapping, not mutable value graphs.
 * Actor/interop transport must independently freeze and authorize every key
 * and recursively copy values before crossing isolation boundaries.
 */
public final class OresKeyedMap<K, V> {
    public enum KeyDomain {
        STRING, SYMBOL, STRING_OR_SYMBOL
    }

    private final KeyDomain domain;
    private final LinkedHashMap<K, V> entries = new LinkedHashMap<>();

    private OresKeyedMap(KeyDomain domain) {
        this.domain = Objects.requireNonNull(domain, "key domain");
    }

    public static <V> OresKeyedMap<String, V> stringKeys() {
        return new OresKeyedMap<>(KeyDomain.STRING);
    }

    public static <V> OresKeyedMap<OresSymbol, V> symbolKeys() {
        return new OresKeyedMap<>(KeyDomain.SYMBOL);
    }

    public static <V> OresKeyedMap<Object, V> stringOrSymbolKeys() {
        return new OresKeyedMap<>(KeyDomain.STRING_OR_SYMBOL);
    }

    public KeyDomain keyDomain() {
        return domain;
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
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
    }

    public boolean containsKey(Object key) {
        checkKey(key);
        return entries.containsKey(key);
    }

    public V get(Object key) {
        checkKey(key);
        return entries.get(key);
    }

    public V put(K key, V value) {
        checkKey(key);
        Objects.requireNonNull(value, "map value");
        return entries.put(key, value);
    }

    /**
     * Validate the entire incoming batch before modifying the destination.
     * No partial insertion occurs if any key or value is invalid. Entries are
     * copied to immutable pairs before validation so mutable source Map.Entry
     * objects cannot change after the validation pass.
     */
    public void putAll(Map<? extends K, ? extends V> values) {
        Objects.requireNonNull(values, "map entries");
        List<Map.Entry<K, V>> prepared = new ArrayList<>(values.size());
        for (Map.Entry<? extends K, ? extends V> entry : values.entrySet()) {
            K key = Objects.requireNonNull(entry, "map entry").getKey();
            V value = entry.getValue();
            checkKey(key);
            Objects.requireNonNull(value, "map value");
            prepared.add(Map.entry(key, value));
        }
        for (Map.Entry<K, V> entry : prepared) {
            entries.put(entry.getKey(), entry.getValue());
        }
    }

    public V remove(Object key) {
        checkKey(key);
        return entries.remove(key);
    }

    public void clear() {
        entries.clear();
    }

    /**
     * Immutable, independently owned mapping of already-immutable keys.
     * Values are NOT recursively frozen: callers may not use this for actor
     * transport or untrusted serialization without additional checks.
     */
    public Map<K, V> shallowSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(entries));
    }

    /** Detached immutable entry pairs, in deterministic insertion order. */
    public List<Map.Entry<K, V>> entrySnapshot() {
        List<Map.Entry<K, V>> result = new ArrayList<>(entries.size());
        for (Map.Entry<K, V> entry : entries.entrySet()) {
            result.add(Map.entry(entry.getKey(), entry.getValue()));
        }
        return List.copyOf(result);
    }
}
