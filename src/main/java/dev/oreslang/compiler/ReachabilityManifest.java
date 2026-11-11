package dev.oreslang.compiler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic backend-neutral description of semantic reachability.
 *
 * Emitted after semantic reachability and before target-specific lowering.
 */
public final class ReachabilityManifest {
    public static final String FORMAT = "ores-reachability/1";

    private static final Comparator<String> CODE_POINT_ORDER =
            ReachabilityManifest::compareCodePointOrder;
    private static final int MAX_ITEMS = 1_000_000;

    private final String buildKind;
    private final List<String> roots;
    private final List<String> retainedSymbols;
    private final List<String> removedSymbols;
    private final Map<String, Object> compileTimeConstants;

    private ReachabilityManifest(
            String buildKind,
            List<String> roots,
            List<String> retainedSymbols,
            List<String> removedSymbols,
            Map<String, Object> compileTimeConstants) {
        this.buildKind = buildKind;
        this.roots = List.copyOf(roots);
        this.retainedSymbols = List.copyOf(retainedSymbols);
        this.removedSymbols = List.copyOf(removedSymbols);
        this.compileTimeConstants = Collections.unmodifiableMap(
                new LinkedHashMap<>(compileTimeConstants));
    }

    public static ReachabilityManifest from(
            TreeShaker.Result result) {
        if (result == null) {
            throw new IllegalArgumentException(
                    "reachability result cannot be null");
        }

        requireCardinality("retained symbols", result.retainedSymbols().size());
        requireCardinality("removed symbols", result.removedSymbols().size());
        requireCardinality("root symbols", result.rootSymbols().size());
        requireCardinality(
                "compile-time constants",
                result.compileTimeConstants().size());
        requireCardinality("semantic symbols", result.semanticSymbols().size());

        List<String> retained = sorted(
                "retained symbol", result.retainedSymbols());
        List<String> removed = sorted(
                "removed symbol", result.removedSymbols());
        List<String> roots = sorted(
                "root symbol", result.rootSymbols());

        java.util.LinkedHashSet<String> classified =
                new java.util.LinkedHashSet<>(result.retainedSymbols());
        classified.addAll(result.removedSymbols());
        if (!classified.equals(result.semanticSymbols())) {
            throw new IllegalArgumentException(
                    "reachability result does not completely classify semantic symbols");
        }

        for (String root : roots) {
            if (!result.retainedSymbols().contains(root)) {
                throw new IllegalArgumentException(
                        "reachability root is not retained: " + root);
            }
        }
        for (String symbol : retained) {
            if (result.removedSymbols().contains(symbol)) {
                throw new IllegalArgumentException(
                        "reachability result contains symbol in both retained and removed sets: "
                                + symbol);
            }
        }

        for (Map.Entry<String, Object> entry :
                result.compileTimeConstants().entrySet()) {
            validateSymbol(
                    "compile-time constant name", entry.getKey());
            validateConstant(entry.getKey(), entry.getValue());
        }

        LinkedHashMap<String, Object> constants = new LinkedHashMap<>();
        result.compileTimeConstants().entrySet().stream()
                .sorted((left, right) ->
                        CODE_POINT_ORDER.compare(
                                left.getKey(), right.getKey()))
                .forEach(entry ->
                        constants.put(entry.getKey(), entry.getValue()));

        return new ReachabilityManifest(
                result.libraryBuild()
                        ? "library"
                        : "executable",
                roots,
                retained,
                removed,
                constants);
    }

    public static ReachabilityManifest from(
            TreeShaker.Result result,
            BuildOptions options) {
        if (result == null || options == null) {
            throw new IllegalArgumentException(
                    "reachability result/options cannot be null");
        }
        if (result.libraryBuild() != options.preservePublicApi()) {
            throw new IllegalArgumentException(
                    "reachability result build kind does not match BuildOptions");
        }
        return from(result);
    }

    public String buildKind() {
        return buildKind;
    }

    public List<String> roots() {
        return roots;
    }

    public List<String> retainedSymbols() {
        return retainedSymbols;
    }

    public List<String> removedSymbols() {
        return removedSymbols;
    }

    public Map<String, Object> compileTimeConstants() {
        return compileTimeConstants;
    }

    public String canonicalJson() {
        StringBuilder out = new StringBuilder(256);
        out.append('{');
        field(out, "format", FORMAT).append(',');
        field(out, "buildKind", buildKind).append(',');
        arrayField(out, "roots", roots).append(',');
        arrayField(out, "retainedSymbols", retainedSymbols).append(',');
        arrayField(out, "removedSymbols", removedSymbols).append(',');
        out.append("\"compileTimeConstants\":{");
        boolean first = true;
        for (Map.Entry<String, Object> entry :
                compileTimeConstants.entrySet()) {
            if (!first) out.append(',');
            first = false;
            quote(out, entry.getKey()).append(':');
            jsonValue(out, entry.getValue());
        }
        out.append("}}");
        return out.toString();
    }

    public String sha256() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson()
                            .getBytes(StandardCharsets.UTF_8));
            StringBuilder out =
                    new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                out.append(Character.forDigit(
                        (value >>> 4) & 0xf, 16));
                out.append(Character.forDigit(
                        value & 0xf, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(
                    "SHA-256 unavailable", impossible);
        }
    }

    private static void requireCardinality(
            String label,
            int size) {
        if (size > MAX_ITEMS) {
            throw new IllegalArgumentException(
                    label + " exceeds ores-reachability/1 schema limit");
        }
    }

    private static List<String> sorted(
            String label,
            java.util.Set<String> values) {
        ArrayList<String> sorted =
                new ArrayList<>(values.size());
        for (String value : values) {
            validateSymbol(label, value);
            sorted.add(value);
        }
        sorted.sort(CODE_POINT_ORDER);
        return List.copyOf(sorted);
    }

    private static int compareCodePointOrder(
            String left,
            String right) {
        int li = 0;
        int ri = 0;
        while (li < left.length() && ri < right.length()) {
            int lcp = left.codePointAt(li);
            int rcp = right.codePointAt(ri);
            if (lcp != rcp)
                return Integer.compare(lcp, rcp);
            li += Character.charCount(lcp);
            ri += Character.charCount(rcp);
        }
        return Integer.compare(
                left.length() - li,
                right.length() - ri);
    }

    private static void validateSymbol(
            String label,
            String value) {
        validateUnicode(label, value);
        int codePoints =
                value.codePointCount(0, value.length());
        if (codePoints == 0 || codePoints > 1024) {
            throw new IllegalArgumentException(
                    label +
                    " must contain 1..1024 Unicode code points");
        }
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            if (codePoint <= 0x1f ||
                    codePoint == 0x7f) {
                throw new IllegalArgumentException(
                        label +
                        " contains a forbidden control character");
            }
            i += Character.charCount(codePoint);
        }
    }

    private static void validateUnicode(
            String label,
            String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    label + " cannot be null");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() ||
                        !Character.isLowSurrogate(
                                value.charAt(i + 1))) {
                    throw new IllegalArgumentException(
                            label +
                            " contains an unpaired UTF-16 surrogate");
                }
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException(
                        label +
                        " contains an unpaired UTF-16 surrogate");
            }
        }
    }

    private static void validateConstant(
            String name,
            Object value) {
        if (value == null || value instanceof Boolean)
            return;
        if (value instanceof String text) {
            validateUnicode(
                    "compile-time constant '" +
                    name + "'", text);
            return;
        }
        if (value instanceof Byte ||
                value instanceof Short ||
                value instanceof Integer ||
                value instanceof Long)
            return;
        if (value instanceof Float number &&
                Float.isFinite(number))
            return;
        if (value instanceof Double number &&
                Double.isFinite(number))
            return;
        throw new IllegalArgumentException(
                "compile-time constant '" + name +
                "' is not canonically JSON-encodable");
    }

    private static StringBuilder field(
            StringBuilder out,
            String name,
            String value) {
        quote(out, name).append(':');
        return quote(out, value);
    }

    private static StringBuilder arrayField(
            StringBuilder out,
            String name,
            List<String> values) {
        quote(out, name).append(":[");
        for (int i = 0; i < values.size(); i++) {
            if (i != 0) out.append(',');
            quote(out, values.get(i));
        }
        return out.append(']');
    }

    private static void jsonValue(
            StringBuilder out,
            Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            quote(out, text);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Number number) {
            out.append(canonicalNumber(number));
        } else {
            throw new IllegalArgumentException(
                    "unsupported JSON value " +
                    value.getClass().getName());
        }
    }

    private static String canonicalNumber(Number number) {
        if (number instanceof Byte ||
                number instanceof Short ||
                number instanceof Integer ||
                number instanceof Long) {
            return Long.toString(number.longValue());
        }
        if (number instanceof Float value) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException(
                        "non-finite float is not valid canonical JSON");
            }
            return normalizeFloatingSyntax(
                    Float.toString(value));
        }
        if (number instanceof Double value) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(
                        "non-finite double is not valid canonical JSON");
            }
            return normalizeFloatingSyntax(
                    Double.toString(value));
        }
        throw new IllegalArgumentException(
                "unsupported canonical numeric type " +
                number.getClass().getName());
    }

    private static String normalizeFloatingSyntax(
            String raw) {
        if (raw.equals("-0.0")) return "0.0";
        int exponent = Math.max(
                raw.indexOf('e'), raw.indexOf('E'));
        if (exponent < 0) return raw;

        String mantissa =
                raw.substring(0, exponent);
        String power =
                raw.substring(exponent + 1);
        boolean negative = power.startsWith("-");
        if (negative || power.startsWith("+"))
            power = power.substring(1);

        int firstNonZero = 0;
        while (firstNonZero + 1 < power.length() &&
                power.charAt(firstNonZero) == '0') {
            firstNonZero++;
        }
        power = power.substring(firstNonZero);
        return mantissa + "e" +
                (negative ? "-" : "") + power;
    }

    private static StringBuilder quote(
            StringBuilder out,
            String value) {
        validateUnicode("JSON string", value);
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append("\\u00");
                        out.append(Character.forDigit(
                                (ch >>> 4) & 0xf, 16));
                        out.append(Character.forDigit(
                                ch & 0xf, 16));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"');
    }
}
