package dev.oreslang.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;

/** Single bounded, strict UTF-8 source admission boundary for the CLI and file loader. */
public final class BoundedSourceReader {
    public static final int MAX_BYTES = 16 * 1024 * 1024;

    private BoundedSourceReader() { }

    /** Immutable decoded source and its actual encoded length for closure-wide budgeting. */
    public record ReadResult(String text, int byteCount) { }

    public static String readUtf8(Path path) throws IOException {
        return readWithByteCount(path).text();
    }

    public static ReadResult readWithByteCount(Path path) throws IOException {
        // AttoLang source admission is independent of the entrypoint used.
        // Reject symlinked leaf AND ancestor paths before opening the source:
        // a direct CLI read must enforce the same policy as linked imports.
        // NOFOLLOW_LINKS additionally prevents a leaf-link swap at open time.
        Path absolute = path.toAbsolutePath().normalize();
        boolean atto = absolute.getFileName() != null
                && absolute.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".atto");
        if (atto) {
            for (Path component = absolute; component != null; component = component.getParent()) {
                if (Files.isSymbolicLink(component)) {
                    throw new SecurityException("symlinked .atto source path is not allowed: " + component);
                }
            }
        }
        byte[] bytes;
        if (atto) {
            // Directory handles, not pathname re-resolution, pin every parent.
            // An attacker cannot swap a checked parent for a symlink between
            // admission and the eventual file open.
            bytes = readPinnedAtto(absolute);
        } else {
            try (InputStream input = Files.newInputStream(path)) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
        }
        if (bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("Oreslang source exceeds 16 MiB source limit");
        }
        // Reject binary source before lexical analysis (aligns with LLVM/Forge).
        for (byte value : bytes) {
            if (value == 0) {
                throw new IllegalArgumentException("Oreslang source contains NUL byte");
            }
        }
        String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        return new ReadResult(text, bytes.length);
    }
    /**
     * Reads a source through provider-pinned, no-follow directory handles.
     * Fail closed if the filesystem lacks SecureDirectoryStream support:
     * a preflight realpath check alone is not an atomic security boundary.
     */
    private static byte[] readPinnedAtto(Path absolute) throws IOException {
        Path root = absolute.getRoot();
        if (root == null) {
            throw new SecurityException("absolute .atto source root is required");
        }
        Path relative = root.relativize(absolute);
        if (relative.getNameCount() == 0) {
            throw new SecurityException(".atto source filename is required");
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            if (!(stream instanceof SecureDirectoryStream<Path> pinnedRoot)) {
                throw new SecurityException(
                        "secure directory handles unavailable for .atto source: " + root);
            }
            return readPinnedComponent(pinnedRoot, relative, 0);
        }
    }

    private static byte[] readPinnedComponent(
            SecureDirectoryStream<Path> pinned, Path relative, int index) throws IOException {
        Path part = relative.getName(index);
        if (index == relative.getNameCount() - 1) {
            try (SeekableByteChannel channel = pinned.newByteChannel(part,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 InputStream input = Channels.newInputStream(channel)) {
                return input.readNBytes(MAX_BYTES + 1);
            }
        }
        try (SecureDirectoryStream<Path> next =
                     pinned.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS)) {
            return readPinnedComponent(next, relative, index + 1);
        }
    }

}
