package dev.oreslang.imports;

import dev.oreslang.ast.Ast;

import java.util.List;
import java.util.regex.Pattern;

/** Shared invariants for source imports and capability-gated Java host imports. */
public final class ImportRules {
    public static final String JAVA_PREFIX = "java:";
    public static final String PACKAGE_PREFIX = "pkg:";
    public static final String NAMESPACE_PACKAGE_PREFIX = "pkg:namespace:";
    private static final Pattern JAVA_BINARY_NAME =
            Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    private ImportRules() { }

    public static boolean isJavaPath(String path) {
        return path != null && path.startsWith(JAVA_PREFIX);
    }

    public static String javaClassName(String path) {
        if (!isJavaPath(path)) throw new IllegalArgumentException("not a Java host import path: " + path);
        String className = path.substring(JAVA_PREFIX.length());
        if (!JAVA_BINARY_NAME.matcher(className).matches()) {
            throw new IllegalArgumentException("invalid Java host class name '" + className + "'");
        }
        return className;
    }

    public static String javaSimpleName(String path) {
        String className = javaClassName(path);
        int dot = className.lastIndexOf('.');
        String simple = dot < 0 ? className : className.substring(dot + 1);
        int nested = simple.lastIndexOf('$');
        return nested < 0 ? simple : simple.substring(nested + 1);
    }

    public static String localName(Ast.ImportDecl imported, String sourceName) {
        if (!imported.wildcard() && imported.namespace() != null) {
            if (imported.names().size() != 1 || !imported.names().getFirst().equals(sourceName)) {
                throw new IllegalArgumentException("named import alias does not match its selected source name");
            }
            return imported.namespace();
        }
        return sourceName;
    }

    public static List<String> exposedBindings(Ast.ImportDecl imported) {
        if (imported.wildcard()) return List.of(imported.namespace());
        return imported.names().stream().map(name -> localName(imported, name)).toList();
    }

    private static boolean isExplicitSelfPath(String path) {
        if ("@self".equals(path)) return true;
        String unix = path.replace('\\', '/').trim();
        boolean sawDot = false;
        for (String component : unix.split("/+", -1)) {
            if (component.isEmpty()) continue;
            if (!component.equals(".")) return false;
            sawDot = true;
        }
        return sawDot;
    }

    public static boolean isTypeOnlyKind(Ast.ImportKind kind) {
        return switch (kind) {
            case ACTOR, CLASS, INTERFACE, CONTRACT, TRAIT, STRUCT, TYPE, TYPES -> true;
            case MODULE, FUNCTION, ALL -> false;
        };
    }

    /** Native unsafe libraries are not part of the guest source catalog. */
    public static boolean isReservedUnsafeNativePath(String path) {
        if (path == null) return false;
        String unix = path.replace('\\', '/');
        if (hasUnsafeSegments(unix)) return true;
        // Track canonical prefixes before later traversal can erase them.
        java.util.ArrayList<String> prefix = new java.util.ArrayList<>(3);
        java.util.ArrayList<Integer> stack = new java.util.ArrayList<>();
        int rootDepth = 0;
        for (String component : unix.split("/")) {
            if (component.isEmpty() || component.equals(".")) continue;
            if (component.equals("..")) {
                if (rootDepth != 0 && stack.size() <= rootDepth) return true;
                if (!stack.isEmpty()) {
                    int previous = stack.removeLast();
                    prefix.subList(previous, prefix.size()).clear();
                }
                continue;
            }
            stack.add(prefix.size());
            for (String segment : component.split("[.:]+")) {
                if (prefix.size() == 3) break;
                if (!segment.isEmpty()) prefix.add(segment);
            }
            int offset = !prefix.isEmpty() && (prefix.getFirst().equals("atto") ||
                    prefix.getFirst().equals("ores")) ? 1 : 0;
            if (prefix.size() > offset && prefix.get(offset).equals("std")) {
                if (rootDepth == 0) rootDepth = stack.size();
                if (prefix.size() > offset + 1 && prefix.get(offset + 1).equals("unsafe")) return true;
            }
        }
        return false;
    }

    private static boolean hasUnsafeSegments(String value) {
        String[] segments = java.util.Arrays.stream(value.split("[/.:]+"))
                .filter(part -> !part.isEmpty()).toArray(String[]::new);
        int offset = segments.length > 0 && (segments[0].equals("atto") ||
                segments[0].equals("ores")) ? 1 : 0;
        return segments.length > offset + 1 && segments[offset].equals("std") &&
                segments[offset + 1].equals("unsafe");
    }

    public static void requireGuestSafePath(String path) {
        if (isReservedUnsafeNativePath(path))
            throw new IllegalArgumentException("E_UNSAFE_DOMAIN: native unsafe standard-library import unavailable in guest domains");
    }

    public static void validate(Ast.ImportDecl imported) {
        if (imported == null) throw new IllegalArgumentException("import declaration cannot be null");
        if (imported.path() == null || imported.path().isBlank()) {
            throw new IllegalArgumentException("import path cannot be empty");
        }
        requireGuestSafePath(imported.path());
        // Import grammar admission does not authorize linking. A synthetic
        // package identity must never fall through to a relative-file
        // resolver or host interop lookup.
        if (imported.path().startsWith(NAMESPACE_PACKAGE_PREFIX)) {
            throw new IllegalArgumentException("E_NAMESPACE_LINK_REQUIRED: namespace imports require a validated pinned multi-provider linker");
        }
        if (imported.path().startsWith(PACKAGE_PREFIX)) {
            throw new IllegalArgumentException(
                    "package namespace imports require a validated package-export graph linker");
        }
        if (imported.path().length() > 4096 || imported.path().chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("import path contains invalid control characters or is too long");
        }

        if (!isJavaPath(imported.path()) && isExplicitSelfPath(imported.path())) {
            throw new IllegalArgumentException(
                    "source file cannot import itself via '" + imported.path() + "'");
        }

        if (imported.wildcard()) {
            if (imported.namespace() == null || imported.namespace().isBlank()) {
                throw new IllegalArgumentException("wildcard imports require a namespace alias");
            }
            if (!imported.names().isEmpty()) {
                throw new IllegalArgumentException("wildcard imports cannot also contain named selections");
            }
        } else {
            if (imported.kind() == Ast.ImportKind.ALL || imported.names().isEmpty()) {
                throw new IllegalArgumentException("named import must select at least one name");
            }
            if (imported.namespace() != null) {
                if (imported.namespace().isBlank()) {
                    throw new IllegalArgumentException("named import alias cannot be blank");
                }
                if (imported.names().size() != 1) {
                    throw new IllegalArgumentException("named import aliases require exactly one selected name");
                }
            }
        }

        if (!isJavaPath(imported.path())) return;

        String simpleName = javaSimpleName(imported.path());
        if (imported.kind() != Ast.ImportKind.CLASS
                && imported.kind() != Ast.ImportKind.FUNCTION
                && imported.kind() != Ast.ImportKind.ALL) {
            throw new IllegalArgumentException(
                    "Java host imports support only class, fnc, or * selectors; got "
                            + imported.kind().name().toLowerCase());
        }
        if (imported.kind() == Ast.ImportKind.CLASS) {
            if (imported.wildcard() || imported.names().size() != 1) {
                throw new IllegalArgumentException("Java class imports must select exactly one class name");
            }
            if (!imported.names().getFirst().equals(simpleName)) {
                throw new IllegalArgumentException("Java class import name '" + imported.names().getFirst()
                        + "' must match host class simple name '" + simpleName + "'");
            }
        }
    }
}
