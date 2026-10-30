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
    record Workspace(List<Path> manifests, List<Path> files, Map<String, String> aliases,
                     Set<String> rootPackageIds, Map<Path, String> dependencySourcePins,
                     Map<Path, String> dependencyManifestPins) {
        Workspace {
            manifests = List.copyOf(manifests);
            files = List.copyOf(files);
            aliases = Map.copyOf(aliases);
            rootPackageIds = Set.copyOf(rootPackageIds);
            dependencySourcePins = Map.copyOf(dependencySourcePins);
            dependencyManifestPins = Map.copyOf(dependencyManifestPins);
        }
    }

    private PackageNamespaceWorkspace() { }

    static Workspace discover(Path entry) throws IOException {
        Path canonicalEntry = entry.toRealPath();
        Path manifest = null;
        for (Path cursor = canonicalEntry.getParent(); cursor != null; cursor = cursor.getParent()) {
            Path candidate = cursor.resolve(".ores.pkg.toml");
            if (Files.isSymbolicLink(candidate)) {
                throw new SecurityException("symlinked root package manifest is forbidden");
            }
            if (Files.isRegularFile(candidate)) {
                manifest = candidate.toRealPath();
                break;
            }
        }
        if (manifest == null) return null;

        LinkedHashSet<Path> manifests = new LinkedHashSet<>();
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        LinkedHashMap<String, String> aliases = new LinkedHashMap<>();
        LinkedHashMap<Path, String> sourcePins = new LinkedHashMap<>();
        LinkedHashMap<Path, String> manifestPins = new LinkedHashMap<>();
        manifests.add(manifest);
        TomlParseResult root = checkedManifest(manifest);
        files.addAll(members(manifest, root));
        LinkedHashSet<String> rootPackageIds = new LinkedHashSet<>();
        TomlTable rootPackages = (TomlTable) root.get("packages");
        for (String packageKey : rootPackages.keySet()) {
            rootPackageIds.add(PackageNamespaceIndex.packageId(manifest, packageKey));
        }

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
                // Hash pins authenticate bytes, not filesystem authority.
                // Reject an alias through a symlinked directory or manifest:
                // otherwise a mutable symlink could retarget the package
                // between path validation, digest checking and source admission.
                requireNoSymlinkComponents(dependencyManifest);
                if (!Files.isRegularFile(dependencyManifest)) {
                    throw new IllegalArgumentException("namespace dependency manifest not found: " + alias);
                }
                if (Files.size(dependencyManifest) > 1024 * 1024) {
                    throw new IllegalArgumentException("dependency manifest exceeds byte budget");
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
                // The current index admits *every* declared package from
                // each supplied manifest. Until it supports package-filtered
                // source admission, accepting a multi-package dependency
                // would initialize providers outside the pinned package.
                Object allPackages = parsed.get("packages");
                if (!(allPackages instanceof TomlTable dependencyPackages)
                        || dependencyPackages.size() != 1) {
                    throw new SecurityException(
                            "pinned dependency manifest must contain exactly one package: " + alias);
                }
                List<Path> owned = members(canonical, parsed);
                if (!sourceDigest(canonical, owned).equals(sourcesSha)) {
                    throw new SecurityException("namespace dependency source pin mismatch: " + alias);
                }
                manifests.add(canonical);
                files.addAll(owned);
                String olderSourcePin = sourcePins.putIfAbsent(canonical, sourcesSha);
                String olderManifestPin = manifestPins.putIfAbsent(canonical, manifestSha);
                if ((olderSourcePin != null && !olderSourcePin.equals(sourcesSha))
                        || (olderManifestPin != null && !olderManifestPin.equals(manifestSha))) {
                    throw new SecurityException("conflicting pins for one package instance: " + canonical);
                }
                aliases.put(alias, PackageNamespaceIndex.packageId(canonical, packageKey));
            }
        }
        long totalBytes = 0;
        for (Path path : files) totalBytes += Files.size(path);
        if (manifests.size() > 33 || files.size() > 4096
                || totalBytes > 64L * 1024 * 1024) {
            throw new IllegalArgumentException("namespace workspace exceeds manifest/source limits");
        }
        return new Workspace(new ArrayList<>(manifests), new ArrayList<>(files),
                aliases, rootPackageIds, sourcePins, manifestPins);
    }

    private static void requireNoSymlinkComponents(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path cursor = absolute.getRoot();
        if (cursor == null) throw new SecurityException("namespace dependency has no absolute root");
        for (Path component : absolute) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) {
                throw new SecurityException("symlinked namespace dependency path: " + cursor);
            }
        }
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

    /**
     * Resolve a manifest-owned root without following mutable path aliases.
     * A symlink that happens to point inside this package is still unsafe:
     * it may be replaced between source admission and pinned-snapshot checks.
     * Share this resolver across member admission, digests and revalidation.
     */
    private static Path checkedProjectRoot(Path manifest, TomlParseResult parsed)
            throws IOException {
        Path manifestDirectory = manifest.getParent().toRealPath();
        Path root = manifestDirectory;
        String relativeRoot = parsed.getString("project.root");
        if (relativeRoot != null && !".".equals(relativeRoot)) {
            Path offset = Path.of(relativeRoot);
            if (offset.isAbsolute() || containsParent(offset)) {
                throw new IllegalArgumentException("project root escapes package manifest");
            }
            for (Path component : offset) {
                root = root.resolve(component);
                if (Files.isSymbolicLink(root)) {
                    throw new SecurityException("symlinked namespace project root: " + root);
                }
            }
            root = root.normalize().toRealPath();
            if (!root.startsWith(manifestDirectory)) {
                throw new IllegalArgumentException("project root escapes manifest directory");
            }
        }
        return root;
    }

    private static List<Path> members(Path manifest, TomlParseResult parsed) throws IOException {
        Path root = checkedProjectRoot(manifest, parsed);
        Object raw = parsed.get("packages");
        if (!(raw instanceof TomlTable packages) || packages.size() == 0
                || packages.size() > 128) {
            throw new IllegalArgumentException("package manifest needs bounded [packages]");
        }
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        long sourceBytes = 0;
        for (String key : packages.keySet()) {
            Object value = packages.get(key);
            if (!(value instanceof TomlTable table)
                    || !(table.get("files") instanceof TomlArray listed)
                    || listed.size() > 4096 || listed.size() == 0) {
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
                long bytes = Files.size(canonical);
                sourceBytes += bytes;
                if (bytes > 8L * 1024 * 1024 || sourceBytes > 64L * 1024 * 1024) {
                    throw new IllegalArgumentException("namespace source snapshot exceeds byte budget");
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

    /**
     * Re-verify pinned dependency bytes against the *same admitted source text*
     * that the compiler will parse, not just an earlier filesystem read.
     */
    static void verifyPinnedSnapshot(Workspace workspace, Map<String, String> admitted)
            throws IOException {
        for (var entry : workspace.dependencySourcePins().entrySet()) {
            Path manifest = entry.getKey();
            String pinnedManifestHash = workspace.dependencyManifestPins().get(manifest);
            if (!sha256(Files.readAllBytes(manifest)).equals(pinnedManifestHash)) {
                throw new SecurityException("dependency manifest changed during admission");
            }
            Path root = checkedProjectRoot(manifest, checkedManifest(manifest));
            List<String> parts = new ArrayList<>();
            for (Path source : members(manifest, checkedManifest(manifest))) {
                String id = source.toRealPath().toString().replace('\\', '/');
                String admittedText = admitted.get(id);
                if (admittedText == null) {
                    throw new IllegalArgumentException("pinned namespace provider is absent: " + id);
                }
                parts.add(root.relativize(source.toRealPath()).toString().replace('\\', '/')
                        + "\n" + sha256(admittedText.getBytes(StandardCharsets.UTF_8)) + "\n");
            }
            parts.sort(String::compareTo);
            if (!sha256(String.join("", parts).getBytes(StandardCharsets.UTF_8))
                    .equals(entry.getValue())) {
                throw new SecurityException("dependency source content changed during admission");
            }
        }
    }

    static String sourceDigest(Path manifest, List<Path> sourceFiles) throws IOException {
        Path root = checkedProjectRoot(manifest, checkedManifest(manifest));
        List<String> parts = new ArrayList<>();
        for (Path source : sourceFiles) {
            Path logical = source.toAbsolutePath().normalize();
            // Digests are only meaningful inside the authenticated project root.
            // Canonicalization alone is insufficient: a symlink that currently
            // points inside the tree may be retargeted between the digest and
            // subsequent compiler admission.
            if (!logical.startsWith(root)) {
                throw new SecurityException("namespace digest source is outside manifest root: " + source);
            }
            Path cursor = root;
            for (Path component : root.relativize(logical)) {
                cursor = cursor.resolve(component);
                if (Files.isSymbolicLink(cursor)) {
                    throw new SecurityException("symlinked namespace digest source: " + cursor);
                }
            }
            Path canonical = logical.toRealPath();
            if (!canonical.equals(logical) || !Files.isRegularFile(canonical)) {
                throw new SecurityException("namespace digest source is not a regular manifest-root file");
            }
            parts.add(root.relativize(canonical).toString().replace('\\', '/')
                    + "\n" + sha256(Files.readAllBytes(canonical)) + "\n");
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
