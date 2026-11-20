// A deliberately narrow Atto source -> Java compiler for an IPC sender.
// NOT the canonical Atto compiler, general ProxyActor lowering, or TenantVM.
// Accept exactly one preview grammar; all other input fails closed. The
// destination, authority, tenant and transport credentials are HOST-BOUND.
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !args[0].endsWith(".atto") ||
            !args[1].endsWith("M10GeneratedSender.java"))
            throw new IllegalArgumentException(
                "usage: compiler source.atto M10GeneratedSender.java");
        Path source = Path.of(args[0]);
        Path output = Path.of(args[1]);
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source))
            throw new IOException("untrusted sender source path");
        if (Files.exists(output))
            throw new IOException("refuse to overwrite source artifact");
        Files.writeString(output,
            compile_source(Files.readString(source, StandardCharsets.UTF_8)),
            StandardCharsets.UTF_8);
        System.out.println("M10_RESTRICTED_ATTO_SENDER_SOURCE_TRANSLATED=PASS");
    }
}
