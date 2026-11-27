package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Package and namespace identity for source units
 * (atto-spec language/stdlib-parity-and-runtime-integration-v2.md, section 4).
 *
 * <ul>
 *   <li>A <b>package</b> is a manifest root (a folder holding
 *       {@code .atto.pkg.toml}); every unit under it belongs to that package.</li>
 *   <li>A <b>namespace</b> is a folder inside a package. A unit without a
 *       namespace header takes the folder path relative to its package root,
 *       joined with '.', for example {@code src/net/http/a.atto} in package
 *       root {@code src} becomes {@code net.http}. Units directly in the
 *       package root take the package name.</li>
 *   <li>{@code use default namespace(n);} takes the last n folders.</li>
 * </ul>
 *
 * <p>Namespace names use '.' separators; '/' only appears in import paths.
 * Unit ids are logical, package-relative paths: never absolute host paths.
 */
public record PackageLayout(Map<String, String> packageRoots) {
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    public static final PackageLayout NONE = new PackageLayout(Map.of());

    public PackageLayout {
        LinkedHashMap<String, String> normalized = new LinkedHashMap<>();
        java.util.HashSet<String> packageNames = new java.util.HashSet<>();
        for (Map.Entry<String, String> entry : packageRoots.entrySet()) {
            String root = normalizeRoot(entry.getKey());
            String name = entry.getValue();
            if (name == null || !Pattern.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*", name)) {
                throw new IllegalArgumentException("invalid package name '" + name + "'");
            }
            // This legacy API supplies names, not pinned package-instance IDs.
            // Never collapse two distinct roots into the same visibility/cache domain.
            if (!packageNames.add(name)) {
                throw new IllegalArgumentException("ambiguous package instance for name '" + name + "'");
            }
            if (normalized.putIfAbsent(root, name) != null) {
                throw new IllegalArgumentException("duplicate package root '" + root + "'");
            }
        }
        packageRoots = Map.copyOf(normalized);
    }

    /** The package root owning a unit (longest matching root), or null. */
    public String rootOf(String unitId) {
        String best = null;
        for (String root : packageRoots.keySet()) {
            boolean contains = root.isEmpty() || unitId.startsWith(root + "/");
            if (contains && (best == null || root.length() > best.length())) best = root;
        }
        return best;
    }

    public String packageId(String unitId) {
        String root = rootOf(unitId);
        return root == null ? "file:" + unitId : "pkg:" + packageRoots.get(root);
    }

    /** Folder names between the owning package root (or the id start) and the file. */
    private List<String> folders(String unitId) {
        String root = rootOf(unitId);
        String relative = root == null || root.isEmpty() ? unitId : unitId.substring(root.length() + 1);
        List<String> parts = new ArrayList<>(List.of(relative.split("/")));
        parts.removeLast();
        return parts;
    }

    /**
     * The namespace a unit actually has: its explicit (dotted) header, a
     * derived {@code use default namespace(n)} header, or, inside a package,
     * the folder-path default. Without a package, a missing header stays null.
     */
    public String effectiveNamespace(String unitId, String declared) {
        if (declared != null && declared.startsWith(Parser.DERIVED_NAMESPACE_PREFIX)) {
            int depth = Integer.parseInt(declared.substring(Parser.DERIVED_NAMESPACE_PREFIX.length()));
            List<String> folders = folders(unitId);
            if (folders.size() < depth) {
                throw new IllegalArgumentException("use default namespace(" + depth + ") in '" + unitId
                        + "' needs " + depth + " enclosing folder(s) inside its package");
            }
            return join(folders.subList(folders.size() - depth, folders.size()), unitId);
        }
        if (declared != null) return declared;
        String root = rootOf(unitId);
        if (root == null) return null;
        List<String> folders = folders(unitId);
        return folders.isEmpty() ? packageRoots.get(root) : join(folders, unitId);
    }

    /** Rewrites a derived/missing header to the effective namespace. */
    public Ast.Program withEffectiveNamespace(String unitId, Ast.Program program) {
        String effective = effectiveNamespace(unitId, program.namespace());
        if (java.util.Objects.equals(effective, program.namespace())) return program;
        return new Ast.Program(effective, program.imports(), program.modules(), program.exports());
    }

    private static String join(List<String> folders, String unitId) {
        for (String folder : folders) {
            if (!SEGMENT.matcher(folder).matches() || folder.equals(Parser.ROOT_MODULE)) {
                throw new IllegalArgumentException("folder '" + folder + "' in '" + unitId
                        + "' is not a valid namespace segment; add an explicit 'use namespace' header");
            }
        }
        return String.join(".", folders);
    }

    private static String normalizeRoot(String root) {
        if (root == null) throw new IllegalArgumentException("package root cannot be null");
        String normalized = root.isEmpty() ? "" : Path.of(root).normalize().toString().replace('\\', '/');
        if (normalized.equals(".")) normalized = "";
        if (normalized.startsWith("/") || normalized.startsWith("..")) {
            throw new IllegalArgumentException("package root must be a logical relative path: " + root);
        }
        return normalized.endsWith("/") ? normalized.substring(0, normalized.length() - 1) : normalized;
    }
}
