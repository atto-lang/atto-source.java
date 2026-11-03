package dev.oreslang.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.nio.file.StandardOpenOption;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.DirectoryStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.Channels;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;

/** Single bounded, strict UTF-8 source admission boundary for the CLI and file loader. */
public final class BoundedSourceReader {
    public static final int MAX_BYTES = 16 * 1024 * 1024;

    private BoundedSourceReader() { }

    /** Immutable decoded source and its actual encoded length for closure-wide budgeting. */
    public record ReadResult(String text, int byteCount) { }

    public static String readUtf8(Path path) throws IOException {
        return readWithByteCount(path).text();
    }

    /** Fails closed where the platform cannot report a numeric owner. */
    private static boolean ownedByRoot(Path link) {
        try {
            return Integer.valueOf(0).equals(
                    Files.getAttribute(link, "unix:uid", LinkOption.NOFOLLOW_LINKS));
        } catch (IOException | UnsupportedOperationException | IllegalArgumentException unknown) {
            return false;
        }
    }

    public static ReadResult readWithByteCount(Path path) throws IOException {
        // AttoLang source admission is independent of the entrypoint used.
        // Reject a symlinked leaf and user-controlled symlinked ancestors before
        // opening the source: a direct CLI read must enforce the same policy as
        // linked imports. Root-owned ancestors are OS path aliases, such as /var
        // and /tmp on macOS, and rejecting them would refuse every path below.
        // NOFOLLOW_LINKS additionally prevents a leaf-link swap at open time.
        Path absolute = path.toAbsolutePath().normalize();
        boolean atto = absolute.getFileName() != null
                && absolute.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".atto");
        if (atto) {
            for (Path component = absolute; component != null; component = component.getParent()) {
                if (Files.isSymbolicLink(component)
                        && (component.equals(absolute) || !ownedByRoot(component))) {
                    throw new SecurityException("symlinked .atto source path is not allowed: " + component);
                }
            }
        }
        byte[] bytes;
        try (InputStream input = atto
                ? Files.newInputStream(absolute, LinkOption.NOFOLLOW_LINKS)
                : Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
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
     * Read a canonical AttoLang package manifest using the same pinned,
     * no-follow source-path admission as .atto source files. Caller chooses
     * its bounded maximum; unsupported filesystem providers fail closed.
     */
    public static String readPinnedAttoManifestUtf8(Path path, int maxBytes)
            throws IOException {
        if (maxBytes < 1 || maxBytes >= MAX_BYTES) {
            throw new IllegalArgumentException("invalid bounded AttoLang manifest size limit");
        }
        Path absolute = path.toAbsolutePath().normalize();
        if (absolute.getFileName() == null ||
                !absolute.getFileName().toString().equals(".atto.pkg.toml")) {
            throw new IllegalArgumentException("expected canonical .atto.pkg.toml");
        }
        for (Path component = absolute; component != null; component = component.getParent()) {
            if (Files.isSymbolicLink(component)) {
                throw new SecurityException("symlinked AttoLang package manifest path: " + component);
            }
        }
        byte[] bytes = readPinnedAtto(absolute, maxBytes);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("AttoLang manifest exceeds " + maxBytes + " bytes");
        }
        for (byte value : bytes) {
            if (value == 0) throw new IllegalArgumentException("AttoLang manifest contains NUL byte");
        }
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    /**
     * Reads a source through provider-pinned, no-follow directory handles.
     * Fail closed if the filesystem lacks SecureDirectoryStream support:
     * a preflight realpath check alone is not an atomic security boundary.
     */
    private static byte[] readPinnedAtto(Path absolute) throws IOException {
        return readPinnedAtto(absolute, MAX_BYTES);
    }

    private static byte[] readPinnedAtto(Path absolute, int maxBytes) throws IOException {
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
            return readPinnedComponent(pinnedRoot, relative, 0, maxBytes);
        }
    }

    private static byte[] readPinnedComponent(
            SecureDirectoryStream<Path> pinned, Path relative, int index, int maxBytes)
            throws IOException {
        Path part = relative.getName(index);
        if (index == relative.getNameCount() - 1) {
            try (SeekableByteChannel channel = pinned.newByteChannel(part,
                    Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 InputStream input = Channels.newInputStream(channel)) {
                return input.readNBytes(maxBytes + 1);
            }
        }
        try (SecureDirectoryStream<Path> next =
                     pinned.newDirectoryStream(part, LinkOption.NOFOLLOW_LINKS)) {
            return readPinnedComponent(next, relative, index + 1, maxBytes);
        }
    }

}
