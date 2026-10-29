package dev.oreslang.runtime;

import dev.oreslang.compiler.PackageNamespaceIndex;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The host's explicit, bounded namespace package graph. A dependency alias
 * must pin both its manifest and the source file content set. Source code
 * cannot discover packages by scanning directories or using ORESLANG_PATH.
 */
final class PackageNamespaceWorkspace {
    record Workspace(List<Path> manifests, List<Path> files, Map<String, String> aliases) {
        Workspace {
            manifests = List.copyOf(manifests);
            files = List.copyOf(files);
            aliases = Map.copyOf(aliases);
        }
    }

    private PackageNamespaceWorkspace() { }

    static Workspace discover(Path entry) throws IOException {
        Path canonicalEntry = entry.toRealPath();
        Path manifest = null;
        for (Path cursor = canonicalEntry.getParent(); cursor != null; cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(".ores.pkg.toml");
            if (Files.isRegularFile(candidate)) {
                manifest = candidate.toRealPath();
                break;
            }
        }
        if (manifest == null) return null;

        LinkedHashSet<Path> manifests = new LinkedHashSet<>();
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        manifests.add(manifest);
        TomlParseResult root = checkedManifest(manifest);
        files.addAll(members(manifest, root));

        Object rawDeps = root.get("namespace_dependencies");
        if (rawDeps != null) {
            if (!(rawDeps instanceof TomlTable deps) || deps.size() > 32) {
                throw new IllegalArgumentException("invalid namespace_dependencies table");
            }
            for (String alias : deps.keySet()) {
                if (!alias.matches("[A-Za-z][A-Za-z0-9_-]{0,127}")) {
                    throw new IllegalArgumentException("invalid namespace dependency alias: " + alias);
                }
                Object raw = deps.get(alias);
                if (!(raw instanceof TomlTable dependency) || dependency.size() != 4) {
                    throw new IllegalArgumentException("namespace dependency must pin manifest, "
                            + "package, manifest_sha256 and sources_sha256: " + alias);
                }
                String location = requiredString(dependency, "manifest");
                String packageKey = requiredString(dependency, "package");
                String manifestSha = requiredString(dependency, "manifest_sha256");
                String sourcesSha = requiredString(dependency, "sources_sha256");
                if (!manifestSha.matches("[a-f0-9]{64}") || !sourcesSha.matches("[a-f0-9]{64}")) {
                    throw new IllegalArgumentException("dependency hashes must be lowercase SHA-256");
                }
                // Deliberately allow an explicit relative path to another
                // project; require strong content pins before admitting it.
                Path candidate = Path.of(location);
                if (candidate.isAbsolute() || location.indexOf('\\') >= 0
                        || !".ores.pkg.toml".equals(candidate.getFileName().toString())) {
                    throw new IllegalArgumentException("namespace dependency manifest must be relative .ores.pkg.toml");
                }
                Path dependencyManifest = manifest.getParent().resolve(candidate).normalize();
                if (!Files.isRegularFile(dependencyManifest)) {
                    throw new IllegalArgumentException("namespace dependency manifest not found: " + alias);
                }
                if (!sha256(Files.readAllBytes(dependencyManifest)).equals(manifestSha)) {
                    throw new SecurityException("namespace dependency manifest pin mismatch: " + alias);
                }
                Path canonical = dependencyManifest.toRealPath();
                TomlParseResult parsed = checkedManifest(canonical);
                Object packageTable = parsed.get("packages." + packageKey);
                if (!(packageTable instanceof TomlTable)) {
                    throw new IllegalArgumentException("pinned package not declared: " + packageKey);
                }
                List<Path> owned = members(canonical, parsed);
                if (!sourceDigest(canonical, owned).equals(sourcesSha)) {
                    throw new SecurityException("namespace dependency source pin mismatch: " + alias);
                }
                manifests.add(canonical);
                files.addAll(owned);
                aliases.put(alias, PackageNamespaceIndex.packageId(canonical, packageKey));
            }
        }
        if (manifests.size() > 33 || files.size() > 4096) {
            throw new IllegalArgumentException("namespace workspace exceeds manifest/source limits");
        }
        return new Workspace(new ArrayList<>(manifests), new ArrayList<>(files), aliases);
    }

    private static TomlParseResult checkedManifest(Path path) throws IOException {
        if (Files.size(path) > 1024 * 1024) {
            throw new IllegalArgumentException("package manifest exceeds byte budget");
        }
        TomlParseResult parsed = Toml.parse(path);
        if (!parsed.errors().isEmpty()) {
            throw new IllegalArgumentException("invalid namespace manifest: " + parsed.errors().getFirst());
        }
        if (!"2".equals(parsed.getString("schema_version"))) {
            throw new IllegalArgumentException("namespace package requires schema_version = 2");
        }
        return parsed;
    }

    private static List<Path> members(Path manifest, TomlParseResult parsed) throws IOException {
        Path root = manifest.getParent().toRealPath();
        String relativeRoot = parsed.getString("project.root");
        if (relativeRoot != null && !".".equals(relativeRoot)) {
            Path offset = Path.of(relativeRoot);
            if (offset.isAbsolute() || containsParent(offset)) {
                throw new IllegalArgumentException("project root escapes package manifest");
            }
            root = root.resolve(offset).normalize().toRealPath();
            if (!root.startsWith(manifest.getParent().toRealPath())) {
                throw new IllegalArgumentException("project root escapes manifest directory");
            }
        }
        Object raw = parsed.get("packages");
        if (!(raw instanceof TomlTable packages) || packages.size() == 0
                || packages.size() > 128) {
            throw new IllegalArgumentException("package manifest needs bounded [packages]");
        }
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        for (String key : packages.keySet()) {
            Object value = packages.get(key);
            if (!(value instanceof TomlTable table)
                    || !(table.get("files") instanceof TomlArray listed)
                    || listed.size() > 4096 || listed.isEmpty()) {
                throw new IllegalArgumentException("invalid package file list: " + key);
            }
            for (int i = 0; i < listed.size(); i++) {
                String item = listed.getString(i);
                if (item == null || !item.endsWith(".ores") || item.indexOf('\\') >= 0) {
                    throw new IllegalArgumentException("invalid namespace provider filename");
                }
                Path relative = Path.of(item);
                if (relative.isAbsolute() || containsParent(relative)) {
                    throw new IllegalArgumentException("namespace package member path traversal");
                }
                Path source = root.resolve(relative).normalize();
                if (!source.startsWith(root)) throw new SecurityException("namespace provider path escaped");
                Path cursor = root;
                for (Path part : relative) {
                    cursor = cursor.resolve(part);
                    if (Files.isSymbolicLink(cursor)) {
                        throw new SecurityException("symlinked namespace package member: " + cursor);
                    }
                    if (!cursor.equals(root) && !cursor.equals(source)
                            && Files.isRegularFile(cursor.resolve(".ores.pkg.toml"))) {
                        throw new SecurityException("nested namespace package boundary: " + cursor);
                    }
                }
                Path canonical = source.toRealPath();
                if (!canonical.startsWith(root) || !Files.isRegularFile(canonical)) {
                    throw new SecurityException("namespace provider is outside its package");
                }
                if (!files.add(canonical)) {
                    throw new IllegalArgumentException("duplicate namespace file membership: " + canonical);
                }
                if (Files.size(canonical) > 8L * 1024 * 1024) {
                    throw new IllegalArgumentException("namespace source file exceeds byte budget");
                }
            }
        }
        return List.copyOf(files);
    }

    private static boolean containsParent(Path path) {
        for (Path part : path) if ("..".equals(part.toString())) return true;
        return false;
    }

    private static String requiredString(TomlTable table, String field) {
        String value = table.getString(field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required namespace dependency field: " + field);
        }
        return value;
    }

    static String sourceDigest(Path manifest, List<Path> sourceFiles) throws IOException {
        Path root = manifest.toRealPath().getParent();
        String relativeRoot = checkedManifest(manifest).getString("project.root");
        if (relativeRoot != null && !".".equals(relativeRoot)) {
            root = root.resolve(relativeRoot).normalize().toRealPath();
        }
        List<String> parts = new ArrayList<>();
        for (Path source : sourceFiles) {
            parts.add(root.relativize(source.toRealPath()).toString().replace('\\', '/')
                    + "\n" + sha256(Files.readAllBytes(source)) + "\n");
        }
        parts.sort(String::compareTo);
        return sha256(String.join("", parts).getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
