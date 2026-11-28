package dev.oreslang.launcher;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact-source Oreslang client scalar-v1 execution through the real reference
 * parser/typechecker and Truffle guest runtime, never a host-side oracle.
 * A JVM --mode=aot guest run does not establish Graal Native Image support,
 * transport, JSON, modules or five-backend client parity.
 */
final class ClientScalarV1Test {
    private static final Path ROOT = Path.of("contracts", "clients", "scalar-v1");
    private static final Map<String, String> DIGESTS = Map.of(
            "01-precedence", "e42464360ffa360b263bc74f74ef485e6a9e4eb9dff601ead2c4576982622576",
            "02-grouping", "9196a61de9c4710bdf32e658de396c521ad2abb2e3d03ce711ae449ac7aa5a2f",
            "03-signed", "613d1e1e104361ce555f397dd0d23754d44ade5b80750147110c1e4a73cce286",
            "04-i64-edge", "b0f80c76a24cb15ed25fee0bdaa096d7cbcd318f787f7d8a040166c127532247",
            "05-separators", "2bcfdc119d3e48b9ac97e2061b7b88571d6e621b04ecabf886347d9eeb19382c",
            "06-nested", "dd1bf333cdd17140571227e810427030a279666b8a4d06eb5968a056cf5a8a2a",
            "07-unknown-reject", "c45493f0814253ab5a2e8896d8b39643c7b8e1247d408eef11baccd79d6580e1",
            "08-overflow-reject", "37b793a36e231ee37d953b8359cd0f004dc008a795eb32a413762a0a504c542c",
            "09-syntax-reject", "32f4b30c52ce58955ecbb0aa0d564f0ac394737940faaab01351e25ed9df4b75"
    );

    private static Path source(String caseName) throws Exception {
        Path file = ROOT.resolve(caseName + ".ores");
        assertTrue(Files.isRegularFile(file), "missing source " + caseName);
        assertFalse(Files.isSymbolicLink(file), "symlinked source " + caseName);
        byte[] bytes = Files.readAllBytes(file);
        assertTrue(bytes.length > 20 && bytes.length < 2048, "unexpected source size");
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(DIGESTS.get(caseName), actual, "source bytes changed: " + caseName);
        return file.toAbsolutePath().normalize();
    }

    private static void run(String caseName, int expected) throws Exception {
        String name = source(caseName).toString();
        assertEquals(0, OresMain.exitStatusForArgs(new String[] {"--check", name}),
                "reference compiler rejected positive fixture " + caseName);
        assertEquals(expected, OresMain.exitStatusForArgs(new String[] {"--mode=aot", name}),
                "reference guest returned a different scalar value " + caseName);
    }

    private static void reject(String caseName, String diagnosticWords) throws Exception {
        String name = source(caseName).toString();
        PrintStream original = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status;
        try (PrintStream capture = new PrintStream(errors, true, StandardCharsets.UTF_8)) {
            System.setErr(capture);
            try {
                status = OresMain.exitStatusForArgs(new String[] {"--check", name});
            } finally {
                System.setErr(original);
            }
        }
        assertEquals(1, status, "invalid source must fail compiler checking: " + caseName);
        String diagnostic = errors.toString(StandardCharsets.UTF_8).toLowerCase();
        assertTrue(diagnostic.matches("(?s).*(" + diagnosticWords + ").*"),
                "wrong rejection category for " + caseName + ": " + diagnostic);
        assertFalse(diagnostic.contains("exception in thread") || diagnostic.contains("stack trace"),
                "compiler crash is not a semantic rejection: " + caseName);
    }

    @Test void exactSourceIdentity() throws Exception {
        for (String name : DIGESTS.keySet()) source(name);
        assertEquals(9, DIGESTS.size());
    }
    @Test void precedence() throws Exception { run("01-precedence", 14); }
    @Test void grouping() throws Exception { run("02-grouping", 20); }
    @Test void signedArithmetic() throws Exception { run("03-signed", 5); }
    @Test void i64Boundary() throws Exception { run("04-i64-edge", 1); }
    @Test void integerSeparators() throws Exception { run("05-separators", 100); }
    @Test void nestedGrouping() throws Exception { run("06-nested", 35); }
    @Test void undefinedNameRejected() throws Exception {
        reject("07-unknown-reject", "unknown|undefined|undeclared|unresolved|identifier|symbol|not defined");
    }
    @Test void positiveOverflowLiteralRejected() throws Exception {
        reject("08-overflow-reject", "range|overflow|literal|integer");
    }
    @Test void invalidGrammarRejected() throws Exception {
        reject("09-syntax-reject", "syntax|parse|expected|unexpected|token");
    }
}
