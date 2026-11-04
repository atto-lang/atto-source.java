package dev.oreslang.runtime;

import java.io.IOException;
import java.io.InputStream;
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
}
