package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A bounded, request-owned string slot table. Never an ambient ThreadLocal,
 * actor field, or a transferable capability.
 *
 * <p>Only NativeHttp.Exchange may expose operations after checking its current
 * owner and generation. There are no public references to this object. Values
 * are immutable strings; arbitrary guest objects/borrows cannot be inserted.
 * The table is cleared on physical HTTP exchange retirement, not thread exit.</p>
 */
final class HttpRequestLocals {
    static final int MAX_ENTRIES = 32;
    static final int MAX_VALUE_BYTES = 1024;
    static final int MAX_TOTAL_BYTES = 8192;
    private final Map<String, String> values = new LinkedHashMap<>();
    private int usedBytes;
    private boolean closed;

    private static void validateKey(String key) {
        Objects.requireNonNull(key, "request local key");
        if (key.length() < 1 || key.length() > 64
                || !key.matches("[a-zA-Z][a-zA-Z0-9_.-]*")) {
            throw new IllegalArgumentException("invalid request local key");
        }
    }

    synchronized void set(String key, String value) {
        validateKey(key);
        Objects.requireNonNull(value, "request local value");
        requireOpen();
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_VALUE_BYTES) {
            throw new IllegalArgumentException("request local value exceeds byte limit");
        }
        String previous = values.get(key);
        if (previous == null && values.size() >= MAX_ENTRIES) {
            throw new IllegalStateException("request local slot capacity exhausted");
        }
        int priorBytes = previous == null ? 0 : previous.getBytes(StandardCharsets.UTF_8).length;
        if (usedBytes - priorBytes + bytes > MAX_TOTAL_BYTES) {
            throw new IllegalStateException("request local byte budget exhausted");
        }
        values.put(key, value);
        usedBytes = usedBytes - priorBytes + bytes;
    }

    synchronized String get(String key) {
        validateKey(key);
        requireOpen();
        return values.getOrDefault(key, "");
    }

    synchronized boolean contains(String key) {
        validateKey(key);
        requireOpen();
        return values.containsKey(key);
    }

    synchronized int size() {
        return values.size();
    }

    synchronized void close() {
        closed = true;
        usedBytes = 0;
        values.clear();
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("HTTP request context retired");
    }
}
