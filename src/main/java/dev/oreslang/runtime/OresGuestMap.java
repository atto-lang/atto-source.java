package dev.oreslang.runtime;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Owned guest map with a *reified key category*. This keeps Map<Symbol,V>
 * distinct from legacy String-named record/class slots. The entry view cannot
 * bypass key validation through Map.Entry.setValue or mutable key insertion.
 *
 * <p>Value-type reification and full Map APIs remain separate milestones.</p>
 */
public final class OresGuestMap extends AbstractMap<Object, Object> {
    public enum KeyDomain { STRING, SYMBOL, STRING_OR_SYMBOL }

    private final KeyDomain keys;
    private final LinkedHashMap<Object, Object> entries = new LinkedHashMap<>();

    public OresGuestMap(KeyDomain keys) {
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    public KeyDomain keyDomain() { return keys; }

    private void requireKey(Object key) {
        boolean valid = switch (keys) {
            case STRING -> key instanceof String;
            case SYMBOL -> key instanceof OresSymbol;
            case STRING_OR_SYMBOL -> key instanceof String || key instanceof OresSymbol;
        };
        if (!valid) throw new IllegalArgumentException(
                "Map key must be " + switch (keys) {
                    case STRING -> "String";
                    case SYMBOL -> "Symbol";
                    case STRING_OR_SYMBOL -> "String or Symbol";
                });
    }

    @Override public Object put(Object key, Object value) {
        requireKey(key);
        Objects.requireNonNull(value, "Map values cannot be null; use Option<T>");
        return entries.put(key, value);
    }

    @Override public Object get(Object key) {
        requireKey(key);
        return entries.get(key);
    }

    @Override public boolean containsKey(Object key) {
        requireKey(key);
        return entries.containsKey(key);
    }

    @Override public Object remove(Object key) {
        requireKey(key);
        return entries.remove(key);
    }

    @Override public Set<Map.Entry<Object, Object>> entrySet() {
        return Collections.unmodifiableMap(entries).entrySet();
    }

    @Override public int size() { return entries.size(); }
}
