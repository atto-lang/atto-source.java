package dev.oreslang.runtime;

import dev.oreslang.compiler.PackageNamespaceIndex;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
                     Map<Path, String> dependencyManifestPins,
                     String rootManifestSha256,
                     Map<Path, String> manifestSnapshots,
                     Map<Path, Object> providerFileKeys) {
        Workspace {
            manifests = List.copyOf(manifests);
            files = List.copyOf(files);
            aliases = Map.copyOf(aliases);
            rootPackageIds = Set.copyOf(rootPackageIds);
            dependencySourcePins = Map.copyOf(dependencySourcePins);
            dependencyManifestPins = Map.copyOf(dependencyManifestPins);
            manifestSnapshots = Map.copyOf(manifestSnapshots);
            providerFileKeys = Map.copyOf(providerFileKeys);
            if (!Set.copyOf(files).containsAll(providerFileKeys.keySet())) {
                throw new IllegalArgumentException("provider identity map references unlisted sources");
            }
            if (manifests.isEmpty() || !manifestSnapshots.keySet().equals(Set.copyOf(manifests))
                    || !sha256(manifestSnapshots.get(manifests.getFirst())
                            .getBytes(StandardCharsets.UTF_8)).equals(rootManifestSha256)) {
                throw new IllegalArgumentException("incomplete or inconsistent manifest snapshot");
            }
        }
    }

    private PackageNamespaceWorkspace() { }

    static Workspace discover(Path entry) throws IOException {
        // Do not canonicalize away a caller-controlled source entry symlink.
        // A link can be replaced after resolution and reinterpret package
        // ownership without changing the bytes of the admitted target file.
        if (Files.isSymbolicLink(entry)) {
            throw new SecurityException("symlinked namespace entry source is forbidden");
        }
        // A symlinked parent directory is just as mutable as a symlink at
        // the leaf. Check the caller's original path before toRealPath()
        // erases its alias components and changes package-root authority.
        try {
            requireNoSymlinkComponents(entry);
        } catch (SecurityException symlinkedParent) {
            throw new SecurityException(
                    "symlinked namespace entry source ancestry is forbidden", symlinkedParent);
        }
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
        LinkedHashMap<Path, String> manifestSnapshots = new LinkedHashMap<>();
        manifests.add(manifest);
        byte[] rootManifestBytes = readManifestBytes(manifest);
        String rootManifestSha256 = sha256(rootManifestBytes);
        String rootManifestText = decodeManifestText(rootManifestBytes);
        manifestSnapshots.put(manifest, rootManifestText);
        TomlParseResult root = checkedManifestText(rootManifestText);
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
                Path dependencyManifest = manifest.getParent().resolve(candidate);
                // Inspect the caller-provided relative path BEFORE lexical
                // normalization can erase a symlink/../ component. Hash pins
                // authenticate bytes, not filesystem authority.
                requireNoSymlinkComponents(dependencyManifest);
                dependencyManifest = dependencyManifest.normalize();
                if (!Files.isRegularFile(dependencyManifest)) {
                    throw new IllegalArgumentException("namespace dependency manifest not found: " + alias);
                }
                byte[] dependencyBytes = readManifestBytes(dependencyManifest);
                if (!sha256(dependencyBytes).equals(manifestSha)) {
                    throw new SecurityException("namespace dependency manifest pin mismatch: " + alias);
                }
                Path canonical = dependencyManifest.toRealPath();
                String dependencyText = decodeManifestText(dependencyBytes);
                TomlParseResult parsed = checkedManifestText(dependencyText);
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
                if (!sourceDigest(canonical, owned, parsed).equals(sourcesSha)) {
                    throw new SecurityException("namespace dependency source pin mismatch: " + alias);
                }
                manifests.add(canonical);
                files.addAll(owned);
                String olderSnapshot = manifestSnapshots.putIfAbsent(canonical, dependencyText);
                if (olderSnapshot != null && !olderSnapshot.equals(dependencyText)) {
                    throw new SecurityException("conflicting immutable dependency manifests: " + canonical);
                }
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
        // Where supported, bind each admitted physical provider to its file
        // identity. Byte digests alone cannot detect a same-byte inode swap.
        LinkedHashMap<Path, Object> providerFileKeys = new LinkedHashMap<>();
        for (Path file : files) {
            Object fileKey = Files.readAttributes(file,
                    java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey();
            if (fileKey != null) providerFileKeys.put(file, fileKey);
        }
        Workspace snapshot = new Workspace(new ArrayList<>(manifests), new ArrayList<>(files),
                aliases, rootPackageIds, sourcePins, manifestPins, rootManifestSha256,
                manifestSnapshots, providerFileKeys);
        verifyRootManifestSnapshot(snapshot);
        return snapshot;
    }

    private static void requireNoSymlinkComponents(Path path) {
        // Do not normalize before examining the caller's path. Normalizing
        // alias/../source erases the alias without proving that the filesystem
        // traversed the same parent (the alias may point elsewhere).
        Path absolute = path.toAbsolutePath();
        Path cursor = absolute.getRoot();
        if (cursor == null) throw new SecurityException("namespace dependency has no absolute root");
        for (Path component : absolute) {
            String segment = component.toString();
            if (".".equals(segment)) continue;
            if ("..".equals(segment)) {
                cursor = cursor.getParent();
                if (cursor == null) {
                    throw new SecurityException("namespace path escapes filesystem root");
                }
                continue;
            }
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) {
                throw new SecurityException("symlinked namespace dependency path: " + cursor);
            }
        }
    }

    private static byte[] readManifestBytes(Path path) throws IOException {
        // Bound the read itself: a file can grow after a size precheck.
        // NOFOLLOW_LINKS rejects a mutable symlink at the final component.
        try (var in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) {
                throw new IllegalArgumentException("package manifest exceeds byte budget");
            }
            return bytes;
        }
    }

    private static String decodeManifestText(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new IllegalArgumentException("namespace manifest must be valid UTF-8", invalid);
        }
    }

    private static TomlParseResult checkedManifest(Path path) throws IOException {
        return checkedManifestText(decodeManifestText(readManifestBytes(path)));
    }

    private static TomlParseResult checkedManifestText(String text) {
        if (text.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
            throw new IllegalArgumentException("package manifest exceeds byte budget");
        }
        TomlParseResult parsed = Toml.parse(text);
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
        // A project.root subdirectory is still owned by this manifest. Do not
        // cross a second package boundary at that root; otherwise the nearest
        // manifest for a guest entry and the manifest admitting its providers
        // could disagree on ownership. NOFOLLOW_LINKS also fences a dangling
        // manifest symlink, which is not a regular file but can be retargeted.
        if (!root.equals(manifest.getParent().toRealPath())
                && Files.exists(root.resolve(".ores.pkg.toml"), LinkOption.NOFOLLOW_LINKS)) {
            throw new SecurityException("nested namespace package boundary: " + root);
        }
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
                // A manifest is a signed source inventory, not a path search.
                // Refuse syntactically aliased spellings (./, //, trailing .)
                // before canonicalization can erase the caller's bytes.
                if (!relative.normalize().toString().replace('\\', '/').equals(item)) {
                    throw new IllegalArgumentException(
                            "noncanonical namespace provider filename");
                }
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
                            && Files.exists(cursor.resolve(".ores.pkg.toml"), LinkOption.NOFOLLOW_LINKS)) {
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
    static void verifyRootManifestSnapshot(Workspace workspace) throws IOException {
        Path manifest = workspace.manifests().getFirst();
        if (Files.isSymbolicLink(manifest)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
                || !sha256(readManifestBytes(manifest)).equals(workspace.rootManifestSha256())) {
            throw new SecurityException("root namespace manifest changed during admission");
        }
    }

    static void verifyPinnedSnapshot(Workspace workspace, Map<String, String> admitted)
            throws IOException {
        verifyRootManifestSnapshot(workspace);

        // All manifest-owned source bytes must still match the exact text
        // admitted by collectImportClosure(), including ROOT package files.
        // Reconstruct membership from the frozen manifest text: a replacement,
        // deleted provider, symlink swap or changed inventory cannot silently
        // change the source graph between collection and linking.
        LinkedHashSet<Path> verifiedMembers = new LinkedHashSet<>();
        for (Path manifest : workspace.manifests()) {
            String frozenManifest = workspace.manifestSnapshots().get(manifest);
            if (frozenManifest == null) {
                throw new SecurityException("namespace source manifest snapshot missing: " + manifest);
            }
            for (Path member : members(manifest, checkedManifestText(frozenManifest))) {
                if (!verifiedMembers.add(member)) {
                    throw new SecurityException("namespace source member belongs to multiple packages: " + member);
                }
            }
        }
        if (!verifiedMembers.equals(new LinkedHashSet<>(workspace.files()))) {
            throw new SecurityException("namespace source inventory changed during admission");
        }
        for (Path member : verifiedMembers) {
            Object expectedKey = workspace.providerFileKeys().get(member);
            if (expectedKey != null) {
                Object observedKey = Files.readAttributes(member,
                        java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS).fileKey();
                if (!expectedKey.equals(observedKey)) {
                    throw new SecurityException("namespace provider file identity changed during admission: " + member);
                }
            }
            String id = member.toRealPath().toString().replace('\\', '/');
            String sourceText = admitted.get(id);
            if (sourceText == null) {
                throw new SecurityException("namespace provider is absent from admitted source snapshot: " + id);
            }
            byte[] admittedBytes = sourceText.getBytes(StandardCharsets.UTF_8);
            if (admittedBytes.length > 8L * 1024 * 1024
                    || !boundedSourceSha256(member).equals(sha256(admittedBytes))) {
                throw new SecurityException("namespace provider bytes changed during admission: " + member);
            }
        }

        for (var entry : workspace.dependencySourcePins().entrySet()) {
            Path manifest = entry.getKey();
            String pinnedManifestHash = workspace.dependencyManifestPins().get(manifest);
            if (!sha256(readManifestBytes(manifest)).equals(pinnedManifestHash)) {
                throw new SecurityException("dependency manifest changed during admission");
            }
            String captured = workspace.manifestSnapshots().get(manifest);
            if (captured == null) throw new SecurityException("pinned namespace manifest snapshot missing");
            TomlParseResult parsed = checkedManifestText(captured);
            Path root = checkedProjectRoot(manifest, parsed);
            List<String> parts = new ArrayList<>();
            for (Path source : members(manifest, parsed)) {
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
        return sourceDigest(manifest, sourceFiles, checkedManifest(manifest));
    }

    private static String sourceDigest(Path manifest, List<Path> sourceFiles,
                                       TomlParseResult parsed) throws IOException {
        Path root = checkedProjectRoot(manifest, parsed);
        // A pinned sources digest authenticates the ENTIRE manifest-owned
        // inventory, not a caller-selected subset. Recompute membership from
        // the checked manifest so a caller cannot mint a valid-looking digest
        // for an unlisted or omitted provider.
        Set<Path> manifestMembers = new LinkedHashSet<>(members(manifest, parsed));
        Set<Path> digestedMembers = new LinkedHashSet<>();
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
            if (!manifestMembers.contains(canonical)) {
                throw new SecurityException("namespace digest source is not manifest-listed: " + source);
            }
            if (!digestedMembers.add(canonical)) {
                throw new IllegalArgumentException("duplicate namespace digest source: " + source);
            }
            parts.add(root.relativize(canonical).toString().replace('\\', '/')
                    + "\n" + boundedSourceSha256(canonical) + "\n");
        }
        if (!digestedMembers.equals(manifestMembers)) {
            throw new SecurityException("namespace digest source set differs from manifest members");
        }
        parts.sort(String::compareTo);
        return sha256(String.join("", parts).getBytes(StandardCharsets.UTF_8));
    }


    /**
     * The size check during manifest membership discovery is not a bound on
     * a later read: a source file can grow between stat and digest. Stream
     * the exact source SHA-256 with a hard per-file byte limit and no-follow
     * semantics instead of Files.readAllBytes().
     */
    static String boundedSourceSha256(Path source) throws IOException {
        if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new SecurityException("namespace digest source is not a regular file: " + source);
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        try (var in = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS)) {
            byte[] chunk = new byte[8192];
            long bytes = 0;
            int count;
            while ((count = in.read(chunk)) != -1) {
                bytes += count;
                if (bytes > 8L * 1024 * 1024) {
                    throw new IllegalArgumentException("namespace digest source exceeds byte budget");
                }
                digest.update(chunk, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
