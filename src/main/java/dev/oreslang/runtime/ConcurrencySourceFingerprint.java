package dev.oreslang.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Conservative workspace-wide source fingerprint for experimental replay.
 *
 * This covers MORE than the current entrypoint, but is not the compiler's
 * transitive import graph and is not a trusted compiler/binary build receipt.
 * Any import outside the supplied workspace must be separately pinned.
 */
public final class ConcurrencySourceFingerprint {
    private static final int MAX_SOURCE_FILES = 10_000;
    private static final int MAX_VISITED_NODES = 250_000;
    private static final long MAX_SOURCE_BYTES = 64L * 1024 * 1024;

    private ConcurrencySourceFingerprint() { }

    private static boolean included(Path root, Path path) {
        for (Path segment : root.relativize(path)) {
            String part = segment.toString();
            if (part.equals(".git") || part.equals("target") || part.equals("build")
                    || part.equals("dist") || part.equals("node_modules")
                    || part.equals(".oreslang") || part.equals(".venv")
                    || part.equals("__pycache__")) {
                return false;
            }
        }
        String name = path.getFileName().toString();
        return name.endsWith(".ores") || name.endsWith(".toml")
                || name.endsWith(".wit") || name.endsWith(".tsp")
                || name.endsWith(".java") || name.endsWith(".c")
                || name.endsWith(".cpp") || name.endsWith(".h")
                || name.endsWith(".hpp") || name.endsWith(".rs")
                || name.endsWith(".py") || name.endsWith(".xml")
                || name.endsWith(".json") || name.endsWith(".yaml")
                || name.endsWith(".yml") || name.endsWith(".gradle");
    }

    public static String ofWorkspace(Path root) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("source workspace must be a real directory");
        }
        Path anchor = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        List<Path> sourceFiles = new ArrayList<>();
        // Never walk generated or dependency directories: they may be huge and
        // must not silently influence guest source replay provenance.
        Files.walkFileTree(anchor, new SimpleFileVisitor<>() {
            private int visited;

            private void tickWalkBudget() {
                if (++visited > MAX_VISITED_NODES) {
                    throw new IllegalArgumentException("source workspace traversal budget exceeded");
                }
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                tickWalkBudget();
                if (!dir.equals(anchor)) {
                    if (anchor.relativize(dir).getNameCount() > 48) {
                        throw new IOException("source workspace nesting budget exceeded");
                    }
                    String name = dir.getFileName().toString();
                    if (name.equals(".git") || name.equals("target") || name.equals("build")
                            || name.equals("dist") || name.equals("node_modules")
                            || name.equals(".oreslang") || name.equals(".venv")
                            || name.equals("__pycache__")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                tickWalkBudget();
                // Never silently omit a symlinked directory containing source
                // dependencies, even if the symlink itself has no source suffix.
                if (attrs.isSymbolicLink()) {
                    throw new IllegalArgumentException("source workspace contains a symlink: " + file);
                }
                if (included(anchor, file)) {
                    sourceFiles.add(file);
                    if (sourceFiles.size() > MAX_SOURCE_FILES) {
                        throw new IllegalArgumentException("source workspace file-count admission failed");
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });
        sourceFiles.sort(Comparator.comparing(
                path -> anchor.relativize(path).toString()));
        if (sourceFiles.isEmpty() || sourceFiles.size() > MAX_SOURCE_FILES) {
            throw new IllegalArgumentException("source workspace file-count admission failed");
        }
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        digest.update("ORES_WORKSPACE_SOURCE_V1\0".getBytes(StandardCharsets.UTF_8));
        long total = 0;
        byte[] buffer = new byte[8192];
        for (Path path : sourceFiles) {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("symlink or non-regular source in workspace: " + path);
            }
            long size = Files.size(path);
            if (size > MAX_SOURCE_BYTES - total) {
                throw new IllegalArgumentException("source workspace byte budget exceeded");
            }
            total += size;
            String relative = anchor.relativize(path).toString().replace('\\', '/');
            digest.update(relative.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(Long.toString(size).getBytes(StandardCharsets.US_ASCII));
            digest.update((byte) 0);
            // NOFOLLOW_LINKS also closes the check/open symlink race.
            try (InputStream in = Channels.newInputStream(Files.newByteChannel(
                    path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)))) {
                int bytes;
                long read = 0;
                while ((bytes = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytes);
                    read += bytes;
                    if (read > size) {
                        throw new IOException("source mutated during fingerprint calculation");
                    }
                }
                if (read != size || Files.size(path) != size) {
                    throw new IOException("source mutated during fingerprint calculation");
                }
            }
            digest.update((byte) 0);
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
}
