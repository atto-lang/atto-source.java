package dev.oreslang.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dependency-free core JSON codec.
 *
 * <p>This class owns only JSON text/tree mechanics. Static Oreslang type
 * descriptors and class construction stay in the compiler/evaluator so JSON
 * cannot gain host-reflection authority.</p>
 */
public final class CoreJson {
    private static final int MAX_INPUT_CHARS = 8 * 1024 * 1024;
    private static final int MAX_OUTPUT_CHARS = 16 * 1024 * 1024;
    private static final int MAX_DEPTH = 128;
    private static final int MAX_NODES = 1_000_000;
    private static final int MAX_CONTAINER_ENTRIES = 200_000;

    private CoreJson() { }

    public static Object parse(String source) {
        if (source == null) throw new IllegalArgumentException("JSON input cannot be null");
        if (source.length() > MAX_INPUT_CHARS) {
            throw new IllegalArgumentException(
                    "JSON input exceeds " + MAX_INPUT_CHARS + " UTF-16 code units");
        }
        Parser parser = new Parser(source);
        Object value = parser.value(0);
        parser.ws();
        if (!parser.end()) {
            throw parser.error("trailing data after JSON value");
        }
        return value;
    }

    public static String encode(Object value) {
        Encoder encoder = new Encoder();
        encoder.value(value, 0);
        return encoder.out.toString();
    }

    public static long exactLong(Object value, String where) {
        try {
            if (value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long) {
                return ((Number) value).longValue();
            }
            if (value instanceof BigInteger integer) return integer.longValueExact();
            if (value instanceof BigDecimal decimal) return decimal.longValueExact();
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(where + " is outside signed 64-bit integer range", overflow);
        }
        throw new IllegalArgumentException(where + " must be an integral JSON number");
    }

    public static double finiteDouble(Object value, String where) {
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(where + " must be a JSON number");
        }
        double result = number.doubleValue();
        if (!Double.isFinite(result)) {
            throw new IllegalArgumentException(where + " is outside finite floating-point range");
        }
        return result;
    }

    public static BigDecimal decimal(Object value, String where) {
        if (value instanceof BigDecimal decimal) return decimal;
        if (value instanceof BigInteger integer) return new BigDecimal(integer);
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            double d = ((Number) value).doubleValue();
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException(where + " must be finite");
            }
            return BigDecimal.valueOf(d);
        }
        throw new IllegalArgumentException(where + " must be a JSON number");
    }

    private static final class Parser {
        private final String source;
        private int at;
        private int nodes;

        private Parser(String source) {
            this.source = source;
        }

        private boolean end() { return at >= source.length(); }
        private char peek() {
            if (end()) throw error("unexpected end of JSON input");
            return source.charAt(at);
        }
        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(
                    "invalid JSON at UTF-16 offset " + at + ": " + message);
        }
        private void ws() {
            while (!end()) {
                char c = source.charAt(at);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') at++;
                else break;
            }
        }
        private void node(int depth) {
            if (depth > MAX_DEPTH) throw error("maximum nesting depth exceeded");
            if (++nodes > MAX_NODES) throw error("maximum JSON node count exceeded");
        }

        private Object value(int depth) {
            node(depth);
            ws();
            if (end()) throw error("expected JSON value");
            return switch (peek()) {
                case '"' -> string();
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", OresNull.INSTANCE);
                default -> number();
            };
        }

        private Object literal(String token, Object value) {
            if (!source.startsWith(token, at)) throw error("unexpected token");
            at += token.length();
            return value;
        }

        private Map<String, Object> object(int depth) {
            at++; // {
            ws();
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            if (!end() && source.charAt(at) == '}') {
                at++;
                return result;
            }
            int entries = 0;
            while (true) {
                ws();
                if (end() || source.charAt(at) != '"') {
                    throw error("object key must be a JSON string");
                }
                String key = string();
                ws();
                if (end() || source.charAt(at) != ':') throw error("expected ':' after object key");
                at++;
                Object value = value(depth);
                if (result.putIfAbsent(key, value) != null) {
                    throw error("duplicate object key '" + key + "'");
                }
                if (++entries > MAX_CONTAINER_ENTRIES) {
                    throw error("JSON object exceeds entry limit");
                }
                ws();
                if (end()) throw error("unterminated JSON object");
                char delimiter = source.charAt(at++);
                if (delimiter == '}') return result;
                if (delimiter != ',') throw error("expected ',' or '}' in object");
            }
        }

        private List<Object> array(int depth) {
            at++; // [
            ws();
            ArrayList<Object> result = new ArrayList<>();
            if (!end() && source.charAt(at) == ']') {
                at++;
                return result;
            }
            while (true) {
                result.add(value(depth));
                if (result.size() > MAX_CONTAINER_ENTRIES) {
                    throw error("JSON array exceeds entry limit");
                }
                ws();
                if (end()) throw error("unterminated JSON array");
                char delimiter = source.charAt(at++);
                if (delimiter == ']') return result;
                if (delimiter != ',') throw error("expected ',' or ']' in array");
            }
        }

        private String string() {
            if (source.charAt(at++) != '"') throw error("expected string");
            StringBuilder out = new StringBuilder();
            while (!end()) {
                char c = source.charAt(at++);
                if (c == '"') return out.toString();
                if (c < 0x20) throw error("unescaped control character in string");
                if (c != '\\') {
                    if (Character.isHighSurrogate(c)) {
                        if (end() || !Character.isLowSurrogate(source.charAt(at))) {
                            throw error("unpaired high surrogate in string");
                        }
                        out.append(c).append(source.charAt(at++));
                    } else if (Character.isLowSurrogate(c)) {
                        throw error("unpaired low surrogate in string");
                    } else {
                        out.append(c);
                    }
                    continue;
                }
                if (end()) throw error("unterminated escape sequence");
                char escape = source.charAt(at++);
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> appendUnicodeEscape(out);
                    default -> throw error("invalid string escape '\\" + escape + "'");
                }
            }
            throw error("unterminated JSON string");
        }

        private void appendUnicodeEscape(StringBuilder out) {
            char first = hexCodeUnit();
            if (Character.isHighSurrogate(first)) {
                if (at + 6 > source.length()
                        || source.charAt(at) != '\\'
                        || source.charAt(at + 1) != 'u') {
                    throw error("high surrogate escape must be followed by low surrogate escape");
                }
                at += 2;
                char second = hexCodeUnit();
                if (!Character.isLowSurrogate(second)) {
                    throw error("high surrogate escape is not followed by low surrogate");
                }
                out.append(first).append(second);
            } else if (Character.isLowSurrogate(first)) {
                throw error("unpaired low surrogate escape");
            } else {
                out.append(first);
            }
        }

        private char hexCodeUnit() {
            if (at + 4 > source.length()) throw error("short unicode escape");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                char c = source.charAt(at++);
                int digit = Character.digit(c, 16);
                if (digit < 0) throw error("non-hex digit in unicode escape");
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private Object number() {
            int start = at;
            if (!end() && source.charAt(at) == '-') at++;
            if (end()) throw error("incomplete JSON number");
            if (source.charAt(at) == '0') {
                at++;
                if (!end() && Character.isDigit(source.charAt(at))) {
                    throw error("leading zero in JSON number");
                }
            } else {
                if (!isDigit19(source.charAt(at))) throw error("expected JSON value");
                while (!end() && Character.isDigit(source.charAt(at))) at++;
            }
            boolean decimal = false;
            if (!end() && source.charAt(at) == '.') {
                decimal = true;
                at++;
                int digits = at;
                while (!end() && Character.isDigit(source.charAt(at))) at++;
                if (digits == at) throw error("fraction requires at least one digit");
            }
            if (!end() && (source.charAt(at) == 'e' || source.charAt(at) == 'E')) {
                decimal = true;
                at++;
                if (!end() && (source.charAt(at) == '+' || source.charAt(at) == '-')) at++;
                int digits = at;
                while (!end() && Character.isDigit(source.charAt(at))) at++;
                if (digits == at) throw error("exponent requires at least one digit");
            }
            String token = source.substring(start, at);
            try {
                if (!decimal) {
                    try {
                        return Long.parseLong(token);
                    } catch (NumberFormatException large) {
                        return new BigInteger(token);
                    }
                }
                return new BigDecimal(token);
            } catch (NumberFormatException malformed) {
                throw error("invalid JSON number");
            }
        }

        private static boolean isDigit19(char c) {
            return c >= '1' && c <= '9';
        }
    }

    private static final class Encoder {
        private final StringBuilder out = new StringBuilder();
        private final IdentityHashMap<Object, Boolean> visiting = new IdentityHashMap<>();
        private int nodes;

        private void append(char c) {
            if (out.length() >= MAX_OUTPUT_CHARS) {
                throw new IllegalArgumentException("JSON output exceeds configured size limit");
            }
            out.append(c);
        }
        private void append(String s) {
            if ((long) out.length() + s.length() > MAX_OUTPUT_CHARS) {
                throw new IllegalArgumentException("JSON output exceeds configured size limit");
            }
            out.append(s);
        }
        private void node(int depth) {
            if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON encode maximum nesting depth exceeded");
            if (++nodes > MAX_NODES) throw new IllegalArgumentException("JSON encode maximum node count exceeded");
        }

        private void value(Object value, int depth) {
            node(depth);
            if (value == null || value == OresNull.INSTANCE) {
                append("null");
                return;
            }
            if (value instanceof String string) {
                string(string);
                return;
            }
            if (value instanceof Boolean bool) {
                append(bool ? "true" : "false");
                return;
            }
            if (value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long
                    || value instanceof BigInteger) {
                append(value.toString());
                return;
            }
            if (value instanceof BigDecimal decimal) {
                append(decimal.toPlainString());
                return;
            }
            if (value instanceof Float || value instanceof Double) {
                double number = ((Number) value).doubleValue();
                if (!Double.isFinite(number)) {
                    throw new IllegalArgumentException("JSON cannot encode NaN or Infinity");
                }
                append(Double.toString(number));
                return;
            }
            if (value instanceof Map<?, ?> map) {
                composite(value);
                try {
                    append('{');
                    boolean first = true;
                    int count = 0;
                    for (Map.Entry<?, ?> entry : map.entrySet()) {
                        if (!(entry.getKey() instanceof String key)) {
                            throw new IllegalArgumentException(
                                    "JSON object maps require String keys");
                        }
                        if (++count > MAX_CONTAINER_ENTRIES) {
                            throw new IllegalArgumentException("JSON object exceeds entry limit");
                        }
                        if (!first) append(',');
                        first = false;
                        string(key);
                        append(':');
                        value(entry.getValue(), depth + 1);
                    }
                    append('}');
                } finally {
                    visiting.remove(value);
                }
                return;
            }
            if (value instanceof List<?> list) {
                composite(value);
                try {
                    if (list.size() > MAX_CONTAINER_ENTRIES) {
                        throw new IllegalArgumentException("JSON array exceeds entry limit");
                    }
                    append('[');
                    for (int i = 0; i < list.size(); i++) {
                        if (i != 0) append(',');
                        value(list.get(i), depth + 1);
                    }
                    append(']');
                } finally {
                    visiting.remove(value);
                }
                return;
            }
            if (value instanceof Object[] array) {
                composite(value);
                try {
                    if (array.length > MAX_CONTAINER_ENTRIES) {
                        throw new IllegalArgumentException("JSON array exceeds entry limit");
                    }
                    append('[');
                    for (int i = 0; i < array.length; i++) {
                        if (i != 0) append(',');
                        value(array[i], depth + 1);
                    }
                    append(']');
                } finally {
                    visiting.remove(value);
                }
                return;
            }
            throw new IllegalArgumentException(
                    "value of runtime type " + value.getClass().getName()
                            + " is not JSON encodable");
        }

        private void composite(Object value) {
            if (visiting.put(value, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("JSON cannot encode cyclic object graphs");
            }
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
                            append(String.format("\\u%04x", (int) c));
                        } else if (Character.isHighSurrogate(c)) {
                            if (i + 1 >= value.length()
                                    || !Character.isLowSurrogate(value.charAt(i + 1))) {
                                throw new IllegalArgumentException(
                                        "JSON string contains unpaired high surrogate");
                            }
                            append(c);
                            append(value.charAt(++i));
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
    }
}
