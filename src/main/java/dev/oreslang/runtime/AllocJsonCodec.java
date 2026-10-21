package dev.oreslang.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Allocator-backed JSON text codec for the closed {@link OresJsonValue}
 * runtime carrier.
 *
 * <p>This is deliberately not allocator-free core. The encoder owns a
 * growable output buffer and therefore belongs to the alloc/json tier.</p>
 */
public final class AllocJsonCodec {
    private static final long MAX_OUTPUT_BYTES = 16L * 1024 * 1024;

    private AllocJsonCodec() { }

    /**
     * Encode a dynamic JSON object while holding the private JSON graph
     * stability guard across both complete-root validation and traversal.
     *
     * <p>A retained alias to a nested mutable object can otherwise change a
     * previously validated root between validation and encoding.</p>
     */
    public static String stringifyObject(
            OresKeyedMap<String, OresJsonValue> object) {
        Objects.requireNonNull(object, "JSON object");
        if (object.keyDomain() != OresKeyedMap.KeyDomain.STRING
                || object.valueDomain() != OresKeyedMap.ValueDomain.JSON) {
            throw new IllegalArgumentException(
                    "Json.stringify requires Map<String, JsonValue>");
        }
        return OresKeyedMap.withJsonGraphLock(() -> {
            OresJsonValue root = OresJsonValue.object(object);
            Encoder encoder = new Encoder();
            encoder.value(root, 0);
            return encoder.out.toString();
        });
    }

    private static final class Encoder {
        private final StringBuilder out = new StringBuilder();
        private int nodes;
        private long outputUtf8Bytes;

        private void node(int depth) {
            if (depth > OresJsonValue.MAX_GRAPH_DEPTH) {
                throw new IllegalArgumentException(
                        "JSON encode maximum nesting depth exceeded");
            }
            if (++nodes > OresJsonValue.MAX_GRAPH_NODES) {
                throw new IllegalArgumentException(
                        "JSON encode maximum node count exceeded");
            }
        }

        private void reserveUtf8Bytes(long additional) {
            if (additional < 0
                    || outputUtf8Bytes > MAX_OUTPUT_BYTES - additional) {
                throw new IllegalArgumentException(
                        "JSON output exceeds configured UTF-8 byte limit");
            }
            outputUtf8Bytes += additional;
        }

        private void append(char value) {
            if (Character.isSurrogate(value)) {
                throw new IllegalArgumentException(
                        "internal JSON encoder cannot append a lone surrogate");
            }
            reserveUtf8Bytes(
                    value <= 0x7f ? 1
                            : value <= 0x7ff ? 2
                            : 3);
            out.append(value);
        }

        private void appendSurrogatePair(char high, char low) {
            if (!Character.isHighSurrogate(high)
                    || !Character.isLowSurrogate(low)) {
                throw new IllegalArgumentException(
                        "internal JSON encoder received an invalid surrogate pair");
            }
            reserveUtf8Bytes(4);
            out.append(high).append(low);
        }

        private void append(String value) {
            Objects.requireNonNull(value, "JSON output fragment");
            long bytes = 0;
            for (int i = 0; i < value.length(); i++) {
                char current = value.charAt(i);
                if (current <= 0x7f) {
                    bytes += 1;
                } else if (current <= 0x7ff) {
                    bytes += 2;
                } else if (Character.isHighSurrogate(current)) {
                    if (i + 1 >= value.length()
                            || !Character.isLowSurrogate(value.charAt(i + 1))) {
                        throw new IllegalArgumentException(
                                "JSON output fragment contains unpaired high surrogate");
                    }
                    bytes += 4;
                    i++;
                } else if (Character.isLowSurrogate(current)) {
                    throw new IllegalArgumentException(
                            "JSON output fragment contains unpaired low surrogate");
                } else {
                    bytes += 3;
                }
                if (bytes > MAX_OUTPUT_BYTES) {
                    throw new IllegalArgumentException(
                            "JSON output exceeds configured UTF-8 byte limit");
                }
            }
            reserveUtf8Bytes(bytes);
            out.append(value);
        }

        private void value(OresJsonValue value, int depth) {
            Objects.requireNonNull(value, "JSON tree value");
            node(depth);

            if (value instanceof OresJsonValue.NullValue) {
                append("null");
                return;
            }
            if (value instanceof OresJsonValue.BoolValue bool) {
                append(bool.value() ? "true" : "false");
                return;
            }
            if (value instanceof OresJsonValue.NumberValue number) {
                // BigDecimal.toString is exact and bounded by the numeric
                // precision/scale ceilings enforced by OresJsonValue.
                append(number.value().toString());
                return;
            }
            if (value instanceof OresJsonValue.StringValue string) {
                string(string.value());
                return;
            }
            if (value instanceof OresJsonValue.ArrayValue array) {
                List<OresJsonValue> values = array.values();
                if (values.size() > OresJsonValue.MAX_ARRAY_ITEMS) {
                    throw new IllegalArgumentException(
                            "JSON array exceeds runtime item limit");
                }
                append('[');
                for (int i = 0; i < values.size(); i++) {
                    if (i != 0) append(',');
                    value(values.get(i), depth + 1);
                }
                append(']');
                return;
            }
            if (value instanceof OresJsonValue.ObjectValue object) {
                OresKeyedMap<String, OresJsonValue> fields = object.fields();
                List<Map.Entry<String, OresJsonValue>> entries =
                        fields.entrySnapshot();
                append('{');
                for (int i = 0; i < entries.size(); i++) {
                    if (i != 0) append(',');
                    Map.Entry<String, OresJsonValue> entry = entries.get(i);
                    string(entry.getKey());
                    append(':');
                    value(entry.getValue(), depth + 1);
                }
                append('}');
                return;
            }

            throw new IllegalStateException(
                    "unknown closed JsonValue runtime variant "
                            + value.getClass().getName());
        }

        private void string(String value) {
            append('"');
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> append("\\\"");
                    case '\\' -> append("\\\\");
                    case '\b' -> append("\\b");
                    case '\f' -> append("\\f");
                    case '\n' -> append("\\n");
                    case '\r' -> append("\\r");
                    case '\t' -> append("\\t");
                    default -> {
                        if (c < 0x20) {
                            append("\\u00");
                            append(hex((c >>> 4) & 0xf));
                            append(hex(c & 0xf));
                        } else if (Character.isHighSurrogate(c)) {
                            if (i + 1 >= value.length()
                                    || !Character.isLowSurrogate(
                                            value.charAt(i + 1))) {
                                throw new IllegalArgumentException(
                                        "JSON string contains unpaired high surrogate");
                            }
                            appendSurrogatePair(
                                    c,
                                    value.charAt(++i));
                        } else if (Character.isLowSurrogate(c)) {
                            throw new IllegalArgumentException(
                                    "JSON string contains unpaired low surrogate");
                        } else {
                            append(c);
                        }
                    }
                }
            }
            append('"');
        }

        private static char hex(int nibble) {
            return "0123456789abcdef".charAt(nibble & 0xf);
        }
    }
}
