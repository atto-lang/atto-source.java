package dev.oreslang.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Conservative workspace-wide source fingerprint for experimental replay.
 *
 * This covers MORE than the current entrypoint, but is not the compiler's
 * transitive import graph and is not a trusted compiler/binary build receipt.
 * Any import outside the supplied workspace must be separately pinned.
 */
public final class ConcurrencySourceFingerprint {
    private static final int MAX_SOURCE_FILES = 10_000;
    private static final long MAX_SOURCE_BYTES = 64L * 1024 * 1024;

    private ConcurrencySourceFingerprint() { }

    private static boolean included(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".ores") || name.endsWith(".toml")
                || name.endsWith(".wit") || name.endsWith(".tsp")
                || name.equals("pom.xml");
    }

    public static String ofWorkspace(Path root) throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
            throw new IllegalArgumentException("source workspace must be a real directory");
        }
        Path anchor = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        List<Path> sourceFiles;
        try (Stream<Path> paths = Files.walk(anchor)) {
            sourceFiles = paths.filter(ConcurrencySourceFingerprint::included)
                    .sorted(Comparator.comparing(path -> anchor.relativize(path).toString()))
                    .limit(MAX_SOURCE_FILES + 1L).toList();
        }
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
            try (InputStream in = Files.newInputStream(path)) {
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
