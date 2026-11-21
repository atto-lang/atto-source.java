package dev.oreslang.launcher;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the real user-facing Java --check CLI, not only LinkedProgramRunner.validate().
 * Native Image AOT, zero-GC and independent CI step attestation are out of scope.
 * All source fixtures retain the exact cross-backend canonical Git blob ID.
 */
final class AttoEightNegativeCliGateTest {
    @TempDir Path scratch;

    private record Case(String id, String source, String blob, int guestExit) {}

    private static final Case[] NEGATIVE = {
        new Case("removed-modulo", "contracts/atto-scalar-v1/negative/removed-modulo.atto",
                "45d5794870e93074d9b11b90833a849e96aaa4c1", -1),
        new Case("removed-caret", "contracts/atto-scalar-v1/negative/removed-caret.atto",
                "6889c7e83c8d94b906fbfe78723caee9580da6aa", -1),
        new Case("removed-double-caret", "contracts/atto-scalar-v1/negative/removed-double-caret.atto",
                "7ee99948018e927cce74c9ad0edc1975e4251689", -1),
        new Case("type-first-reassignment", "contracts/atto-scalar-v1/negative/type-first-reassignment.atto",
                "b118f53e68565dcb9e6acd58deb8ee47dca67606", -1),
        new Case("typed-const-reassignment", "contracts/atto-scalar-v1/negative/typed-const-reassignment.atto",
                "784ebd47a50211e6f00cb732cc74319ed1e58fac", -1),
        new Case("legacy-async-modifier", "tests/formal-policy-atto/negative/legacy-async-modifier.atto",
                "26d392c8f609a730e81b503d9495de7e29b46755", -1),
        new Case("critical-suspends-modifier", "tests/formal-policy-atto/negative/critical-suspends-modifier.atto",
                "6be781ff89f9ebc48ee5870d3401943d50b0f9db", -1),
        new Case("suspends-critical-modifier", "tests/formal-policy-atto/negative/suspends-critical-modifier.atto",
                "c8bb7c921749f06e9d187a6dfbd71685952e3d88", -1)
    };
    private static final Case[] POSITIVE = {
        new Case("canonical-add", "contracts/atto-scalar-v1/positive/canonical-add.atto",
                "015f8b0f79aafa796a148d2adeff1574e8d6c491", 42),
        new Case("precedence", "contracts/atto-scalar-v1/positive/precedence.atto",
                "50414e5e95c8007f066696f55aa75309555b83e2", 14),
        new Case("parentheses", "contracts/atto-scalar-v1/positive/parentheses.atto",
                "9f508169c741486d5cec7510ac84fb761f342def", 20),
        new Case("pure-call", "contracts/atto-scalar-v1/positive/pure-call.atto",
                "e5ab32f7d8f684d655a83a3df989f16c52090c05", 42)
    };

    private record CliResult(int status, String stdout, String stderr) {}

    private static String gitBlob(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII));
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static byte[] pinnedBytes(Case c) throws Exception {
        Path file = Path.of("").toAbsolutePath().normalize();
        for (String segment : c.source().split("/")) {
            assertTrue(!segment.isEmpty() && !segment.equals(".") && !segment.equals(".."),
                    "unsafe fixture path: " + c.id());
            file = file.resolve(segment);
            assertFalse(Files.isSymbolicLink(file), "fixture path symlink: " + c.id());
        }
        assertTrue(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS),
                "missing canonical fixture: " + c.id());
        byte[] bytes = Files.readAllBytes(file);
        assertTrue(bytes.length > 0 && bytes.length <= 8192, "fixture byte budget: " + c.id());
        assertEquals(c.blob(), gitBlob(bytes), "canonical source drift: " + c.id());
        return bytes;
    }

    private Path staged(Case c) throws Exception {
        Path source = scratch.resolve(c.id() + ".atto");
        Files.write(source, pinnedBytes(c));
        assertEquals(c.blob(), gitBlob(Files.readAllBytes(source)),
                "staging mutated the reviewed source bytes");
        return source;
    }

    private static synchronized CliResult runCli(String... args) throws Exception {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try (PrintStream captureOut = new PrintStream(out, true, StandardCharsets.UTF_8);
             PrintStream captureErr = new PrintStream(err, true, StandardCharsets.UTF_8)) {
            System.setOut(captureOut);
            System.setErr(captureErr);
            try {
                int status = OresMain.exitStatusForArgs(args);
                captureOut.flush();
                captureErr.flush();
                return new CliResult(status,
                        out.toString(StandardCharsets.UTF_8),
                        err.toString(StandardCharsets.UTF_8));
            } finally {
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
    }

    private static Set<String> localFiles(Path folder) throws Exception {
        try (Stream<Path> entries = Files.list(folder)) {
            Set<String> paths = new TreeSet<>();
            entries.forEach(p -> paths.add(p.getFileName().toString()));
            return paths;
        }
    }

    private static boolean any(String s, String... terms) {
        for (String term : terms) if (s.contains(term)) return true;
        return false;
    }

    private static boolean wholeWord(String s, String word) {
        return java.util.regex.Pattern.compile("(?<![a-z0-9_])"
                + java.util.regex.Pattern.quote(word) + "(?![a-z0-9_])")
                .matcher(s).find();
    }

    private static boolean intendedDiagnostic(Case c, String rawStderr) {
        int pos = rawStderr.toLowerCase(Locale.ROOT).indexOf("error:");
        if (pos < 0 || rawStderr.length() > 65536) return false;
        String reason = rawStderr.substring(pos + 6).toLowerCase(Locale.ROOT).strip();
        // Do not match filenames, file paths, echoed fixture text or generic parser errors.
        if (reason.contains("\n") || reason.contains("\r")
                || any(reason, "unexpected token", "unsupported character",
                    "parse error", "syntax error", "unknown identifier", "internal error",
                    "stack overflow", "unrecognized token", "unexpected const")) return false;
        return switch (c.id()) {
            case "removed-modulo" -> (reason.contains("%") || reason.contains("modulo"))
                    && reason.contains("operator")
                    && any(reason, "removed", "forbidden", "unsupported", "not allowed");
            case "removed-caret" -> (reason.contains("^") || reason.contains("caret"))
                    && !reason.contains("^^") && !reason.contains("double caret")
                    && reason.contains("operator")
                    && any(reason, "removed", "forbidden", "unsupported", "not allowed");
            case "removed-double-caret" -> (reason.contains("^^") || reason.contains("double caret"))
                    && reason.contains("operator")
                    && any(reason, "removed", "forbidden", "unsupported", "not allowed");
            case "type-first-reassignment", "typed-const-reassignment" ->
                    wholeWord(reason, "x")
                    && any(reason, "immutable", "readonly", "read-only", "const binding",
                            "constant binding", "const variable")
                    && any(reason, "assign", "rebind", "mutation");
            case "legacy-async-modifier" -> wholeWord(reason, "async")
                    && any(reason, "forbidden", "unsupported", "disallowed",
                            "not allowed", "invalid modifier", "use suspends");
            case "critical-suspends-modifier", "suspends-critical-modifier" ->
                    wholeWord(reason, "critical") && wholeWord(reason, "suspends")
                    && any(reason, "invalid", "forbidden", "disallowed",
                            "not allowed", "incompatible", "mutually exclusive");
            default -> false;
        };
    }

    @Test
    void canonicalSourcesAreUniqueBytePinnedAndBounded() throws Exception {
        Set<String> ids = new TreeSet<>();
        for (Case c : NEGATIVE) {
            assertTrue(ids.add(c.id()), "duplicate negative ID: " + c.id());
            pinnedBytes(c);
        }
        for (Case c : POSITIVE) {
            assertTrue(ids.add(c.id()), "duplicate positive ID: " + c.id());
            pinnedBytes(c);
        }
        assertEquals(12, ids.size());
    }

    @Test
    void diagnosticOracleDeniesFilenameLeakageAndWrongStage() {
        Case modulo = NEGATIVE[0];
        assertFalse(intendedDiagnostic(modulo,
                "contracts/removed-modulo-operator.atto: error: unexpected token"));
        assertFalse(intendedDiagnostic(modulo, "error: unsupported character %"));
        assertTrue(intendedDiagnostic(modulo, "source.atto:1:1: error: modulo operator forbidden"));
        assertFalse(intendedDiagnostic(NEGATIVE[6], "error: critical function is invalid"));
        assertFalse(intendedDiagnostic(NEGATIVE[7], "error: suspends modifier invalid"));
        assertTrue(intendedDiagnostic(NEGATIVE[7],
                "source.atto:1:1: error: suspends critical modifier incompatible"));
    }

    @TestFactory
    Stream<DynamicTest> cliCheckRejectsAllEightSourcesWithoutPublishingFiles() {
        return Stream.of(NEGATIVE).map(c -> DynamicTest.dynamicTest("cli-check-reject-" + c.id(), () -> {
            Path source = staged(c);
            Set<String> filesBefore = localFiles(scratch);
            CliResult result = runCli("--check", source.toString());
            assertEquals(1, result.status(), "compiler should reject " + c.id());
            assertEquals("", result.stdout(), "--check must not emit IR, JS or guest output");
            assertTrue(intendedDiagnostic(c, result.stderr()),
                    "wrong semantic diagnostic: " + c.id() + ": " + result.stderr());
            assertEquals(filesBefore, localFiles(scratch),
                    "compiler published an output file beside rejected source: " + c.id());
        }));
    }

    @TestFactory
    Stream<DynamicTest> validSourcesPassCliCheckAndRunActualJvmGuest() {
        return Stream.of(POSITIVE).map(c -> DynamicTest.dynamicTest("cli-positive-" + c.id(), () -> {
            Path source = staged(c);
            Set<String> filesBefore = localFiles(scratch);
            CliResult checked = runCli("--check", source.toString());
            assertEquals(0, checked.status(), "valid .atto source rejected: " + c.id());
            assertEquals("", checked.stderr(), "unexpected check diagnostics: " + c.id());
            assertEquals("", checked.stdout(), "source check must not execute guest main");
            CliResult guest = runCli("--mode=aot", source.toString());
            assertEquals(c.guestExit(), guest.status(), "JVM guest result mismatch: " + c.id());
            assertEquals(filesBefore, localFiles(scratch), "unexpected sidecar output: " + c.id());
            // JVM --mode=aot exercises the Oreslang guest path but is not Graal
            // Native Image, no-JIT process proof, or zero-process-GC evidence.
        }));
    }
}
