package dev.oreslang.runtime;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Strict allocator-backed JSON text parser for alloc/json.
 *
 * <p>This is not part of allocator-free core. It constructs owned
 * {@link OresJsonValue} trees and canonical {@link OresKeyedMap} objects.
 * Parsing is dependency-free, bounded, duplicate-key rejecting, and does not
 * use host reflection or generic host Map values as the guest representation.</p>
 */
public final class AllocJsonText {
    private static final int MAX_INPUT_CHARS = 8 * 1024 * 1024;
    private static final int MAX_DEPTH = 128;
    private static final int MAX_NODES = OresJsonValue.MAX_GRAPH_NODES;
    private static final int MAX_CONTAINER_ENTRIES =
            Math.min(OresKeyedMap.MAX_ENTRIES, OresJsonValue.MAX_ARRAY_ITEMS);
    private static final int MAX_NUMBER_CHARS = OresJsonValue.MAX_NUMBER_PRECISION;

    private AllocJsonText() { }

    public static OresJsonValue parse(String source) {
        if (source == null) {
            throw new IllegalArgumentException("JSON input cannot be null");
        }
        if (source.length() > MAX_INPUT_CHARS) {
            throw new IllegalArgumentException(
                    "JSON input exceeds " + MAX_INPUT_CHARS + " UTF-16 code units");
        }

        Parser parser = new Parser(source);
        OresJsonValue value = parser.value(0);
        parser.whitespace();
        if (!parser.end()) {
            throw parser.error("trailing data after JSON value");
        }
        OresJsonValue.validateCompleteTree(value);
        return value;
    }

    private static final class Parser {
        private final String source;
        private int at;
        private int nodes;

        private Parser(String source) {
            this.source = source;
        }

        private boolean end() {
            return at >= source.length();
        }

        private char peek() {
            if (end()) throw error("unexpected end of JSON input");
            return source.charAt(at);
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(
                    "invalid JSON at UTF-16 offset " + at + ": " + message);
        }

        private void whitespace() {
            while (!end()) {
                char c = source.charAt(at);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    at++;
                } else {
                    break;
                }
            }
        }

        private void countNode(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("maximum nesting depth exceeded");
            }
            if (++nodes > MAX_NODES) {
                throw error("maximum JSON node count exceeded");
            }
        }

        private OresJsonValue value(int depth) {
            countNode(depth);
            whitespace();
            if (end()) throw error("expected JSON value");

            return switch (peek()) {
                case '"' -> OresJsonValue.of(string());
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case 't' -> literal("true", OresJsonValue.of(true));
                case 'f' -> literal("false", OresJsonValue.of(false));
                case 'n' -> literal("null", OresJsonValue.nullValue());
                default -> number();
            };
        }

        private OresJsonValue literal(String token, OresJsonValue value) {
            if (!source.startsWith(token, at)) {
                throw error("unexpected token");
            }
            at += token.length();
            return value;
        }

        private OresJsonValue object(int depth) {
            at++; // {
            whitespace();

            OresKeyedMap<String, OresJsonValue> fields =
                    OresJsonValue.objectMap();
            Set<String> seen = new LinkedHashSet<>();
            if (!end() && source.charAt(at) == '}') {
                at++;
                return OresJsonValue.object(fields);
            }

            int entries = 0;
            while (true) {
                whitespace();
                if (end() || source.charAt(at) != '"') {
                    throw error("object key must be a JSON string");
                }

                String key = string();
                if (!seen.add(key)) {
                    throw error("duplicate object key");
                }

                whitespace();
                if (end() || source.charAt(at) != ':') {
                    throw error("expected ':' after object key");
                }
                at++;

                OresJsonValue member = value(depth);
                fields.put(key, member);
                if (++entries > MAX_CONTAINER_ENTRIES) {
                    throw error("JSON object exceeds entry limit");
                }

                whitespace();
                if (end()) throw error("unterminated JSON object");
                char delimiter = source.charAt(at++);
                if (delimiter == '}') {
                    return OresJsonValue.object(fields);
                }
                if (delimiter != ',') {
                    throw error("expected ',' or '}' in object");
                }
            }
        }

        private OresJsonValue array(int depth) {
            at++; // [
            whitespace();

            List<OresJsonValue> values = new ArrayList<>();
            if (!end() && source.charAt(at) == ']') {
                at++;
                return OresJsonValue.array(values);
            }

            while (true) {
                values.add(value(depth));
                if (values.size() > MAX_CONTAINER_ENTRIES) {
                    throw error("JSON array exceeds entry limit");
                }

                whitespace();
                if (end()) throw error("unterminated JSON array");
                char delimiter = source.charAt(at++);
                if (delimiter == ']') {
                    return OresJsonValue.array(values);
                }
                if (delimiter != ',') {
                    throw error("expected ',' or ']' in array");
                }
            }
        }

        private String string() {
            if (source.charAt(at++) != '"') {
                throw error("expected string");
            }

            StringBuilder out = new StringBuilder();
            while (!end()) {
                char c = source.charAt(at++);
                if (c == '"') return out.toString();
                if (c < 0x20) {
                    throw error("unescaped control character in string");
                }

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
                    default -> throw error("invalid string escape");
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
                    throw error(
                            "high surrogate escape must be followed by low surrogate escape");
                }
                at += 2;
                char second = hexCodeUnit();
                if (!Character.isLowSurrogate(second)) {
                    throw error(
                            "high surrogate escape is not followed by low surrogate");
                }
                out.append(first).append(second);
            } else if (Character.isLowSurrogate(first)) {
                throw error("unpaired low surrogate escape");
            } else {
                out.append(first);
            }
        }

        private char hexCodeUnit() {
            if (at + 4 > source.length()) {
                throw error("short unicode escape");
            }

            int value = 0;
            for (int i = 0; i < 4; i++) {
                char c = source.charAt(at++);
                int digit = Character.digit(c, 16);
                if (digit < 0) {
                    throw error("non-hex digit in unicode escape");
                }
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private OresJsonValue number() {
            int start = at;
            if (!end() && source.charAt(at) == '-') at++;
            if (end()) throw error("incomplete JSON number");

            if (source.charAt(at) == '0') {
                at++;
                if (!end() && Character.isDigit(source.charAt(at))) {
                    throw error("leading zero in JSON number");
                }
            } else {
                if (!digit19(source.charAt(at))) {
                    throw error("expected JSON value");
                }
                while (!end() && Character.isDigit(source.charAt(at))) at++;
            }

            if (!end() && source.charAt(at) == '.') {
                at++;
                int digits = at;
                while (!end() && Character.isDigit(source.charAt(at))) at++;
                if (digits == at) {
                    throw error("fraction requires at least one digit");
                }
            }

            if (!end() && (source.charAt(at) == 'e' || source.charAt(at) == 'E')) {
                at++;
                if (!end()
                        && (source.charAt(at) == '+' || source.charAt(at) == '-')) {
                    at++;
                }
                int digits = at;
                while (!end() && Character.isDigit(source.charAt(at))) at++;
                if (digits == at) {
                    throw error("exponent requires at least one digit");
                }
            }

            if (at - start > MAX_NUMBER_CHARS) {
                throw error("JSON number exceeds character limit");
            }

            String token = source.substring(start, at);
            try {
                return OresJsonValue.of(new BigDecimal(token));
            } catch (NumberFormatException malformed) {
                throw error("invalid JSON number");
            }
        }

        private static boolean digit19(char c) {
            return c >= '1' && c <= '9';
        }
    }
}
