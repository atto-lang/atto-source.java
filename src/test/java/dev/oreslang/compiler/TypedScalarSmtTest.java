package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;

/** Real typed Oreslang frontend admission; real installed Z3 required. */
public final class TypedScalarSmtTest {
    private static String core() throws Exception {
        return Files.readString(Path.of("stdlib/core.ores"), StandardCharsets.UTF_8);
    }

    private static String evaluateWithZ3(String smt) throws Exception {
        Process process = new ProcessBuilder("z3", "-smt2", "-in").start();
        try (var stdin = process.getOutputStream()) {
            stdin.write(smt.getBytes(StandardCharsets.UTF_8));
        }
        boolean finished = process.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("Z3 timeout: no proof accepted");
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), "Z3 exited unsuccessfully: " + stderr);
        assertEquals("", stderr, "unexpected solver diagnostic");
        return stdout.trim();
    }


    private static void writeReceipt(String label, String source, String smt, String verdict)
            throws Exception {
        Path dir = Path.of("target", "formal-typed-scalar");
        Files.createDirectories(dir);
        String sourceDigest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(source.getBytes(StandardCharsets.UTF_8)));
        String smtDigest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(smt.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(dir.resolve(label + ".smt2"), smt, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(label + ".z3.out"), verdict + "\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(label + ".receipt.txt"),
                "source-sha256=" + sourceDigest + "\n"
                + "smt-sha256=" + smtDigest + "\n"
                + "verdicts=" + verdict.replace("\n", ",") + "\n",
                StandardCharsets.UTF_8);
    }

    @Test
    void genuineCheckedJavaAstRefinesAllThreeCoreFunctions() throws Exception {
        String source = core();
        for (String name : new String[]{"min_int","max_int","clamp_int"}) {
            String smt = TypedScalarSmt.fromSource(source, "core", name);
            assertTrue(smt.contains("(define-fun implementation"));
            String observed = evaluateWithZ3(smt);
            writeReceipt("checked-" + name, source, smt, observed);
            assertEquals("unsat\nsat", observed,
                    "typed AST did not refine BV64 contract: " + name);
        }
    }

    @Test
    void incorrectSourceBranchesProduceRealSolverCounterexamples() throws Exception {
        String source = core();
        assertTrue(source.contains("if left < right; do"));
        assertTrue(source.contains("if lower > upper; do"));
        String badMin = source.replace("if left < right; do", "if left > right; do");
        String badClamp = source.replace("if lower > upper; do", "if lower < upper; do");
        String minSmt = TypedScalarSmt.fromSource(badMin, "core", "min_int");
        String minVerdict = evaluateWithZ3(minSmt);
        writeReceipt("mutated-min_int", badMin, minSmt, minVerdict);
        assertEquals("sat\nsat", minVerdict);
        String clampSmt = TypedScalarSmt.fromSource(badClamp, "core", "clamp_int");
        String clampVerdict = evaluateWithZ3(clampSmt);
        writeReceipt("mutated-clamp_int", badClamp, clampSmt, clampVerdict);
        assertEquals("sat\nsat", clampVerdict);
    }

    @Test
    void unsupportedStatementsAndUnverifiedSignaturesFailClosed() throws Exception {
        String source = core();
        assertThrows(IllegalArgumentException.class, () ->
                TypedScalarSmt.fromSource(source, "core", "does_not_exist"));
        assertThrows(IllegalArgumentException.class, () ->
                TypedScalarSmt.fromSource(source, "missing-module", "min_int"));
        // Literal return is well-typed Oreslang, but cannot be silently
        // reinterpreted as a parameter-only return in this proof subset.
        assertTrue(source.contains("return upper;"));
        String literal = source.replace("return upper;", "return 1;");
        assertThrows(IllegalArgumentException.class, () ->
                TypedScalarSmt.fromSource(literal, "core", "clamp_int"));
    }

    @Test
    void invalidProofInputsFailClosed() throws Exception {
        String source = core();
        assertThrows(IllegalArgumentException.class, () ->
                TypedScalarSmt.fromSource(null, "core", "min_int"));
        assertThrows(IllegalArgumentException.class, () ->
                TypedScalarSmt.fromSource(source, "core", null));
        assertThrows(IllegalArgumentException.class, () ->
                TypedScalarSmt.fromSource(" ".repeat(2_000_001), "core", "min_int"));
    }

    @Test
    void pureScalarProofRejectsAddedLocalBindingAndArithmetic() throws Exception {
        String source = core();
        String withBinding = source.replace("return left;",
                "val int unchecked = 0; return left;");
        String withArithmetic = source.replace("return right;",
                "return right + 1;");
        assertNotEquals(source, withBinding);
        assertNotEquals(source, withArithmetic);
        assertThrows(RuntimeException.class, () ->
                TypedScalarSmt.fromSource(withBinding, "core", "min_int"));
        assertThrows(RuntimeException.class, () ->
                TypedScalarSmt.fromSource(withArithmetic, "core", "min_int"));
    }
}
