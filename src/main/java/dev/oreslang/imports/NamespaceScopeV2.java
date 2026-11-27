package dev.oreslang.imports;

import java.util.Arrays;
import java.util.List;

/** Tagged source scope selection; caller must authenticate the package and source snapshot. */
public final class NamespaceScopeV2 {
    public enum Kind { FILE, NAMED, FOLDER }
    public record Scope(Kind kind, String identity) {}
    private NamespaceScopeV2() {}

    // Logical POSIX path, not a filesystem lookup. Retain the complete folder path.
    private static List<String> parts(String path) {
        if (path == null || path.indexOf('\\') >= 0 || path.indexOf(':') >= 0
                || path.indexOf(0) >= 0) throw failure("E_NAMESPACE_PATH");
        List<String> parts = Arrays.asList(path.split("/", -1));
        if (parts.stream().anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."))
                || !parts.get(parts.size()-1).endsWith(".atto")
                || parts.get(parts.size()-1).length() <= 5) throw failure("E_NAMESPACE_PATH");
        return parts;
    }

    private static boolean identifier(String s) {
        return s.matches("[A-Za-z_][A-Za-z0-9_]*") && (s.equals("types") ||
                ContextualImportPath.valid(s));
    }

    /** Explicit file/default/omitted share one key for the same logical file. */
    public static Scope file(String logicalPath) {
        parts(logicalPath);
        return new Scope(Kind.FILE, logicalPath);
    }

    /** Named and derived-named scopes share exactly one tagged identity. */
    public static Scope named(String name) {
        if (name == null) throw failure("E_SYNTAX");
        String[] names = name.split("\\.", -1);
        if (names.length > 32) throw failure("E_NAMESPACE_DEPTH");
        if (Arrays.stream(names).anyMatch(n -> !identifier(n))) throw failure("E_SYNTAX");
        return new Scope(Kind.NAMED, name);
    }

    public static Scope folder(String logicalPath) {
        List<String> p = parts(logicalPath);
        return new Scope(Kind.FOLDER, String.join("/", p.subList(0, p.size()-1)));
    }

    public static Scope numericDefault(String logicalPath, int depth) {
        if (depth < 1 || depth > 32) throw failure("E_NAMESPACE_DEPTH");
        List<String> p = parts(logicalPath);
        if (depth >= p.size()) throw failure("E_NAMESPACE_DEPTH");
        List<String> suffix = p.subList(p.size()-depth-1, p.size()-1);
        if (suffix.stream().anyMatch(n -> !identifier(n))) throw failure("E_NAMESPACE_PATH");
        return named(String.join(".", suffix));
    }

    private static IllegalArgumentException failure(String code) {
        return new IllegalArgumentException(code + ": invalid namespace identity");
    }
}
