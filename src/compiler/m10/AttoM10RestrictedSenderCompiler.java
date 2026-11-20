// A deliberately narrow Atto source -> Java compiler for an IPC sender.
// NOT the canonical Atto compiler, general ProxyActor lowering, or TenantVM.
// Accept exactly one preview grammar; all other input fails closed. The
// destination, authority, tenant and transport credentials are HOST-BOUND.
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.util.Set;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AttoM10RestrictedSenderCompiler {
    private static final Pattern ACCEPTED = Pattern.compile(
        "\\A\\s*define\\s+proxy\\s+actor\\s+RemoteAdder\\s+" +
        "conforms\\s+to\\s+RemoteAdderContract\\s+" +
        "discover\\s+\"remote.add.v1\"\\s+" +
        "transport\\s+ipc\\s+as\\s+end\\s+" +
        "pub\\s+susp\\s+fnc\\s+main\\s*\\(\\s*" +
        "ProxyActor\\s*<\\s*RemoteAdder\\s*>\\s+proxy\\s*\\)" +
        "\\s*:\\s*int\\s*\\{\\s*" +
        "return\\s+await\\s+proxy\\.request\\s*\\(\\s*" +
        "([0-9]{1,9})\\s*\\)\\s*;\\s*\\}\\s*\\z");

    private AttoM10RestrictedSenderCompiler() {}

    public static String compile_source(String input) {
        if (input == null || input.length() > 4096)
            throw new IllegalArgumentException("bounded M10 sender source required");
        Matcher m = ACCEPTED.matcher(input);
        if (!m.matches())
            throw new IllegalArgumentException("unsupported M10 proxy sender syntax");
        long argument = Long.parseLong(m.group(1));
        if (argument < 0 || argument > 999_999_999L)
            throw new IllegalArgumentException("invalid bounded scalar request");
        final String sha;
        try {
            sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        return """
            // GENERATED from a bounded, subset-compiled Atto source sender.
            // Host callback is an injected, explicitly authorized association;
            // this does not implement canonical Atto suspension or a TenantVM.
            public final class M10GeneratedSender {
                public static final String SOURCE_SHA256 = "%s";
                private M10GeneratedSender() {}
                public static long main(AttoM10TrustedScalarProxy authorized_proxy)
                        throws java.io.IOException {
                    if (authorized_proxy == null)
                        throw new IllegalArgumentException("unbound remote proxy");
                    return authorized_proxy.request(%dL);
                }
            }
            """.formatted(sha, argument);
    }

    // Read through one NOFOLLOW_LINKS file descriptor. A pre-check alone is
    // vulnerable to a symlink replacement between isSymbolicLink and readString.
    // Bound bytes BEFORE constructing a String; invalid UTF-8 fails closed.
    private static String read_source(Path source) throws IOException {
        if (Files.isSymbolicLink(source) ||
            !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("untrusted sender source path");
        try (SeekableByteChannel channel = Files.newByteChannel(source,
                Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            if (channel.size() > 4096)
                throw new IOException("oversized sender source");
            ByteBuffer bytes = ByteBuffer.allocate(4097);
            while (bytes.hasRemaining()) {
                int count = channel.read(bytes);
                if (count < 0) break;
                if (count == 0) throw new IOException("sender source stalled");
            }
            if (bytes.position() > 4096)
                throw new IOException("oversized sender source");
            bytes.flip();
            try {
                return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(bytes).toString();
            } catch (CharacterCodingException malformed) {
                throw new IOException("malformed UTF-8 in sender source", malformed);
            }
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !args[0].endsWith(".atto") ||
            !args[1].endsWith("M10GeneratedSender.java"))
            throw new IllegalArgumentException(
                "usage: compiler source.atto M10GeneratedSender.java");
        Path source = Path.of(args[0]);
        Path output = Path.of(args[1]);
        // CREATE_NEW is atomic even if an attacker supplies a dangling link;
        // NOFOLLOW also rejects symlinks at the final output component.
        String generated = compile_source(read_source(source));
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            writer.write(generated);
        }
        System.out.println("M10_RESTRICTED_ATTO_SENDER_SOURCE_TRANSLATED=PASS");
    }
}
