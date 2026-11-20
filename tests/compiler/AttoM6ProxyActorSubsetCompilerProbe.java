// Strict positive/negative subset grammar and source-pinning admission probe.
// This proves a small executable actor body, not the canonical Atto frontend.
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

public final class AttoM6ProxyActorSubsetCompilerProbe {
    private AttoM6ProxyActorSubsetCompilerProbe() {}
    private static void require(boolean value, String why) {
        if (!value) throw new AssertionError(why);
    }
    private static void reject(String source) {
        boolean rejected = false;
        try { AttoM6ProxyActorSubsetCompiler.compile_source(source); }
        catch (AttoM6ProxyActorSubsetCompiler.Reject expected) { rejected = true; }
        require(rejected, "unrecognized Atto syntax was accepted: " + source);
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("one pinned .atto fixture required");
        Path fixture = Path.of(args[0]);
        String source = Files.readString(fixture, StandardCharsets.UTF_8);
        String generated = AttoM6ProxyActorSubsetCompiler.compile_source(source);
        require(generated.contains("Math.addExact(total, increment)")
            && generated.contains("implements java.util.function.LongUnaryOperator"),
            "actor receiver source not statically lowered");
        reject(source + "\nimport java.lang.Runtime;\n");
        reject(source.replace("int increment", "string increment"));
        reject(source.replace("total + increment", "total * increment"));
        reject(source.replace("return total;", "return increment;"));
        reject(source.replace("define actor Counter", "define proxy actor Counter"));
        reject(source.replace("let total: int", "let total: Pointer<int>"));
        reject(source.replace("0;", "0; unsafe_java();"));
        reject("define actor Counter as { pub fnc on_message(int x): int { return 42; } }");
        byte[] original = Files.readAllBytes(fixture);
        String sha = HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(original));
        Path dir = Files.createTempDirectory("atto-m6-proxy-compiler-probe");
        try {
            Path valid = dir.resolve("AttoM6ProxyGuestCounter.java");
            require(AttoM6ProxyActorSubsetCompiler.compile(fixture, "0".repeat(64), valid) == 2
                && !Files.exists(valid), "source SHA mismatch allowed");
            require(AttoM6ProxyActorSubsetCompiler.compile(fixture, sha, valid) == 0,
                "valid pinned .atto actor did not compile");
            require(Files.readString(valid).equals(generated),
                "compiled actor source changed between validation and publication");
            require(AttoM6ProxyActorSubsetCompiler.compile(fixture, sha, valid) == 2,
                "output overwrite allowed");
            Path link = dir.resolve("linked.atto");
            Files.createSymbolicLink(link, fixture.toAbsolutePath());
            require(AttoM6ProxyActorSubsetCompiler.compile(link, sha,
                dir.resolve("should-not-exist.java")) == 2,
                "symlinked guest source admitted");
            Path invalid = dir.resolve("malformed.atto");
            Files.write(invalid, new byte[] {(byte)0xff, (byte)0xfe});
            String badSha = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(invalid)));
            require(AttoM6ProxyActorSubsetCompiler.compile(invalid, badSha,
                dir.resolve("invalid.java")) == 2, "malformed UTF-8 source admitted");
            Files.delete(link);
            Files.delete(invalid);
            Files.delete(valid);
        } finally {
            Files.delete(dir);
        }
        System.out.println("ATTO_M6_PROXY_COMPILER_NEGATIVE_GATES=PASS");
        System.out.println("ATTO_M6_PROXY_COMPILED_GUEST_SCOPE=RESTRICTED_SINGLE_ACTOR_I64");
    }
}
