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
 * Exact-byte Opto Sync AttoLang scalar-fixture execution through the real reference
 * parser/typechecker and Truffle guest runtime, never a host-side oracle.
 * A JVM --mode=aot guest run does not establish Graal Native Image support,
 * transport, JSON, modules or five-backend client parity.
 */
final class OptoSyncScalarGuestTest {
    private static final Path ROOT = Path.of("contracts", "opto-sync", "scalar-v1");
    private static final Map<String, String> DIGESTS = Map.of(
            "scalar-order", "0dedccfdeff013995e1e5d784bf1767acf61b0c0485fa50aba6aa9dd473331f2",
            "scalar-parens", "c1be02a97cbf9c02a3ff6ed2c3452cbde2e7adca77b03c93ea2a185b87fd5a8f",
            "scalar-boundary", "48e5c6d9a4284d5d9c05bab779fa74a53341a981d5e6c63ce6cc5c52cbf323dd",
            "reject-overflow", "dbc6368f793beef3465a061983134ef85621d9084bc179f8d53c096fa0242461",
            "reject-invalid", "dce35f88681548d3e3e4e1d04394924a9b70f27925acc37a53689d74237ec33a"
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
        assertEquals(5, DIGESTS.size());
    }
    @Test void precedence() throws Exception { run("scalar-order", 14); }
    @Test void grouping() throws Exception { run("scalar-parens", 42); }
    @Test void signedI64Boundary() throws Exception { run("scalar-boundary", 1); }
    @Test void undefinedNameRejected() throws Exception {
        reject("reject-invalid", "unknown|undefined|undeclared|unresolved|identifier|symbol|not defined");
    }
    @Test void outOfRangeI64LiteralRejected() throws Exception {
        reject("reject-overflow", "range|overflow|literal|integer|arithmetic");
    }
}
