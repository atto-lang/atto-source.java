package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.config.OresProjectConfig;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.SharedCodeImageStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Closed physical-package admission for NamespaceActorIngressV1.
 *
 * Parses each original .atto file, retains its real parsed AST and lexical
 * critical-MIR token/kind provenance, then builds one immutable root module.
 * The existing generated ingress, typed MIR, Truffle and actor runtime do the
 * execution. This class NEVER converts attached state into ordinary fields.
 *
 * This is an opt-in, deliberately limited *five-source-file* package format.
 * It is not a general import linker, a signed manifest, or a complete guest
 * compiler. Concurrent hostile filesystem renames still require a secure
 * directory-descriptor snapshot mechanism before production admission.
 */
public final class NamespaceActorManifestV1 {
    private NamespaceActorManifestV1() { }

    private static final int MAX_FILES = 5;
    private static final int MAX_ENTRIES = 256;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_TOTAL_BYTES = 5 * 1024 * 1024;

    public record Verified(
            String packageIdentity, String namespace,
            Map<String, String> sourceSha256, String manifestSha256,
            NamespaceActorIngressV1.Plan plan) {
        public Verified {
            sourceSha256 = Collections.unmodifiableMap(
                    new LinkedHashMap<>(sourceSha256));
            Objects.requireNonNull(plan, "compiled actor ingress plan");
        }
    }

    private static IllegalArgumentException denied(String explanation) {
        return new IllegalArgumentException(
                "NAMESPACE_ACTOR_MANIFEST_V1_REJECTED: " + explanation);
    }

    /**
     * The package name/version and complete, symlink-free source set are
     * selected by the canonical manifest, not by a caller-provided Map.
     * Each source file must have one root declaration in exactly one namespace.
     */
    public static Verified compile(
            Path manifest, SharedCodeImageStore store, String actorName)
            throws IOException {
        Objects.requireNonNull(manifest, "canonical package manifest");
        Objects.requireNonNull(store, "code-image store");
        Objects.requireNonNull(actorName, "nominal actor name");
        if (actorName.isBlank()) throw denied("empty nominal actor name");

        Path manifestPath = manifest.toAbsolutePath().normalize();
        if (!manifestPath.getFileName().toString()
                .equals(OresProjectConfig.ATTO_MANIFEST_NAME)) {
            throw denied("canonical .atto.pkg.toml is mandatory");
        }
        byte[] manifestBytes = boundedRead(manifestPath, MAX_MANIFEST_BYTES);
        OresProjectConfig config = OresProjectConfig.load(manifestPath, Map.of());
        Path projectRoot = manifestPath.getParent();
        if (!config.projectRoot().toAbsolutePath().normalize().equals(projectRoot)) {
            throw denied("project.root must be the manifest's own directory");
        }
        String projectName = config.projectName().orElse("");
        String projectVersion = config.projectVersion().orElse("");
        if (!projectName.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")
                || !projectVersion.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw denied("bounded project.name and project.version are required");
        }
        String packageIdentity = projectName + "@" + projectVersion;
        Path sourceRoot = projectRoot.resolve("src");
        if (config.sourceRoots().size() != 1
                || !config.sourceRoots().getFirst().toAbsolutePath()
                        .normalize().equals(sourceRoot)
                || !config.importPaths().isEmpty()
                || !config.environmentPaths().isEmpty()
                || config.mainEntrypoint().isPresent()) {
            throw denied("v1 permits only the project-local src root, without imports or ambient paths");
        }
        if (Files.isSymbolicLink(sourceRoot)
                || !Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw denied("source root must be a real, non-symlink directory");
        }
        if (!sourceRoot.toRealPath().startsWith(projectRoot.toRealPath())) {
            throw denied("source root resolves outside the package");
        }

        TreeMap<String, Path> sources = new TreeMap<>();
        int[] visited = {0};
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(
                    Path dir, BasicFileAttributes attributes) {
                if (++visited[0] > MAX_ENTRIES
                        || sourceRoot.relativize(dir).getNameCount() > MAX_DEPTH
                        || attributes.isSymbolicLink()
                        || Files.isSymbolicLink(dir)) {
                    throw denied("symlink, depth or directory-entry budget exceeded");
                }
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(
                    Path file, BasicFileAttributes attributes) {
                if (++visited[0] > MAX_ENTRIES
                        || attributes.isSymbolicLink()
                        || Files.isSymbolicLink(file)) {
                    throw denied("symlink or source-entry budget exceeded");
                }
                String filename = file.getFileName().toString();
                if (!filename.endsWith(".atto") && !filename.endsWith(".ores")) {
                    return FileVisitResult.CONTINUE;
                }
                if (!filename.endsWith(".atto")
                        || !attributes.isRegularFile()
                        || attributes.size() > MAX_FILE_BYTES) {
                    throw denied("only bounded regular .atto sources are permitted");
                }
                String sourceId = projectRoot.relativize(file)
                        .toString().replace('\\', '/');
                if (sources.putIfAbsent(sourceId, file) != null
                        || sources.size() > MAX_FILES) {
                    throw denied("duplicate or excessive source units");
                }
                return FileVisitResult.CONTINUE;
            }
        });
        if (sources.size() != MAX_FILES) {
            throw denied("exactly five physical actor constituent files are required");
        }

        Map<String, String> sourceHashes = new LinkedHashMap<>();
        List<Ast.Decl> declarations = new ArrayList<>();
        String namespace = null;
        int totalBytes = 0;
        for (var entry : sources.entrySet()) {
            String id = entry.getKey();
            byte[] bytes = boundedRead(entry.getValue(), MAX_FILE_BYTES);
            totalBytes = Math.addExact(totalBytes, bytes.length);
            if (totalBytes > MAX_TOTAL_BYTES) {
                throw denied("total package source byte budget exceeded");
            }
            String text;
            try {
                text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException malformed) {
                throw denied("invalid UTF-8 source unit " + id);
            }
            Ast.Program parsed = Parser.parse(text);
            if (parsed.namespace() == null || parsed.namespace().isBlank()
                    || !parsed.imports().isEmpty()
                    || parsed.modules().size() != 1) {
                throw denied("every source unit needs one explicit namespace and no imports: " + id);
            }
            if (namespace == null) namespace = parsed.namespace();
            if (!namespace.equals(parsed.namespace())) {
                throw denied("cross-namespace constituent is not an actor implementation: " + id);
            }
            Ast.ModuleDecl module = parsed.modules().getFirst();
            if (!module.name().equals(Parser.ROOT_MODULE)
                    || !module.annotations().isEmpty()
                    || !module.contracts().isEmpty()
                    || module.declarations().size() != 1) {
                throw denied("each file must hold exactly one root actor constituent: " + id);
            }
            declarations.add(module.declarations().getFirst());
            sourceHashes.put(id, hex(digest(bytes)));
            if (!Arrays.equals(bytes, boundedRead(entry.getValue(), MAX_FILE_BYTES))) {
                throw denied("source unit changed while parsing: " + id);
            }
        }
        if (!Arrays.equals(manifestBytes, boundedRead(manifestPath, MAX_MANIFEST_BYTES))) {
            throw denied("manifest changed during source snapshot");
        }
        // The five permitted declaration shapes and all method/state effects
        // are validated again by the existing typed ingress and critical MIR.
        Ast.Program joined = new Ast.Program(namespace, List.of(),
                List.of(new Ast.ModuleDecl(Parser.ROOT_MODULE, declarations)));

        // Content-address the published program. Reusing a stale code image
        // for new source bytes cannot silently inherit old execution rights.
        StringBuilder receipt = new StringBuilder(packageIdentity).append('\n')
                .append(namespace).append('\n').append(hex(digest(manifestBytes)));
        sourceHashes.forEach((id, hash) -> receipt.append('\n')
                .append(id).append('=').append(hash));
        String codeUnitId = "src/__manifest_actor_v1_"
                + hex(digest(receipt.toString().getBytes(StandardCharsets.UTF_8)))
                + ".atto";
        var image = store.publish(codeUnitId, joined);
        NamespaceActorIngressV1.Plan compiled =
                NamespaceActorIngressV1.compile(image, packageIdentity, actorName);
        return new Verified(packageIdentity, namespace, sourceHashes,
                hex(digest(manifestBytes)), compiled);
    }

    private static byte[] boundedRead(Path file, int limit) throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > limit) {
            throw denied("missing, symlinked or oversized package file: " + file);
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > limit) throw denied("package file grew past its byte budget");
        return bytes;
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is mandatory", failure);
        }
    }

    private static String hex(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(bytes);
    }
}
