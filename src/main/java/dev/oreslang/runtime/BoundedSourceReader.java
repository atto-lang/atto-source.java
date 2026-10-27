package dev.oreslang.runtime;

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

    public static ReadResult readWithByteCount(Path path) throws IOException {
        byte[] bytes = readSnapshot(path.toAbsolutePath().normalize());
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

    private static byte[] readSnapshot(Path absolute) throws IOException {
        Path root = absolute.getRoot();
        if (root != null) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
                if (stream instanceof SecureDirectoryStream<?> rawSecure) {
                    @SuppressWarnings("unchecked")
                    SecureDirectoryStream<Path> secure =
                            (SecureDirectoryStream<Path>) rawSecure;
                    Path relative = root.relativize(absolute);
                    if (relative.getNameCount() == 0) {
                        throw new IllegalArgumentException("Oreslang source path must name a file");
                    }
                    return readSecure(secure, relative, 0);
                }
            }
        }

        // Some providers (notably Windows) do not expose SecureDirectoryStream.
        // Still open the leaf with NOFOLLOW_LINKS so pathname replacement after
        // open cannot redirect the descriptor. Parent-component races cannot be
        // made fully descriptor-relative on such providers with portable NIO.
        try (SeekableByteChannel channel = Files.newByteChannel(
                absolute, READ_NOFOLLOW)) {
            return snapshotChannel(channel);
        }
    }

    private static byte[] readSecure(
            SecureDirectoryStream<Path> directory,
            Path relative,
            int index) throws IOException {
        Path component = relative.getName(index);
        if (index + 1 == relative.getNameCount()) {
            try (SeekableByteChannel channel =
                    directory.newByteChannel(component, READ_NOFOLLOW)) {
                return snapshotChannel(channel);
            }
        }

        try (SecureDirectoryStream<Path> child =
                directory.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS)) {
            return readSecure(child, relative, index + 1);
        }
    }

    /**
     * Read one already-open file object into an immutable bounded snapshot.
     * All size checks and byte reads use the same descriptor. A second pass
     * detects same-size mutation during the first copy instead of silently
     * compiling a mixed snapshot.
     */
    static byte[] snapshotChannel(SeekableByteChannel channel) throws IOException {
        long initialSize = channel.size();
        if (initialSize < 0 || initialSize > MAX_BYTES) {
            throw new IllegalArgumentException("Oreslang source exceeds 16 MiB source limit");
        }

        byte[] first = readExactPass(channel, (int) initialSize);
        if (channel.size() != initialSize) {
            throw new IllegalArgumentException("Oreslang source changed during snapshot");
        }

        channel.position(0);
        byte[] verify = readExactPass(channel, (int) initialSize);
        if (channel.size() != initialSize || !java.util.Arrays.equals(first, verify)) {
            throw new IllegalArgumentException("Oreslang source changed during snapshot");
        }
        return first;
    }

    private static byte[] readExactPass(
            SeekableByteChannel channel,
            int size) throws IOException {
        byte[] bytes = new byte[size];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                throw new IllegalArgumentException("Oreslang source changed during snapshot");
            }
        }

        ByteBuffer extra = ByteBuffer.allocate(1);
        if (channel.read(extra) >= 0) {
            throw new IllegalArgumentException("Oreslang source changed during snapshot");
        }
        return bytes;
    }
}
