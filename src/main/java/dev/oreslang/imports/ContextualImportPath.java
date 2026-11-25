package dev.oreslang.imports;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Compile-time namespace-path normalization only; never grants package or export authority. */
public final class ContextualImportPath {
    private static final int MAX_COMPONENTS = 8;
    private static final int MAX_PATH = MAX_COMPONENTS * 65;
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    // Bounded import-v2 profile vocabulary aligned with Rust's static import graph.
    private static final Set<String> RESERVED = Set.of("abstract", "actor", "arr", "as", "async", "await", "begin", "block", "break", "catch", "cb", "class", "conforms", "const", "continue", "contract", "cpu", "declare", "def", "default", "defer", "define", "do", "done", "durable", "elif", "else", "elseif", "end", "enum", "eq", "extends", "false", "fi", "finally", "fnc", "for", "from", "generator", "gpu", "guard", "if", "impl", "implements", "import", "interface", "is", "isoactor", "let", "loop", "match", "matches", "maybe", "module", "mtd", "mut", "namespace", "nb", "neq", "new", "nlex", "null", "obj", "of", "over", "panic", "persistent", "pipe", "pkg", "private", "pub", "pure", "quantum", "raise", "readch", "recover", "return", "routine", "rpipe", "rt", "select", "self", "shared", "spawn", "static", "stop", "struct", "super", "suspends", "switch", "sync", "task", "then", "throw", "trait", "trap", "true", "try", "type", "typeof", "types", "unsafe", "untrusted", "use", "using", "val", "void", "while", "writech", "yield");
    private ContextualImportPath() {}

    public static String normalize(String path, String currentNamespace) {
        if (path == null || path.isEmpty() || path.length() > MAX_PATH) throw invalid();
        String[] parts = path.split("\\.", -1);
        if (parts.length < 2 || parts.length > MAX_COMPONENTS) throw invalid();
        List<String> result = new ArrayList<>();
        String root = parts[0];
        if (!root.equals("pkg")) {
            if (!root.equals("self") && !root.equals("super")) throw invalid();
            if (currentNamespace == null || currentNamespace.isEmpty()
                    || currentNamespace.length() > MAX_PATH) throw invalid();
            String[] current = currentNamespace.split("\\.", -1);
            // Validate ALL metadata before parent traversal discards any part.
            if (current.length > MAX_COMPONENTS || Arrays.stream(current).anyMatch(p -> !valid(p)))
                throw invalid();
            result.addAll(Arrays.asList(current));
        }
        int offset = 1;
        if (root.equals("super")) {
            int parents = 1;
            while (offset < parts.length && parts[offset].equals("super")) {
                parents++;
                offset++;
            }
            if (parents > result.size() || offset == parts.length) throw invalid();
            result.subList(result.size() - parents, result.size()).clear();
        }
        if (result.size() + parts.length - offset > MAX_COMPONENTS) throw invalid();
        for (; offset < parts.length; offset++) {
            if (!valid(parts[offset])) throw invalid();
            result.add(parts[offset]);
        }
        return String.join(".", result);
    }

    static boolean valid(String p) {
        return IDENT.matcher(p).matches() && !p.startsWith("__atto_") && !RESERVED.contains(p);
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("E_IMPORT_PATH: invalid contextual namespace path");
    }
}
