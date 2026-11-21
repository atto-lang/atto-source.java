package dev.oreslang.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Strict bounded JSON ingress for the signed AOT artifact manifest V1.
 *
 * <p>This is intentionally a closed parser for the current manifest schema,
 * not a permissive general-purpose JSON binding layer. It rejects duplicate
 * decoded object keys, unknown/missing schema fields, malformed UTF-8,
 * unpaired Unicode surrogates, non-JSON numbers, non-finite IEEE-754 numeric
 * values, excessive nesting, oversized input and trailing JSON.</p>
 */
public final class ActorArtifactManifestJsonV1 {
    public static final int MAX_MANIFEST_BYTES = 64 * 1024;
    public static final int MAX_MANIFEST_DEPTH = 64;

    private ActorArtifactManifestJsonV1() { }

    public static ActorArtifactAdmission.Manifest parse(byte[] rawUtf8) {
        rawUtf8 = Objects.requireNonNull(rawUtf8, "rawUtf8");
        if (rawUtf8.length > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException(
                    "AOT manifest must be at most 64 KiB");
        }

        // Own one immutable ingress snapshot before UTF-8 validation/parsing.
        byte[] admitted = rawUtf8.clone();
        String text = decodeUtf8(admitted);
        Object root = new StrictJsonParser(text).parseDocument();
        Map<String, Object> manifest = object(root, "AOT manifest");
        requireExactKeys(
                manifest,
                Set.of(
                        "schema_version",
                        "compiler",
                        "source_sha256",
                        "expanded_sha256",
                        "generated_ast_sha256",
                        "artifact",
                        "policy",
                        "provenance"),
                "AOT manifest");

        String schemaVersion = string(manifest, "schema_version", "AOT manifest");
        Map<String, Object> compilerMap =
                object(required(manifest, "compiler", "AOT manifest"), "compiler");
        requireExactKeys(
                compilerMap,
                Set.of("backend", "version", "git_commit"),
                "compiler");
        ActorArtifactAdmission.Backend backend = switch (
                string(compilerMap, "backend", "compiler")) {
            case "java-graalvm" -> ActorArtifactAdmission.Backend.JAVA_GRAALVM;
            case "llvm" -> ActorArtifactAdmission.Backend.LLVM;
            default -> throw new IllegalArgumentException(
                    "unknown compiler backend in AOT manifest");
        };
        ActorArtifactAdmission.Compiler compiler =
                new ActorArtifactAdmission.Compiler(
                        backend,
                        string(compilerMap, "version", "compiler"),
                        string(compilerMap, "git_commit", "compiler"));

        Map<String, Object> artifactMap =
                object(required(manifest, "artifact", "AOT manifest"), "artifact");
        requireExactKeys(
                artifactMap,
                Set.of(
                        "file_sha256",
                        "size_bytes",
                        "format",
                        "target_triple",
                        "abi"),
                "artifact");
        ActorArtifactAdmission.Format format = switch (
                string(artifactMap, "format", "artifact")) {
            case "native-executable" ->
                    ActorArtifactAdmission.Format.NATIVE_EXECUTABLE;
            case "static-library" ->
                    ActorArtifactAdmission.Format.STATIC_LIBRARY;
            case "wasm-module" ->
                    ActorArtifactAdmission.Format.WASM_MODULE;
            default -> throw new IllegalArgumentException(
                    "unknown artifact format in AOT manifest");
        };
        ActorArtifactAdmission.Artifact artifact =
                new ActorArtifactAdmission.Artifact(
                        string(artifactMap, "file_sha256", "artifact"),
                        safeInteger(artifactMap, "size_bytes", "artifact"),
                        format,
                        string(artifactMap, "target_triple", "artifact"),
                        string(artifactMap, "abi", "artifact"));

        Map<String, Object> policyMap =
                object(required(manifest, "policy", "AOT manifest"), "policy");
        requireExactKeys(
                policyMap,
                Set.of(
                        "runtime_macro_expansion",
                        "runtime_annotation_generation",
                        "runtime_guest_compilation",
                        "required_capabilities"),
                "policy");
        List<Object> rawCapabilities = array(
                required(policyMap, "required_capabilities", "policy"),
                "policy.required_capabilities");
        ArrayList<String> capabilities =
                new ArrayList<>(rawCapabilities.size());
        for (int i = 0; i < rawCapabilities.size(); i++) {
            Object value = rawCapabilities.get(i);
            if (!(value instanceof String capability)) {
                throw new IllegalArgumentException(
                        "policy.required_capabilities[" + i
                                + "] must be a string");
            }
            capabilities.add(capability);
        }
        ActorArtifactAdmission.Policy policy =
                new ActorArtifactAdmission.Policy(
                        bool(policyMap, "runtime_macro_expansion", "policy"),
                        bool(policyMap, "runtime_annotation_generation", "policy"),
                        bool(policyMap, "runtime_guest_compilation", "policy"),
                        capabilities);

        Map<String, Object> provenanceMap =
                object(required(manifest, "provenance", "AOT manifest"), "provenance");
        requireExactKeys(
                provenanceMap,
                Set.of("builder_id", "signature"),
                "provenance");
        Map<String, Object> signatureMap =
                object(required(provenanceMap, "signature", "provenance"),
                        "provenance.signature");
        requireExactKeys(
                signatureMap,
                Set.of("algorithm", "key_id", "value_base64"),
                "provenance.signature");
        ActorArtifactAdmission.Provenance provenance =
                new ActorArtifactAdmission.Provenance(
                        string(provenanceMap, "builder_id", "provenance"),
                        new ActorArtifactAdmission.ManifestSignature(
                                string(
                                        signatureMap,
                                        "algorithm",
                                        "provenance.signature"),
                                string(
                                        signatureMap,
                                        "key_id",
                                        "provenance.signature"),
                                string(
                                        signatureMap,
                                        "value_base64",
                                        "provenance.signature")));

        return new ActorArtifactAdmission.Manifest(
                schemaVersion,
                compiler,
                string(manifest, "source_sha256", "AOT manifest"),
                string(manifest, "expanded_sha256", "AOT manifest"),
                string(manifest, "generated_ast_sha256", "AOT manifest"),
                artifact,
                policy,
                provenance);
    }

    /**
     * Package-private primitive shared by the V2 admission path. This does NOT
     * weaken the public V1 parser: schema-specific V1 decoding is unchanged.
     * One defensive raw-byte snapshot is strict-UTF8 decoded, then tokenized
     * with the existing duplicate-decoded-key and surrogate rejection rules.
     * JSON numbers are converted losslessly to BigDecimal for V2 size policy.
     */
    static Object parseStrictJsonValue(byte[] rawUtf8) {
        Objects.requireNonNull(rawUtf8, "rawUtf8");
        if (rawUtf8.length == 0 || rawUtf8.length > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException("V2 JSON input must be 1..64 KiB");
        }
        String decoded = decodeUtf8(rawUtf8.clone());
        Object parsed = new StrictJsonParser(decoded).parseDocument();
        return exposeExactNumbers(parsed);
    }

    private static Object exposeExactNumbers(Object parsed) {
        if (parsed instanceof JsonNumber n) return n.value();
        if (parsed instanceof Map<?, ?> fields) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : fields.entrySet()) {
                out.put((String) field.getKey(), exposeExactNumbers(field.getValue()));
            }
            return java.util.Collections.unmodifiableMap(out);
        }
        if (parsed instanceof List<?> elements) {
            List<Object> out = new ArrayList<>(elements.size());
            for (Object element : elements) out.add(exposeExactNumbers(element));
            return java.util.Collections.unmodifiableList(out);
        }
        return parsed;
    }

    private static String decodeUtf8(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException(
                    "AOT manifest must be valid UTF-8", invalid);
        }
    }

    private static Object required(
            Map<String, Object> object,
            String name,
            String context) {
        if (!object.containsKey(name)) {
            throw new IllegalArgumentException(
                    context + " is missing required field '" + name + "'");
        }
        return object.get(name);
    }

    private static String string(
            Map<String, Object> object,
            String name,
            String context) {
        Object value = required(object, name, context);
        if (!(value instanceof String string)) {
            throw new IllegalArgumentException(
                    context + "." + name + " must be a string");
        }
        return string;
    }

    private static boolean bool(
            Map<String, Object> object,
            String name,
            String context) {
        Object value = required(object, name, context);
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException(
                    context + "." + name + " must be a boolean");
        }
        return bool;
    }

    private static long safeInteger(
            Map<String, Object> object,
            String name,
            String context) {
        Object value = required(object, name, context);
        if (!(value instanceof JsonNumber number)) {
            throw new IllegalArgumentException(
                    context + "." + name + " must be an integer");
        }
        final BigInteger integer;
        try {
            integer = number.value().toBigIntegerExact();
        } catch (ArithmeticException fractional) {
            throw new IllegalArgumentException(
                    context + "." + name + " must be an integer",
                    fractional);
        }
        if (integer.signum() < 0
                || integer.compareTo(
                        BigInteger.valueOf(9_007_199_254_740_991L)) > 0) {
            throw new IllegalArgumentException(
                    context + "." + name
                            + " must be a non-negative JSON-safe integer");
        }
        try {
            return integer.longValueExact();
        } catch (ArithmeticException impossible) {
            throw new IllegalArgumentException(
                    context + "." + name + " is outside long range",
                    impossible);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String context) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(context + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String context) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException(context + " must be an array");
        }
        return (List<Object>) list;
    }

    private static void requireExactKeys(
            Map<String, Object> object,
            Set<String> expected,
            String context) {
        for (String key : object.keySet()) {
            if (!expected.contains(key)) {
                throw new IllegalArgumentException(
                        context + " contains unknown field '" + key + "'");
            }
        }
        for (String key : expected) {
            if (!object.containsKey(key)) {
                throw new IllegalArgumentException(
                        context + " is missing required field '" + key + "'");
            }
        }
    }

    private record JsonNumber(BigDecimal value) { }

    private static final class StrictJsonParser {
        private final String text;
        private int at;

        private StrictJsonParser(String text) {
            this.text = Objects.requireNonNull(text, "text");
        }

        private Object parseDocument() {
            skipWhitespace();
            Object value = value(0);
            skipWhitespace();
            if (at != text.length()) {
                fail("trailing JSON after AOT manifest");
            }
            return value;
        }

        private Object value(int depth) {
            if (depth > MAX_MANIFEST_DEPTH) {
                fail("AOT manifest nesting depth exceeded");
            }
            skipWhitespace();
            if (at >= text.length()) fail("unexpected end of JSON");

            char ch = text.charAt(at);
            return switch (ch) {
                case '{' -> object(depth);
                case '[' -> array(depth);
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (ch == '-' || isDigit(ch)) yield number();
                    fail("invalid JSON value");
                    yield null; // unreachable
                }
            };
        }

        private Map<String, Object> object(int depth) {
            expect('{');
            skipWhitespace();
            LinkedHashMap<String, Object> result = new LinkedHashMap<>();
            if (take('}')) return result;

            for (;;) {
                skipWhitespace();
                if (at >= text.length() || text.charAt(at) != '"') {
                    fail("JSON object key must be a string");
                }
                String key = string();
                if (result.containsKey(key)) {
                    fail("duplicate manifest JSON key '" + key + "'");
                }
                skipWhitespace();
                expect(':');
                Object child = value(depth + 1);
                result.put(key, child);
                skipWhitespace();
                if (take('}')) return result;
                expect(',');
            }
        }

        private List<Object> array(int depth) {
            expect('[');
            skipWhitespace();
            ArrayList<Object> result = new ArrayList<>();
            if (take(']')) return result;

            for (;;) {
                result.add(value(depth + 1));
                skipWhitespace();
                if (take(']')) return result;
                expect(',');
            }
        }

        private String string() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (at < text.length()) {
                char ch = text.charAt(at++);
                if (ch == '"') return out.toString();
                if (ch == '\\') {
                    if (at >= text.length()) fail("truncated JSON escape");
                    char escape = text.charAt(at++);
                    switch (escape) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> appendEscapedUnicode(out);
                        default -> fail("invalid JSON string escape");
                    }
                    continue;
                }
                if (ch < 0x20) fail("unescaped control character in JSON string");
                if (Character.isHighSurrogate(ch)) {
                    if (at >= text.length()
                            || !Character.isLowSurrogate(text.charAt(at))) {
                        fail("unpaired Unicode surrogate in AOT manifest");
                    }
                    out.append(ch).append(text.charAt(at++));
                    continue;
                }
                if (Character.isLowSurrogate(ch)) {
                    fail("unpaired Unicode surrogate in AOT manifest");
                }
                out.append(ch);
            }
            fail("unterminated JSON string");
            return null; // unreachable
        }

        private void appendEscapedUnicode(StringBuilder out) {
            char first = hex4();
            if (Character.isHighSurrogate(first)) {
                if (at + 1 >= text.length()
                        || text.charAt(at) != '\\'
                        || text.charAt(at + 1) != 'u') {
                    fail("unpaired Unicode surrogate in AOT manifest");
                }
                at += 2;
                char second = hex4();
                if (!Character.isLowSurrogate(second)) {
                    fail("unpaired Unicode surrogate in AOT manifest");
                }
                out.append(first).append(second);
                return;
            }
            if (Character.isLowSurrogate(first)) {
                fail("unpaired Unicode surrogate in AOT manifest");
            }
            out.append(first);
        }

        private char hex4() {
            if (at + 4 > text.length()) fail("truncated Unicode escape");
            int value = 0;
            for (int i = 0; i < 4; i++) {
                int digit = Character.digit(text.charAt(at++), 16);
                if (digit < 0) fail("invalid Unicode escape");
                value = (value << 4) | digit;
            }
            return (char) value;
        }

        private JsonNumber number() {
            int start = at;
            take('-');
            if (at >= text.length()) fail("truncated JSON number");

            if (take('0')) {
                if (at < text.length() && isDigit(text.charAt(at))) {
                    fail("JSON number has a leading zero");
                }
            } else {
                if (!isOneToNine(peek())) fail("invalid JSON number");
                at++;
                while (at < text.length() && isDigit(text.charAt(at))) at++;
            }

            if (take('.')) {
                if (at >= text.length() || !isDigit(text.charAt(at))) {
                    fail("fractional JSON number requires digits");
                }
                while (at < text.length() && isDigit(text.charAt(at))) at++;
            }

            if (at < text.length()
                    && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
                at++;
                if (at < text.length()
                        && (text.charAt(at) == '+' || text.charAt(at) == '-')) {
                    at++;
                }
                if (at >= text.length() || !isDigit(text.charAt(at))) {
                    fail("JSON exponent requires digits");
                }
                while (at < text.length() && isDigit(text.charAt(at))) at++;
            }

            String token = text.substring(start, at);
            final BigDecimal value;
            try {
                value = new BigDecimal(token);
            } catch (NumberFormatException invalid) {
                fail("invalid JSON number");
                return null; // unreachable
            }
            double ieee754 = value.doubleValue();
            if (!Double.isFinite(ieee754)) {
                fail("nonfinite JSON number in AOT manifest");
            }
            return new JsonNumber(value);
        }

        private Object literal(String token, Object value) {
            if (!text.startsWith(token, at)) fail("invalid JSON literal");
            at += token.length();
            return value;
        }

        private void skipWhitespace() {
            while (at < text.length()) {
                char ch = text.charAt(at);
                if (ch == ' ' || ch == '\t' || ch == '\r' || ch == '\n') {
                    at++;
                } else {
                    return;
                }
            }
        }

        private void expect(char expected) {
            if (!take(expected)) fail("expected '" + expected + "'");
        }

        private boolean take(char expected) {
            if (at < text.length() && text.charAt(at) == expected) {
                at++;
                return true;
            }
            return false;
        }

        private char peek() {
            return at < text.length() ? text.charAt(at) : '\0';
        }

        private static boolean isDigit(char ch) {
            return ch >= '0' && ch <= '9';
        }

        private static boolean isOneToNine(char ch) {
            return ch >= '1' && ch <= '9';
        }

        private void fail(String message) {
            throw new IllegalArgumentException(
                    message + " at JSON character " + at);
        }
    }
}
