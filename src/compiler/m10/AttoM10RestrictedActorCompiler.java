// Restricted AttoLang -> Java source compiler used only by the M10 IPC pilot.
// This is not the canonical Atto frontend: accept exactly one scalar actor
// form and fail closed for all other syntax. No Python or interpretation.
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

public final class AttoM10RestrictedActorCompiler {
    private static final Pattern ACCEPTED = Pattern.compile(
        "\\A\\s*define\\s+actor\\s+RemoteAdder\\s+as\\s*\\{\\s*" +
        "pub\\s+fnc\\s+on_message\\s*\\(\\s*int\\s+value\\s*\\)" +
        "\\s*:\\s*int\\s*\\{\\s*return\\s+value\\s*\\+\\s*" +
        "([0-9]{1,9})\\s*;\\s*\\}\\s*\\}\\s*\\z");
    private AttoM10RestrictedActorCompiler() {}

    public static String compile_source(String input) {
        if (input == null || input.length() > 4096)
            throw new IllegalArgumentException("bounded M10 guest source required");
        Matcher accepted = ACCEPTED.matcher(input);
        if (!accepted.matches())
            throw new IllegalArgumentException(
                "M10 supports only RemoteAdder.on_message(int value) scalar addition");
        long amount = Long.parseLong(accepted.group(1));
        if (amount < 1 || amount > 999_999_999L)
            throw new IllegalArgumentException("unsupported increment");
        final String sha;
        try {
            sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException fail) { throw new AssertionError(fail); }
        return """
            // Java source GENERATED FROM restricted .atto actor syntax.
            // Does NOT implement general Atto actor grammar, compiler or TenantVM.
            public final class M10GeneratedActor {
                public static final String SOURCE_SHA256 = "%s";
                private M10GeneratedActor() {}
                public static long on_message(long value) {
                    return Math.addExact(value, %dL);
                }
                public static AttoM6Runtime.ReceiveWithResult new_handler() {
                    return M10GeneratedActor::on_message;
                }
            }
            """.formatted(sha, amount);
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
            !args[1].endsWith("M10GeneratedActor.java"))
            throw new IllegalArgumentException("usage: compiler source.atto M10GeneratedActor.java");
        Path source = Path.of(args[0]);
        Path output = Path.of(args[1]);
        // CREATE_NEW + NOFOLLOW_LINKS prevents overwrites and redirection to
        // a dangling symlink, even if another process races this compiler.
        String generated = compile_source(read_source(source));
        try (var writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            writer.write(generated);
        }
        System.out.println("M10_RESTRICTED_ATTO_GUEST_SOURCE_TRANSLATED=PASS");
    }
}
