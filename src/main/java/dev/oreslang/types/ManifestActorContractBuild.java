package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.parser.Parser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Opt-in, manifest-scoped STATIC actor contract witness candidate.
 *
 * Does not execute Atto guest source or mint actor execution capabilities.
 * Source roots are explicitly supplied by the existing OresProjectConfig,
 * never by ambient path lookup. Symlinks and foreign imports fail closed.
 *
 * WARNING: An adversarial writer who can replace ancestor directories during
 * the scan can race path resolution. Production use requires an immutable
 * checkout/snapshot or a directory-handle-anchored secure traversal.
 * This Java library API is not wired into the launcher.
 */
public final class ManifestActorContractBuild {
    public static final int MAX_FILES = 4096;
    public static final int MAX_VISITED_ENTRIES = 131072;
    public static final int MAX_SOURCE_DEPTH = 64;
    public static final long MAX_SOURCE_BYTES = 256L * 1024 * 1024;
    public static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    public static final int MAX_MANIFEST_BYTES = 1024 * 1024;

    private final OresProjectConfig config;
    private final Path root;
    private final Path manifest;
    private final byte[] manifestDigest;
    private final String packageIdentity;

    public record Receipt(
            ActorContractWitness.Result staticWitness,
            Map<String, String> sourceSha256,
            String manifestSha256) {
        public Receipt {
            sourceSha256 = java.util.Collections.unmodifiableMap(
                    new LinkedHashMap<>(sourceSha256));
        }
    }

    public ManifestActorContractBuild(OresProjectConfig config) throws IOException {
        this.config = java.util.Objects.requireNonNull(config, "manifest project config");
        this.manifest = config.manifestPath().orElseThrow(() ->
                new IllegalArgumentException("actor witness requires explicit .atto.pkg.toml"));
        this.root = config.projectRoot().toAbsolutePath().normalize();
        if (!manifest.getFileName().toString().equals(OresProjectConfig.ATTO_MANIFEST_NAME)) {
            throw new IllegalArgumentException("actor witness requires canonical .atto.pkg.toml");
        }
        requireOrdinaryPath(manifest, false);
        requireOrdinaryPath(root, true);
        if (!manifest.toAbsolutePath().normalize().startsWith(root)) {
            throw new IllegalArgumentException("actor witness manifest must belong to project root");
        }
        String name = config.projectName().orElseThrow(() ->
                new IllegalArgumentException("actor witness requires project.name"));
        String version = config.projectVersion().orElseThrow(() ->
                new IllegalArgumentException("actor witness requires project.version"));
        if (!name.matches("[A-Za-z][A-Za-z0-9._-]{0,127}")
                || !version.matches("[0-9A-Za-z][0-9A-Za-z.+_-]{0,127}")) {
            throw new IllegalArgumentException("invalid actor witness package name/version");
        }
        this.packageIdentity = name + "@" + version;
        this.manifestDigest = sha256(readBounded(manifest, MAX_MANIFEST_BYTES));
    }

    public String packageIdentity() { return packageIdentity; }

    public synchronized Receipt build() throws IOException {
        requireOrdinaryPath(manifest, false);
        requireOrdinaryPath(root, true);
        if (!Arrays.equals(manifestDigest, sha256(readBounded(manifest, MAX_MANIFEST_BYTES)))) {
            throw new IllegalArgumentException(
                    "package manifest changed; reload actor contract witness builder");
        }

        Map<String, Path> sources = new TreeMap<>();
        Map<String, String> folded = new LinkedHashMap<>();
        final int[] scannedEntries = {0};
        for (Path declaredRoot : config.sourceRoots()) {
            Path sourceRoot = declaredRoot.toAbsolutePath().normalize();
            requireOrdinaryPath(sourceRoot, true);
            if (!sourceRoot.startsWith(root)) {
                throw new IllegalArgumentException(
                        "actor witness source root escapes manifest project: " + sourceRoot);
            }
            Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(
                        Path dir, BasicFileAttributes attrs) {
                    if (++scannedEntries[0] > MAX_VISITED_ENTRIES
                            || sourceRoot.relativize(dir).getNameCount() > MAX_SOURCE_DEPTH) {
                        throw new IllegalArgumentException(
                                "actor witness source directory budget/depth exceeded");
                    }
                    requireOrdinaryPath(dir, true);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) {
                    if (++scannedEntries[0] > MAX_VISITED_ENTRIES) {
                        throw new IllegalArgumentException(
                                "actor witness scanned-entry budget exceeded");
                    }
                    if (Files.isSymbolicLink(path)) {
                        throw new IllegalArgumentException(
                                "symlink inside manifest actor source root: " + path);
                    }
                    String filename = path.getFileName().toString();
                    if (!filename.endsWith(".atto") && !filename.endsWith(".ores")) {
                        return FileVisitResult.CONTINUE;
                    }
                    requireOrdinaryPath(path, false);
                    if (!path.startsWith(root)) {
                        throw new IllegalArgumentException("actor source escaped project root");
                    }
                    String id = root.relativize(path).toString().replace(
                            java.io.File.separatorChar, '/');
                    String prior = folded.putIfAbsent(
                            id.toLowerCase(Locale.ROOT), id);
                    if (prior != null && !prior.equals(id)) {
                        throw new IllegalArgumentException(
                                "case-folded actor source collision: " + prior + " vs " + id);
                    }
                    if (sources.putIfAbsent(id, path) != null) {
                        throw new IllegalArgumentException(
                                "overlapping manifest source roots: " + id);
                    }
                    if (sources.size() > MAX_FILES) {
                        throw new IllegalArgumentException(
                                "actor witness source set exceeds 4096 files");
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        if (sources.isEmpty()) {
            throw new IllegalArgumentException(
                    "manifest actor package has no source units");
        }
        Map<String, Ast.Program> parsed = new TreeMap<>();
        Map<String, String> sourceHashes = new TreeMap<>();
        long totalBytes = 0;
        for (var entry : sources.entrySet()) {
            byte[] bytes = readBounded(entry.getValue(), MAX_FILE_BYTES);
            totalBytes += bytes.length;
            if (totalBytes > MAX_SOURCE_BYTES) {
                throw new IllegalArgumentException(
                        "manifest actor source aggregate exceeds 256 MiB");
            }
            String source;
            try {
                source = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (java.nio.charset.CharacterCodingException invalid) {
                throw new IllegalArgumentException(
                        "actor source must be valid UTF-8: " + entry.getKey(), invalid);
            }
            parsed.put(entry.getKey(), Parser.parse(source));
            sourceHashes.put(entry.getKey(), hex(sha256(bytes)));
        }

        // Check the manifest again rather than silently promoting an outdated
        // caller-selected package identity. File tree race safety is separately
        // bounded by the immutable-checkout requirement in the class contract.
        if (!Arrays.equals(manifestDigest, sha256(readBounded(manifest, MAX_MANIFEST_BYTES)))) {
            throw new IllegalArgumentException(
                    "manifest changed while checking actor contract source set");
        }
        var linked = ActorContractWitness.linkSourceSet(packageIdentity, parsed);
        if (linked.actors().isEmpty()) {
            throw new IllegalArgumentException(
                    "manifest actor witness package contains no declaration-only actors");
        }
        return new Receipt(linked, sourceHashes, hex(manifestDigest));
    }

    private static byte[] readBounded(Path file, int cap) throws IOException {
        requireOrdinaryPath(file, false);
        try (var input = Files.newInputStream(file,
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             var buffer = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[8192];
            int size;
            while ((size = input.read(chunk)) != -1) {
                if (buffer.size() > cap - size) {
                    throw new IllegalArgumentException(
                            "oversize actor witness source/manifest: " + file);
                }
                buffer.write(chunk, 0, size);
            }
            return buffer.toByteArray();
        }
    }

    private static void requireOrdinaryPath(Path path, boolean directory) {
        Path absolute = path.toAbsolutePath().normalize();
        Path cursor = absolute.getRoot();
        for (Path segment : absolute) {
            cursor = cursor.resolve(segment);
            if (Files.isSymbolicLink(cursor)) {
                throw new IllegalArgumentException(
                        "symlink path component forbidden for actor witnesses: " + cursor);
            }
        }
        if (directory ? !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)
                : !Files.isRegularFile(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "actor witness path must be an ordinary "
                            + (directory ? "directory: " : "file: ") + absolute);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 missing", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
