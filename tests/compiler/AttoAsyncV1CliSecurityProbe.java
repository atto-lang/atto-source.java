package atto.compiler.asyncv1;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;

/** End-to-end, real-filesystem CLI checks; no artifact on rejection. */
public final class AttoAsyncV1CliSecurityProbe {
    private AttoAsyncV1CliSecurityProbe() {}
    private static int checks;
    private static void check(boolean ok) {
        if (!ok) throw new AssertionError("async CLI security admission");
        checks++;
    }
    private static void rejects(Path input, Path output, String code) throws IOException {
        try {
            AttoAsyncV1Compiler.main(new String[] {input.toString(), output.toString()});
            throw new AssertionError("unexpected CLI acceptance");
        } catch (AttoAsyncV1Compiler.Reject expected) {
            check(code.equals(expected.getMessage())
                && !Files.exists(output, LinkOption.NOFOLLOW_LINKS));
        }
    }
    public static void main(String[] args) throws IOException {
        if (args.length != 0) throw new AssertionError("unexpected args");
        Path dir = Files.createTempDirectory("atto-async-cli-security-");
        try {
            Path input = dir.resolve("valid.atto");
            Files.writeString(input,
                "pub fnc main(): int { do sync task<void> { return; }; return 42; }");
            Path output = dir.resolve("valid.java");
            AttoAsyncV1Compiler.main(new String[] {input.toString(), output.toString()});
            check(Files.readString(output).contains("ATTO_ASYNC_SCOPE=PURE_INLINE_TASK_ONLY"));
            try {
                AttoAsyncV1Compiler.main(new String[] {input.toString(), output.toString()});
                throw new AssertionError("existing output overwritten");
            } catch (AttoAsyncV1Compiler.Reject expected) {
                check("E_ASYNC_SOURCE_OR_OUTPUT_ADMISSION".equals(expected.getMessage()));
            }
            Path bad = dir.resolve("negative.atto");
            Files.writeString(bad,
                "pub fnc main(): int { do sync task<int> {}; return 42; }");
            rejects(bad, dir.resolve("nonvoid.java"), "E_TASK_RETURN_TYPE");
            Files.writeString(bad,
                "pub fnc main(): int { do sync task { return 5; }; return 42; }");
            rejects(bad, dir.resolve("inferred.java"), "E_TASK_RETURN_TYPE");
            Files.writeString(bad,
                "pub fnc main(): int { sync task {}; return 42; }");
            rejects(bad, dir.resolve("bare.java"), "E_SYNTAX");
            Path sourceLink = dir.resolve("source-link.atto");
            Files.createSymbolicLink(sourceLink, input.getFileName());
            rejects(sourceLink, dir.resolve("symlink.java"), "E_ASYNC_SOURCE_OR_OUTPUT_ADMISSION");
            Path dangling = dir.resolve("dangling.java");
            Files.createSymbolicLink(dangling, Path.of("nonexistent-target"));
            try {
                AttoAsyncV1Compiler.main(new String[] {input.toString(), dangling.toString()});
                throw new AssertionError("dangling symlink output was overwritten");
            } catch (AttoAsyncV1Compiler.Reject expected) {
                check("E_ASYNC_SOURCE_OR_OUTPUT_ADMISSION".equals(expected.getMessage())
                    && Files.isSymbolicLink(dangling)
                    && !Files.exists(dir.resolve("nonexistent-target")));
            }
            Path malformed = dir.resolve("malformed.atto");
            Files.write(malformed, new byte[] {(byte)0xC3, (byte)0x28});
            try {
                AttoAsyncV1Compiler.main(new String[] {malformed.toString(),
                    dir.resolve("malformed.java").toString()});
                throw new AssertionError("malformed UTF-8 accepted");
            } catch (java.nio.charset.CharacterCodingException expected) {
                check(!Files.exists(dir.resolve("malformed.java")));
            }
            Path large = dir.resolve("oversized.atto");
            Files.write(large, new byte[8193]);
            rejects(large, dir.resolve("large.java"), "E_ASYNC_SOURCE_OR_OUTPUT_ADMISSION");
            check(Files.readString(output, StandardCharsets.UTF_8).contains("return 42;"));
            System.out.println("ATTO_ASYNC_V1_CLI_SECURITY_PASS checks=" + checks);
        } finally {
            try (var stream = Files.walk(dir)) {
                stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                });
            }
        }
    }
}
