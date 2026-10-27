package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Single bounded, strict UTF-8 source admission boundary for the CLI and file loader. */
public final class BoundedSourceReader {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final Set<OpenOption> READ_NOFOLLOW =
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);

    private BoundedSourceReader() { }

    /** Immutable decoded source and its actual encoded length for closure-wide budgeting. */
    public record ReadResult(String text, int byteCount) { }

    public static String readUtf8(Path path) throws IOException {
        return readWithByteCount(path).text();
    }

    /**
     * Resolves the caller spelling once, then snapshots that physical pathname
     * without following any symlink during the actual file open.
     */
    public static ReadResult readWithByteCount(Path path) throws IOException {
        if (path == null) throw new IllegalArgumentException("source path cannot be null");
        return readPhysicalWithByteCount(path.toRealPath());
    }

    /**
     * Reads an already-canonical physical source path.
     *
     * On providers exposing SecureDirectoryStream, every parent lookup and the
     * final no-follow open are descriptor-relative. Other providers still use
     * no-follow leaf opens plus pre/post component checks and fail closed when
     * a symlink or non-regular source is observed.
     */
    public static ReadResult readPhysicalWithByteCount(Path physicalPath) throws IOException {
        return readPhysicalWithByteCount(physicalPath, null);
    }

    /**
     * Reads an already-canonical physical source and, when supplied, requires
     * it to match the resolver's earlier identity snapshot before any bytes
     * are accepted.
     */
    public static ReadResult readPhysicalWithByteCount(
            Path physicalPath,
            BasicFileAttributes expectedIdentity) throws IOException {
        if (physicalPath == null) throw new IllegalArgumentException("source path cannot be null");
        Path absolute = physicalPath.toAbsolutePath().normalize();
        Path root = absolute.getRoot();
        if (root == null || absolute.getNameCount() == 0) {
            throw new IllegalArgumentException("source path must name a file: " + absolute);
        }

        try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(root)) {
            if (rootStream instanceof SecureDirectoryStream<?> rawSecure) {
                @SuppressWarnings("unchecked")
                SecureDirectoryStream<Path> secureRoot = (SecureDirectoryStream<Path>) rawSecure;
                return readSecure(secureRoot, absolute, expectedIdentity);
            }
        }
        return readPortableNoFollow(absolute, expectedIdentity);
    }

    private static ReadResult readSecure(
            SecureDirectoryStream<Path> secureRoot,
            Path absolute,
            BasicFileAttributes expectedIdentity) throws IOException {
        List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
        opened.add(secureRoot);
        SecureDirectoryStream<Path> current = secureRoot;
        try {
            for (int index = 0; index + 1 < absolute.getNameCount(); index++) {
                Path component = absolute.getName(index);
                SecureDirectoryStream<Path> next =
                        current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                opened.add(next);
                current = next;
            }

            Path leaf = absolute.getFileName();
            BasicFileAttributeView view = current.getFileAttributeView(
                    leaf, BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                throw new IOException("filesystem cannot provide no-follow source attributes");
            }
            BasicFileAttributes before = view.readAttributes();
            requireRegularBounded(before, absolute);
            requireExpectedIdentity(expectedIdentity, before, absolute);

            byte[] bytes;
            try (SeekableByteChannel channel = current.newByteChannel(leaf, READ_NOFOLLOW)) {
                bytes = readBounded(channel);
            }

            BasicFileAttributes after = view.readAttributes();
            requireStableSnapshot(before, after, bytes.length, absolute);
            return decode(bytes);
        } finally {
            // rootStream is closed by the caller. Close only descendants here.
            for (int index = opened.size() - 1; index >= 1; index--) {
                opened.get(index).close();
            }
        }
    }

    private static ReadResult readPortableNoFollow(
            Path absolute,
            BasicFileAttributes expectedIdentity) throws IOException {
        requireNoSymlinkComponents(absolute);
        BasicFileAttributes before = Files.readAttributes(
                absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        requireRegularBounded(before, absolute);
        requireExpectedIdentity(expectedIdentity, before, absolute);

        byte[] bytes;
        try (SeekableByteChannel channel = Files.newByteChannel(absolute, READ_NOFOLLOW)) {
            bytes = readBounded(channel);
        }

        requireNoSymlinkComponents(absolute);
        BasicFileAttributes after = Files.readAttributes(
                absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        requireStableSnapshot(before, after, bytes.length, absolute);
        return decode(bytes);
    }

    private static void requireNoSymlinkComponents(Path absolute) throws IOException {
        Path current = absolute.getRoot();
        for (int index = 0; index < absolute.getNameCount(); index++) {
            current = current.resolve(absolute.getName(index));
            BasicFileAttributes attributes = Files.readAttributes(
                    current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink()) {
                throw new IllegalArgumentException(
                        "source path contains a symbolic-link component: " + current);
            }
            if (index + 1 < absolute.getNameCount() && !attributes.isDirectory()) {
                throw new IllegalArgumentException(
                        "source path contains a non-directory component: " + current);
            }
        }
    }

    private static void requireRegularBounded(
            BasicFileAttributes attributes,
            Path path) {
        if (!attributes.isRegularFile()) {
            throw new IllegalArgumentException(
                    "Oreslang source must be a regular no-follow file: " + path);
        }
        if (attributes.size() < 0 || attributes.size() > MAX_BYTES) {
            throw new IllegalArgumentException("Oreslang source exceeds 16 MiB source limit");
        }
    }

    private static byte[] readBounded(SeekableByteChannel channel) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(8192);
        int total = 0;
        for (;;) {
            int count = channel.read(buffer);
            if (count < 0) break;
            if (count == 0) {
                throw new IOException("zero-byte read while snapshotting regular source file");
            }
            total += count;
            if (total > MAX_BYTES) {
                throw new IllegalArgumentException("Oreslang source exceeds 16 MiB source limit");
            }
            output.write(buffer.array(), 0, count);
            buffer.clear();
        }
        return output.toByteArray();
    }

    private static void requireExpectedIdentity(
            BasicFileAttributes expected,
            BasicFileAttributes observed,
            Path path) {
        if (expected == null) return;
        requireRegularBounded(expected, path);
        if (!Objects.equals(expected.fileKey(), observed.fileKey())
                || expected.size() != observed.size()
                || !expected.lastModifiedTime().equals(observed.lastModifiedTime())
                || !expected.creationTime().equals(observed.creationTime())) {
            throw new IllegalArgumentException(
                    "Oreslang source identity changed between resolution and snapshot: " + path);
        }
    }

    private static void requireStableSnapshot(
            BasicFileAttributes before,
            BasicFileAttributes after,
            int bytesRead,
            Path path) {
        requireRegularBounded(after, path);
        Object beforeKey = before.fileKey();
        Object afterKey = after.fileKey();
        if ((beforeKey != null && afterKey != null && !beforeKey.equals(afterKey))
                || before.size() != after.size()
                || bytesRead != before.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !before.creationTime().equals(after.creationTime())) {
            throw new IllegalArgumentException(
                    "Oreslang source changed during bounded snapshot: " + path);
        }
    }

    private static ReadResult decode(byte[] bytes) throws IOException {
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
}
