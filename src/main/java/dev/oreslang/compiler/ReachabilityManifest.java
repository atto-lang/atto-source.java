package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic backend-neutral description of semantic reachability.
 *
 * This is emitted after Oreslang semantic tree shaking and before target-specific
 * lowering. Native/JVM/Wasm/embedded/hardware backends can therefore compare
 * the same semantic symbol set before applying their own implementation-level
 * DCE, linker GC, synthesis, or packaging.
 */
public final class ReachabilityManifest {
    public static final String FORMAT = "ores-reachability/1";

    private static final Comparator<String> CODE_POINT_ORDER =
            ReachabilityManifest::compareCodePointOrder;

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

    public static ReachabilityManifest from(TreeShaker.Result result, BuildOptions options) {
        List<String> retained = sorted(result.retainedSymbols());
        List<String> removed = sorted(result.removedSymbols());
        for (String symbol : retained) {
            if (result.removedSymbols().contains(symbol)) {
                throw new IllegalArgumentException(
                        "reachability result contains symbol in both retained and removed sets: " + symbol);
            }
        }

        List<String> roots = options.preservePublicApi()
                ? libraryRoots(result.program(), result.retainedSymbols())
                : executableRoots(options.entryPoints(), result.retainedSymbols());
        for (String root : roots) {
            if (!result.retainedSymbols().contains(root)) {
                throw new IllegalArgumentException(
                        "reachability root is not retained: " + root);
            }
        }

        LinkedHashMap<String, Object> constants = new LinkedHashMap<>();
        result.compileTimeConstants().entrySet().stream()
                .sorted((left, right) -> CODE_POINT_ORDER.compare(left.getKey(), right.getKey()))
                .forEach(entry -> {
                    validateUnicode("compile-time constant name", entry.getKey());
                    validateConstant(entry.getKey(), entry.getValue());
                    constants.put(entry.getKey(), entry.getValue());
                });

        return new ReachabilityManifest(
                options.preservePublicApi() ? "library" : "executable",
                roots,
                retained,
                removed,
                constants);
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
        for (Map.Entry<String, Object> entry : compileTimeConstants.entrySet()) {
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
                    .digest(canonicalJson().getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                out.append(Character.forDigit((value >>> 4) & 0xf, 16));
                out.append(Character.forDigit(value & 0xf, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static List<String> executableRoots(Set<String> requested, Set<String> retained) {
        ArrayList<String> roots = new ArrayList<>();
        for (String entryPoint : requested) {
            validateUnicode("entry point", entryPoint);
            String resolved = resolveRetainedEntryPoint(entryPoint, retained);
            if (resolved == null) {
                throw new IllegalArgumentException(
                        "retained entry point '" + entryPoint + "' was not found in tree-shaker result");
            }
            roots.add(resolved);
        }
        roots.sort(CODE_POINT_ORDER);
        return List.copyOf(roots);
    }

    private static String resolveRetainedEntryPoint(String requested, Set<String> retained) {
        if (retained.contains(requested)) return requested;
        if (!requested.contains(".")) {
            String root = Parser.ROOT_MODULE + "." + requested;
            if (retained.contains(root)) return root;
            String suffix = "." + requested;
            String found = null;
            for (String symbol : retained) {
                if (!symbol.endsWith(suffix)) continue;
                if (found != null) {
                    throw new IllegalArgumentException(
                            "ambiguous retained entry point '" + requested + "'");
                }
                found = symbol;
            }
            return found;
        }
        return null;
    }

    private static List<String> libraryRoots(Ast.Program program, Set<String> retained) {
        ArrayList<String> roots = new ArrayList<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!isPublicApi(declaration)) continue;
                String name = declarationName(declaration);
                if (name == null) continue;
                String symbol = module.name() + "." + name;
                if (retained.contains(symbol)) roots.add(symbol);
            }
        }
        roots.sort(CODE_POINT_ORDER);
        return List.copyOf(roots);
    }

    private static boolean isPublicApi(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl fn) {
            return fn.visibility() == Ast.Visibility.PUBLIC;
        }
        if (declaration instanceof Ast.FieldDecl field) {
            return field.visibility() == Ast.Visibility.PUBLIC;
        }
        if (declaration instanceof Ast.InterfaceDecl iface) {
            return iface.visibility() == Ast.Visibility.PUBLIC;
        }
        if (declaration instanceof Ast.ClassDecl klass) {
            return klass.visibility() == Ast.Visibility.PUBLIC;
        }
        return declaration instanceof Ast.TypeAliasDecl;
    }

    private static String declarationName(Ast.Decl declaration) {
        if (declaration instanceof Ast.FunctionDecl function) return function.name();
        if (declaration instanceof Ast.ClassDecl klass) return klass.name();
        if (declaration instanceof Ast.InterfaceDecl iface) return iface.name();
        if (declaration instanceof Ast.FieldDecl field) return field.name();
        if (declaration instanceof Ast.TypeAliasDecl alias) return alias.name();
        return null;
    }

    private static List<String> sorted(Set<String> values) {
        ArrayList<String> sorted = new ArrayList<>(values.size());
        for (String value : values) {
            validateUnicode("symbol", value);
            sorted.add(value);
        }
        sorted.sort(CODE_POINT_ORDER);
        return List.copyOf(sorted);
    }

    private static int compareCodePointOrder(String left, String right) {
        int li = 0;
        int ri = 0;
        while (li < left.length() && ri < right.length()) {
            int lcp = left.codePointAt(li);
            int rcp = right.codePointAt(ri);
            if (lcp != rcp) return Integer.compare(lcp, rcp);
            li += Character.charCount(lcp);
            ri += Character.charCount(rcp);
        }
        return Integer.compare(left.length() - li, right.length() - ri);
    }

    private static void validateUnicode(String label, String value) {
        if (value == null) {
            throw new IllegalArgumentException(label + " cannot be null");
        }
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException(label + " contains an unpaired UTF-16 surrogate");
                }
                i++;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException(label + " contains an unpaired UTF-16 surrogate");
            }
        }
    }

    private static void validateConstant(String name, Object value) {
        if (value == null || value instanceof Boolean) return;
        if (value instanceof String text) {
            validateUnicode("compile-time constant '" + name + "'", text);
            return;
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) return;
        if (value instanceof Float number && Float.isFinite(number)) return;
        if (value instanceof Double number && Double.isFinite(number)) return;
        throw new IllegalArgumentException(
                "compile-time constant '" + name + "' is not canonically JSON-encodable");
    }

    private static StringBuilder field(StringBuilder out, String name, String value) {
        quote(out, name).append(':');
        return quote(out, value);
    }

    private static StringBuilder arrayField(StringBuilder out, String name, List<String> values) {
        quote(out, name).append(":[");
        for (int i = 0; i < values.size(); i++) {
            if (i != 0) out.append(',');
            quote(out, values.get(i));
        }
        return out.append(']');
    }

    private static void jsonValue(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            quote(out, text);
        } else if (value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Number number) {
            out.append(canonicalNumber(number));
        } else {
            throw new IllegalArgumentException("unsupported JSON value " + value.getClass().getName());
        }
    }

    private static String canonicalNumber(Number number) {
        if (number instanceof Byte || number instanceof Short
                || number instanceof Integer || number instanceof Long) {
            return Long.toString(number.longValue());
        }
        if (number instanceof Float value) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("non-finite float is not valid canonical JSON");
            }
            return normalizeFloatingSyntax(Float.toString(value));
        }
        if (number instanceof Double value) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("non-finite double is not valid canonical JSON");
            }
            return normalizeFloatingSyntax(Double.toString(value));
        }
        throw new IllegalArgumentException(
                "unsupported canonical numeric type " + number.getClass().getName());
    }

    private static String normalizeFloatingSyntax(String raw) {
        if (raw.equals("-0.0")) return "0.0";
        int exponent = Math.max(raw.indexOf('e'), raw.indexOf('E'));
        if (exponent < 0) return raw;

        String mantissa = raw.substring(0, exponent);
        String power = raw.substring(exponent + 1);
        boolean negative = power.startsWith("-");
        if (negative || power.startsWith("+")) power = power.substring(1);

        int firstNonZero = 0;
        while (firstNonZero + 1 < power.length() && power.charAt(firstNonZero) == '0') {
            firstNonZero++;
        }
        power = power.substring(firstNonZero);
        return mantissa + "e" + (negative ? "-" : "") + power;
    }

    private static StringBuilder quote(StringBuilder out, String value) {
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
                        out.append(Character.forDigit((ch >>> 4) & 0xf, 16));
                        out.append(Character.forDigit(ch & 0xf, 16));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"');
    }
}
