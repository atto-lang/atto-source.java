// Restricted executable Atto actor slice: source -> checked Java receive body.
// NOT the canonical frontend, proxy syntax, generic typed actor compiler,
// full Atto ownership/borrow checker, or a Native Image AOT claim.
// This compiler recognizes only one Counter-like i64 accumulator actor and
// *refuses* unrecognized tokens, methods, effects, types, or guest Java imports.
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AttoM6ProxyActorSubsetCompiler {
    private AttoM6ProxyActorSubsetCompiler() {}
    private static final Pattern SHA = Pattern.compile("[a-f0-9]{64}");
    private static final String IDENT = "[A-Za-z_][A-Za-z_0-9]*";
    private static final Pattern SUBSET = Pattern.compile(
        "\\A\\s*define\\s+actor\\s+(?<actor>" + IDENT + ")\\s+as\\s*\\{\\s*" +
        "let\\s+(?<field>" + IDENT + ")\\s*:\\s*int\\s*=\\s*(?<initial>-?[0-9]{1,18})\\s*;\\s*" +
        "pub\\s+fnc\\s+on_message\\s*\\(\\s*int\\s+(?<arg>" + IDENT + ")\\s*\\)" +
        "\\s*:\\s*int\\s*\\{\\s*" +
        "\\k<field>\\s*=\\s*\\k<field>\\s*\\+\\s*\\k<arg>\\s*;\\s*" +
        "return\\s+\\k<field>\\s*;\\s*\\}\\s*\\}\\s*\\z");
    public static final class Reject extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        Reject(String reason) { super(reason); }
    }
    public static String compile_source(String source) {
        if (source == null || source.length() > 4096)
            throw new Reject("E_PROXY_GUEST_SOURCE_SIZE");
        // This restricted slice has no string literals or nested block comments.
        // Line comments are removed; all remaining syntax must match the whitelist.
        String cleaned = source.replaceAll("(?m)//[^\\r\\n]*", "");
        Matcher m = SUBSET.matcher(cleaned);
        if (!m.matches()) throw new Reject("E_PROXY_GUEST_UNSUPPORTED_ACTOR_SOURCE");
        long initial;
        try { initial = Long.parseLong(m.group("initial")); }
        catch (NumberFormatException e) { throw new Reject("E_PROXY_GUEST_INT_RANGE"); }
        // A bounded statically generated receive, not a runtime source interpreter.
        return """
            // Generated from a verified restricted .atto actor source file.
            // All cross-process bytes and mailbox receipts remain host-owned.
            public final class AttoM6ProxyGuestCounter
                    implements java.util.function.LongUnaryOperator {
                private long total = %dL;
                @Override public long applyAsLong(long increment) {
                    total = Math.addExact(total, increment);
                    return total;
                }
            }
            """.formatted(initial);
    }
    public static int compile(Path source, String sha, Path output) {
        Path temporary = null;
        try {
            if (source == null || output == null || sha == null || !SHA.matcher(sha).matches()
                || !source.toString().endsWith(".atto")
                || Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
                throw new Reject("E_PROXY_GUEST_SOURCE_ADMISSION");
            Path parent = output.toAbsolutePath().getParent();
            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                || Files.size(source) < 1 || Files.size(source) > 4096)
                throw new Reject("E_PROXY_GUEST_OUTPUT_ADMISSION");
            byte[] bytes = Files.readAllBytes(source);
            String found = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
            if (!MessageDigest.isEqual(found.getBytes(StandardCharsets.US_ASCII),
                    sha.getBytes(StandardCharsets.US_ASCII)))
                throw new Reject("E_PROXY_GUEST_SOURCE_HASH");
            String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            String generated = compile_source(text);
            temporary = Files.createTempFile(parent, ".atto-proxy-guest-", ".tmp");
            Files.writeString(temporary, generated, StandardCharsets.UTF_8);
            Files.createLink(output, temporary); // no target overwrite via link race
            System.out.println("ATTO_M6_PROXY_RESTRICTED_ATTO_COMPILED=PASS");
            System.out.println("ATTO_M6_PROXY_GUEST_SHA256=" + found);
            return 0;
        } catch (IOException | NoSuchAlgorithmException | Reject error) {
            System.err.println("ATTO_M6_PROXY_RESTRICTED_ATTO_REJECT=" + error.getMessage());
            return 2;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException e) { System.err.println("ATTO_M6_PROXY_TEMP_CLEANUP_FAILED"); }
            }
        }
    }
    public static void main(String[] args) {
        if (args.length != 5 || !args[1].equals("--source-sha256") ||
            !args[3].equals("--out")) {
            System.err.println("usage: <source.atto> --source-sha256 <64 hex> --out <new.java>");
            System.exit(2);
        }
        System.exit(compile(Path.of(args[0]), args[2], Path.of(args[4])));
    }
}
