package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
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
        this.compileTimeConstants = Map.copyOf(compileTimeConstants);
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

        LinkedHashMap<String, Object> constants = new LinkedHashMap<>();
        result.compileTimeConstants().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
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
            String resolved = resolveRetainedEntryPoint(entryPoint, retained);
            if (resolved == null) {
                throw new IllegalArgumentException(
                        "retained entry point '" + entryPoint + "' was not found in tree-shaker result");
            }
            roots.add(resolved);
        }
        roots.sort(Comparator.naturalOrder());
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
        roots.sort(Comparator.naturalOrder());
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
        return values.stream().sorted().toList();
    }

    private static void validateConstant(String name, Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) return;
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
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(value);
        } else {
            throw new IllegalArgumentException("unsupported JSON value " + value.getClass().getName());
        }
    }

    private static StringBuilder quote(StringBuilder out, String value) {
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
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.append('"');
    }
}
