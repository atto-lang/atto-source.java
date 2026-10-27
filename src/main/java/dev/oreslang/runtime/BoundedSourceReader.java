package dev.oreslang.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

/** Single bounded, strict UTF-8 source admission boundary for the CLI and file loader. */
public final class BoundedSourceReader {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int READ_CHUNK_BYTES = 64 * 1024;

    private BoundedSourceReader() { }

    /** Immutable decoded source and its actual encoded length for closure-wide budgeting. */
    public record ReadResult(String text, int byteCount) { }

    public static String readUtf8(Path path) throws IOException {
        return readWithByteCount(path).text();
    }

    public static ReadResult readWithByteCount(Path path) throws IOException {
        if (path == null) throw new IllegalArgumentException("source path cannot be null");

        // Resolve caller aliases once, then open the canonical leaf with
        // NOFOLLOW_LINKS. This preserves supported symlink aliases while
        // preventing a validated leaf pathname from being swapped to a
        // symlink before the read is opened.
        Path physical = path.toAbsolutePath().normalize().toRealPath();
        BasicFileAttributes attributes = Files.readAttributes(
                physical, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IllegalArgumentException("Oreslang source must be a regular file: " + physical);
        }
        if (attributes.size() < 0 || attributes.size() > MAX_BYTES) {
            throw new IllegalArgumentException("Oreslang source exceeds 16 MiB source limit");
        }

        byte[] bytes;
        Set<java.nio.file.OpenOption> options =
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try (SeekableByteChannel channel = Files.newByteChannel(physical, options)) {
            long beforeSize = channel.size();
            if (beforeSize < 0 || beforeSize > MAX_BYTES) {
                throw new IllegalArgumentException("Oreslang source exceeds 16 MiB source limit");
            }

            ByteArrayOutputStream snapshot = new ByteArrayOutputStream(
                    (int) Math.min(beforeSize, READ_CHUNK_BYTES));
            ByteBuffer buffer = ByteBuffer.allocate(READ_CHUNK_BYTES);
            int total = 0;
            for (;;) {
                buffer.clear();
                int read = channel.read(buffer);
                if (read < 0) break;
                if (read == 0) continue;
                total = Math.addExact(total, read);
                if (total > MAX_BYTES) {
                    throw new IllegalArgumentException(
                            "Oreslang source exceeds 16 MiB source limit");
                }
                snapshot.write(buffer.array(), 0, read);
            }

            long afterSize = channel.size();
            if (afterSize != beforeSize || total != beforeSize) {
                throw new IllegalArgumentException(
                        "Oreslang source changed during bounded snapshot");
            }
            bytes = snapshot.toByteArray();
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
}
