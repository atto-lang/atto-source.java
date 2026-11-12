package dev.oreslang.compiler;

import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.parser.Parser;
import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Manifest-backed, closed-source-set namespace compilation.
 *
 * This entrypoint is opt-in in stage one; legacy compile(Map) remains a
 * caller-supplied ephemeral source-set build. The package build is the only
 * entrypoint that can make a claim about filesystem/manifest completeness.
 *
 * A single instance is tied to one resolved manifest and project root, so
 * incremental cache state cannot be shared with another package. Source
 * discovery always rescans the trusted roots before using the cache.
 */
public final class PackageNamespaceBuild {
    public static final int MAX_SOURCE_FILES = 4096;
    public static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    public static final int MAX_VISITED_ENTRIES = 131072;
    public static final int MAX_SOURCE_DEPTH = 64;
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    public static final long MAX_PACKAGE_SOURCE_BYTES = 256L * 1024 * 1024;

    private final OresProjectConfig config;
    private final Path projectRoot;
    private final Path declaredProjectRoot;
    private final Path manifestPath;
    private final byte[] manifestFingerprint;
    private final String packageIdentity;
    private final IncrementalCompiler compiler = new IncrementalCompiler();

    public PackageNamespaceBuild(OresProjectConfig config) throws IOException {
        this.config = java.util.Objects.requireNonNull(config, "project configuration");
        Path manifest = config.manifestPath().orElseThrow(() ->
                new IllegalArgumentException(
                        "namespace package build requires an explicit .atto.pkg.toml manifest"));
        if (Files.isSymbolicLink(manifest)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("package manifest must be an ordinary file");
        }
        manifestPath = manifest.toAbsolutePath().normalize();
        manifestFingerprint = digest(readManifestBounded(manifestPath));
        Path resolved = config.projectRoot().toAbsolutePath().normalize();
        if (!Files.isDirectory(resolved, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(resolved)) {
            throw new IllegalArgumentException("package root must be a non-symlink directory");
        }
        declaredProjectRoot = resolved;
        projectRoot = resolved.toRealPath();
        String name = config.projectName().orElseThrow(() ->
                new IllegalArgumentException("namespace package requires project.name"));
        String version = config.projectVersion().orElseThrow(() ->
                new IllegalArgumentException("namespace package requires project.version"));
        if (!name.matches("[A-Za-z][A-Za-z0-9._-]*")
                || version.isBlank() || version.indexOf('/') >= 0 || version.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("invalid manifest package identity");
        }
        packageIdentity = name + "@" + version;
    }

    public String packageIdentity() { return packageIdentity; }

    /** The exact parsed bytes used by BOTH conformance audit and compilation. */
    private record SourceSnapshot(
            Map<String, String> text, Map<String, Ast.Program> parsed) { }

    private SourceSnapshot snapshot() throws IOException {
        if (Files.isSymbolicLink(manifestPath)
                || !Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)
                || !java.util.Arrays.equals(manifestFingerprint,
                        digest(readManifestBounded(manifestPath)))) {
            throw new IllegalArgumentException(
                    "package manifest changed during incremental build; reload project configuration");
        }
        if (!Files.isDirectory(projectRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(projectRoot)
                || !projectRoot.toRealPath().equals(projectRoot)) {
            throw new IllegalArgumentException(
                    "package root changed or became a symlink during incremental build");
        }
        config.requireStdJavaBackend();
        Map<String, String> sources = readCompleteSourceSet();
        if (sources.isEmpty()) {
            throw new IllegalArgumentException(
                    "package '" + packageIdentity + "' has no .atto/.ores source files");
        }
        Map<String, Ast.Program> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : sources.entrySet()) {
            parsed.put(entry.getKey(), Parser.parse(entry.getValue()));
        }
        validatePackageImportProvenance(parsed);
        if (!java.util.Arrays.equals(manifestFingerprint,
                digest(readManifestBounded(manifestPath)))) {
            throw new IllegalArgumentException(
                    "package manifest changed while reading actor source set");
        }
        return new SourceSnapshot(sources, java.util.Collections.unmodifiableMap(parsed));
    }

    private static boolean hasDeclaredActors(Map<String, Ast.Program> programs) {
        return programs.values().stream().flatMap(p -> p.modules().stream())
                .filter(m -> Parser.ROOT_MODULE.equals(m.name()))
                .flatMap(m -> m.declarations().stream())
                .anyMatch(d -> d instanceof Ast.DeclaredActorDecl);
    }

    /** Manifest-derived static witness and index, not actor code generation. */
    public record ActorAudit(
            dev.oreslang.types.ActorContractWitness.Result contractWitness,
            ActorMethodIndex actorMethods,
            Map<String, String> sourceSha256,
            String manifestSha256,
            Map<ActorMethodIndex.ActorKey, DeclaredActorDescriptor> descriptors) {
        public ActorAudit {
            java.util.Objects.requireNonNull(contractWitness);
            java.util.Objects.requireNonNull(actorMethods);
            sourceSha256 = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(sourceSha256));
            descriptors = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(descriptors));
        }
    }

    private ActorAudit auditSnapshot(SourceSnapshot snapshot) {
        // A generic compile(Map) cannot claim source provenance; only this
        // canonical .atto.pkg.toml source-set entrypoint may issue the audit.
        if (!manifestPath.getFileName().toString()
                .equals(OresProjectConfig.ATTO_MANIFEST_NAME)) {
            throw new IllegalArgumentException(
                    "declared actor witness requires canonical .atto.pkg.toml");
        }
        if (!hasDeclaredActors(snapshot.parsed())) {
            throw new IllegalArgumentException(
                    "manifest actor package contains no declaration-only actors");
        }
        var witness = dev.oreslang.types.ActorContractWitness.linkSourceSet(
                packageIdentity, snapshot.parsed());
        var index = ActorMethodIndex.collect(
                packageIdentity, snapshot.parsed(), witness.boundMethods());
        for (var proof : witness.actors().entrySet()) {
            var actorKey = new ActorMethodIndex.ActorKey(
                    proof.getKey().packageIdentity(),
                    proof.getKey().namespace(), proof.getKey().actor());
            if (!index.hasActor(actorKey)
                    || !index.declaringUnit(actorKey).equals(proof.getValue().actorUnit())) {
                throw new IllegalArgumentException(
                        "declared actor witness/index provenance mismatch: " + actorKey);
            }
            for (var method : proof.getValue().methods()) {
                var indexed = index.method(new ActorMethodIndex.MethodKey(
                        actorKey, method.name(), method.arity()));
                if (indexed == null
                        || !indexed.declaringUnit().equals(method.methodUnit())
                        || !indexed.declarationEligible()) {
                    throw new IllegalArgumentException(
                            "actor method index disagrees with static contract proof: "
                                    + actorKey + "." + method.name());
                }
            }
        }
        Map<String, String> hashes = new LinkedHashMap<>();
        for (var source : snapshot.text().entrySet()) {
            hashes.put(source.getKey(), java.util.HexFormat.of().formatHex(
                    digest(source.getValue().getBytes(StandardCharsets.UTF_8))));
        }
        String manifestHash = java.util.HexFormat.of().formatHex(manifestFingerprint);
        var descriptors = DeclaredActorDescriptor.collect(
                packageIdentity, witness, index, snapshot.parsed(), hashes, manifestHash);
        return new ActorAudit(witness, index, hashes, manifestHash, descriptors);
    }

    /**
     * Opt-in read-only audit. The manifest source set and source hashes are
     * consumed by the same build entrypoint; no ambient package fallback.
     */
    public synchronized ActorAudit auditDeclaredActors() throws IOException {
        return auditSnapshot(snapshot());
    }

    public synchronized Result build() throws IOException {
        SourceSnapshot source = snapshot();
        if (hasDeclaredActors(source.parsed())) {
            // This is a COMPULSORY manifest-owned gate before incremental
            // caching or guest code generation. A successful static witness
            // cannot bypass the missing typed receiver/HIR/MIR lowering.
            ActorAudit audit = auditSnapshot(source);
            throw new IllegalArgumentException(
                    "DECLARED_ACTOR_CODEGEN_UNSUPPORTED: verified "
                            + audit.contractWitness().actors().size()
                            + " declaration-only actor(s) but executable "
                            + "external method dispatch/state loans are not lowered");
        }
        ActorMethodIndex index = ActorMethodIndex.collect(packageIdentity, source.parsed());
        IncrementalCompiler.BuildResult compiled = compiler.compile(source.text());
        return new Result(packageIdentity, source.text().keySet().stream().sorted().toList(),
                index, compiled);
    }

    /**
     * Until a signed/pinned external-dependency manifest is implemented,
     * every source-level import must resolve to another unit in this same
     * manifest's closed source set. Built-in standard-library and Java-host
     * imports remain governed by their separate existing compiler policies.
     */
    private static void validatePackageImportProvenance(
            Map<String, Ast.Program> sourceUnits) {
        for (Map.Entry<String, Ast.Program> unit : sourceUnits.entrySet()) {
            for (Ast.ImportDecl imported : unit.getValue().imports()) {
                String path = imported.path();
                if (ImportRules.isJavaPath(path)
                        || path.startsWith("ores.core/")
                        || path.startsWith("ores.std/")
                        || path.startsWith("std/")) {
                    continue;
                }
                String resolved = ImportGraph.resolveImportUnitId(
                        unit.getKey(), imported, sourceUnits.keySet());
                if (resolved == null) {
                    throw new IllegalArgumentException(
                            "untracked package source import '" + path + "' from '"
                                    + unit.getKey() + "' has no pinned source/dependency proof");
                }
            }
        }
    }

    public record Result(
            String packageIdentity,
            List<String> sourceUnits,
            ActorMethodIndex actorMethods,
            IncrementalCompiler.BuildResult compilation) {
        public Result {
            sourceUnits = List.copyOf(sourceUnits);
            java.util.Objects.requireNonNull(actorMethods, "actor method index");
            java.util.Objects.requireNonNull(compilation, "package compilation");
        }
    }

    /**
     * Returns project-relative unit IDs. Never draws witnesses from ambient
     * ORESLANG_PATH/import_paths or a different manifest; these are imports,
     * not source owners for the namespace's closed-world proof.
     */
    private Map<String, String> readCompleteSourceSet() throws IOException {
        TreeMap<String, Path> discovered = new TreeMap<>();
        Map<String, String> foldedIds = new LinkedHashMap<>();
        int[] visitedEntries = {0};
        ArrayList<Path> roots = new ArrayList<>(config.sourceRoots());
        roots.sort((a, b) -> a.toString().compareTo(b.toString()));
        for (Path declared : roots) {
            Path declaredRoot = declared.toAbsolutePath().normalize();
            // Resolve against the canonical project directory rather than
            // requiring the caller's full ancestor path to be canonical.
            // Systems such as macOS may have a /var -> /private/var
            // symlink ABOVE the chosen project root; internal symlinks
            // remain explicitly forbidden by assertNoSymbolicSegments.
            if (!declaredRoot.startsWith(declaredProjectRoot)) {
                throw new IllegalArgumentException(
                        "source root escapes package or is a symlink: " + declared);
            }
            Path lexical = projectRoot.resolve(
                    declaredProjectRoot.relativize(declaredRoot)).normalize();
            assertNoSymbolicSegments(lexical);
            if (!Files.isDirectory(lexical, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("source root is not an ordinary directory: " + declared);
            }
            if (!lexical.toRealPath().startsWith(projectRoot)) {
                throw new IllegalArgumentException("source root resolves outside package: " + declared);
            }
            Files.walkFileTree(lexical, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (++visitedEntries[0] > MAX_VISITED_ENTRIES
                            || lexical.relativize(dir).getNameCount() > MAX_SOURCE_DEPTH) {
                        throw new IllegalArgumentException(
                                "package source entry/depth budget exceeded");
                    }
                    if (attrs.isSymbolicLink() || Files.isSymbolicLink(dir)
                            || !dir.toAbsolutePath().normalize().startsWith(projectRoot)) {
                        throw new IllegalArgumentException(
                                "symlinked/outside package source directory: " + dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (++visitedEntries[0] > MAX_VISITED_ENTRIES) {
                        throw new IllegalArgumentException(
                                "package source filesystem entry budget exceeded");
                    }
                    if (attrs.isSymbolicLink() || Files.isSymbolicLink(file)) {
                        throw new IllegalArgumentException(
                                "symlinked package source entry is forbidden: " + file);
                    }
                    String leaf = file.getFileName().toString();
                    if (!leaf.endsWith(".atto") && !leaf.endsWith(".ores")) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (!attrs.isRegularFile() || attrs.size() > MAX_FILE_BYTES) {
                        throw new IllegalArgumentException(
                                "package source is not a bounded regular file: " + file);
                    }
                    Path relative = projectRoot.relativize(file.toAbsolutePath().normalize());
                    String id = relative.toString().replace('\\', '/');
                    if (id.isBlank() || id.startsWith("../")) {
                        throw new IllegalArgumentException("invalid package source unit ID: " + id);
                    }
                    String folded = id.toLowerCase(java.util.Locale.ROOT);
                    String previousCase = foldedIds.putIfAbsent(folded, id);
                    if (previousCase != null && !previousCase.equals(id)) {
                        throw new IllegalArgumentException(
                                "case-insensitive source unit collision: " + previousCase + " and " + id);
                    }
                    Path previous = discovered.putIfAbsent(id, file);
                    if (previous != null) {
                        throw new IllegalArgumentException(
                                "overlapping package source roots include the same unit twice: " + id);
                    }
                    if (discovered.size() > MAX_SOURCE_FILES) {
                        throw new IllegalArgumentException("too many package source files");
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }

        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        long total = 0;
        for (Map.Entry<String, Path> source : discovered.entrySet()) {
            assertNoSymbolicSegments(source.getValue());
            if (!source.getValue().toRealPath().startsWith(projectRoot)) {
                throw new IllegalArgumentException(
                        "package source resolves outside manifest project: " + source.getKey());
            }
            byte[] bytes = boundedRead(source.getValue());
            assertNoSymbolicSegments(source.getValue());
            total += bytes.length;
            if (total > MAX_PACKAGE_SOURCE_BYTES) {
                throw new IllegalArgumentException("package source byte quota exceeded");
            }
            try {
                CharBuffer decoded = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes));
                result.put(source.getKey(), decoded.toString());
            } catch (CharacterCodingException invalid) {
                throw new IllegalArgumentException(
                        "invalid UTF-8 in package source " + source.getKey(), invalid);
            }
        }
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(result));
    }

    private static byte[] readManifestBounded(Path manifest) throws IOException {
        byte[] bytes = boundedRead(manifest);
        if (bytes.length > MAX_MANIFEST_BYTES) {
            throw new IllegalArgumentException(
                    "package manifest exceeds 1 MiB hard limit: " + manifest);
        }
        return bytes;
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (java.security.NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 required for manifest binding", unavailable);
        }
    }

    private void assertNoSymbolicSegments(Path target) {
        Path normalized = target.toAbsolutePath().normalize();
        if (!normalized.startsWith(projectRoot)) {
            throw new IllegalArgumentException("path escapes package source root: " + target);
        }
        Path cursor = projectRoot;
        for (Path part : projectRoot.relativize(normalized)) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException(
                        "symlinked package source path component: " + cursor);
            }
        }
    }

    private static byte[] boundedRead(Path source) throws IOException {
        if (Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("source changed to symlink/non-regular file: " + source);
        }
        try (var channel = Files.newByteChannel(source,
                Set.<java.nio.file.OpenOption>of(
                        java.nio.file.StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
             var input = java.nio.channels.Channels.newInputStream(channel);
             var result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16 * 1024];
            long size = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > MAX_FILE_BYTES) {
                    throw new IllegalArgumentException("package source byte quota exceeded: " + source);
                }
                result.write(buffer, 0, count);
            }
            return result.toByteArray();
        }
    }
}
