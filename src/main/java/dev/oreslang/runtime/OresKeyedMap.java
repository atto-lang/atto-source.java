package dev.oreslang.runtime;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Dedicated immutable guest Map storage for the first current-main typed-Map
 * slice. It deliberately does not implement java.util.Map, so guest Map values
 * cannot be confused with structural records or leaked as mutable host maps.
 */
public final class OresKeyedMap {
    public static final int MAX_ENTRIES = 4_096;
    public static final int MAX_KEY_BYTES = 16_384;
    public static final long MAX_KEY_SET_BYTES = 1_048_576L;

    public enum KeyDomain { INT, STRING, SYMBOL }

    public record Lookup(boolean present, Object value) {
        public Lookup {
            if (present && value == null) {
                throw new IllegalArgumentException("present Map lookup requires a value");
            }
            if (!present && value != null) {
                throw new IllegalArgumentException("missing Map lookup cannot carry a value");
            }
        }
        public static Lookup missing() { return new Lookup(false, null); }
        public static Lookup present(Object value) {
            return new Lookup(true, Objects.requireNonNull(value, "Map lookup value"));
        }
    }

    /** Data-only duplicate-key payload returned by Map.from_entries. */
    public record DuplicateKey(Object key) {
        public DuplicateKey {
            Objects.requireNonNull(key, "duplicate Map key");
        }
    }

    public static final class DuplicateKeyException extends IllegalArgumentException {
        private final Object key;
        private DuplicateKeyException(Object key) {
            super("duplicate key in Map.from_entries");
            this.key = key;
        }
        public Object key() { return key; }
    }

    private final KeyDomain domain;
    private final LinkedHashMap<Object, OresEntry> entries;
    private final long keyBytes;

    private OresKeyedMap(
            KeyDomain domain,
            LinkedHashMap<Object, OresEntry> entries,
            long keyBytes) {
        this.domain = Objects.requireNonNull(domain, "Map key domain");
        this.entries = new LinkedHashMap<>(entries);
        this.keyBytes = keyBytes;
    }

    public static OresKeyedMap fromEntries(List<?> values) {
        Objects.requireNonNull(values, "Map.from_entries entries");
        int reportedSize = values.size();
        if (reportedSize < 0 || reportedSize > MAX_ENTRIES) {
            throw new IllegalArgumentException("typed Map exceeds maximum key count");
        }

        KeyDomain domain = null;
        LinkedHashMap<Object, OresEntry> staged = new LinkedHashMap<>();
        long totalKeyBytes = 0L;
        int observed = 0;
        for (Object raw : values) {
            if (++observed > MAX_ENTRIES) {
                throw new IllegalArgumentException("typed Map exceeds maximum key count");
            }
            if (!(raw instanceof OresEntry entry)) {
                throw new IllegalArgumentException(
                        "Map.from_entries expects only Entry<K,V> values");
            }
            if (domain == null) domain = domainOf(entry.key());
            Object normalized = normalizeKey(domain, entry.key());
            Object value = Objects.requireNonNull(entry.value(), "Map value");
            if (staged.containsKey(normalized)) {
                throw new DuplicateKeyException(entry.key());
            }
            totalKeyBytes = Math.addExact(
                    totalKeyBytes,
                    encodedKeyBytes(domain, normalized));
            if (totalKeyBytes > MAX_KEY_SET_BYTES) {
                throw new IllegalArgumentException(
                        "typed Map exceeds aggregate key byte budget");
            }
            staged.put(normalized, new OresEntry(entry.key(), value));
        }

        if (observed != reportedSize) {
            throw new IllegalArgumentException(
                    "Map.from_entries input size changed during snapshot");
        }
        if (domain == null) {
            throw new IllegalArgumentException(
                    "Map.from_entries cannot infer an empty runtime key domain");
        }
        return new OresKeyedMap(domain, staged, totalKeyBytes);
    }

    public KeyDomain keyDomain() { return domain; }
    public int size() { return entries.size(); }
    public long keyBytes() { return keyBytes; }

    /** Fresh mapping object for async ownership detachment; payloads are v1 copy-safe. */
    public OresKeyedMap detachedCopy() {
        return new OresKeyedMap(domain, entries, keyBytes);
    }

    public boolean containsKey(Object key) {
        return entries.containsKey(normalizeKey(domain, key));
    }

    public Lookup lookup(Object key) {
        Object normalized = normalizeKey(domain, key);
        OresEntry entry = entries.get(normalized);
        return entry == null ? Lookup.missing() : Lookup.present(entry.value());
    }

    public Object getRequired(Object key) {
        Object normalized = normalizeKey(domain, key);
        if (!entries.containsKey(normalized)) {
            throw new IllegalArgumentException("unknown typed Map key");
        }
        return entries.get(normalized).value();
    }

    /** Deterministic detached Entry values in insertion order. */
    public List<OresEntry> entrySnapshot() {
        ArrayList<OresEntry> result = new ArrayList<>(entries.size());
        for (OresEntry entry : entries.values()) {
            result.add(entry);
        }
        return List.copyOf(result);
    }

    private static KeyDomain domainOf(Object key) {
        if (isIntegral(key)) return KeyDomain.INT;
        if (key instanceof String) return KeyDomain.STRING;
        if (key instanceof OresSymbol) return KeyDomain.SYMBOL;
        throw new IllegalArgumentException(
                "Map key must be int, String, or Symbol in typed Map v1");
    }

    private static Object normalizeKey(KeyDomain domain, Object key) {
        Objects.requireNonNull(key, "Map key");
        return switch (domain) {
            case INT -> normalizeInteger(key);
            case STRING -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException(
                            "key is incompatible with Map<int/String/Symbol> key domain STRING");
                }
                validateStringKey(text);
                yield text;
            }
            case SYMBOL -> {
                if (!(key instanceof OresSymbol symbol)) {
                    throw new IllegalArgumentException(
                            "key is incompatible with Map<int/String/Symbol> key domain SYMBOL");
                }
                yield symbol;
            }
        };
    }

    private static boolean isIntegral(Object key) {
        return key instanceof Byte || key instanceof Short || key instanceof Integer
                || key instanceof Long || key instanceof BigInteger;
    }

    private static BigInteger normalizeInteger(Object key) {
        if (key instanceof BigInteger integer) {
            validateIntegerBytes(integer);
            return integer;
        }
        if (key instanceof Byte || key instanceof Short
                || key instanceof Integer || key instanceof Long) {
            return BigInteger.valueOf(((Number) key).longValue());
        }
        throw new IllegalArgumentException(
                "key is incompatible with Map<int,...> key domain");
    }

    private static void validateIntegerBytes(BigInteger integer) {
        if (integer.toByteArray().length > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("Map integer key exceeds byte limit");
        }
    }

    private static void validateStringKey(String text) {
        if (text.length() > MAX_KEY_BYTES) {
            throw new IllegalArgumentException("Map String key exceeds byte limit");
        }
        try {
            ByteBuffer bytes = StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            if (bytes.remaining() > MAX_KEY_BYTES) {
                throw new IllegalArgumentException("Map String key exceeds byte limit");
            }
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException(
                    "Map String key must be valid Unicode", invalid);
        }
    }

    private static int encodedKeyBytes(KeyDomain domain, Object normalized) {
        return switch (domain) {
            case INT -> {
                BigInteger integer = (BigInteger) normalized;
                int bytes = integer.toByteArray().length;
                if (bytes > MAX_KEY_BYTES) {
                    throw new IllegalArgumentException("Map integer key exceeds byte limit");
                }
                yield Math.addExact(6, bytes);
            }
            case STRING -> {
                byte[] bytes = ((String) normalized).getBytes(StandardCharsets.UTF_8);
                yield Math.addExact(6, bytes.length);
            }
            case SYMBOL -> {
                OresSymbol symbol = (OresSymbol) normalized;
                byte[] bytes = symbol.name().getBytes(StandardCharsets.UTF_8);
                // tag + kind + bounded name length + payload
                yield Math.addExact(7, bytes.length);
            }
        };
    }

    @Override
    public String toString() {
        return "Map" + entrySnapshot();
    }
}
