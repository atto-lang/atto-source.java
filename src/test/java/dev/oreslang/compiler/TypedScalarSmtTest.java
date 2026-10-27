package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
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

    /**
     * Fail-closed splitter for the two exact queries emitted by TypedScalarSmt.
     * This is structural extraction from a real checked-AST-derived SMT file,
     * NOT an independently proved translation from AST to SMT.
     */
    private static List<String> separateProofAndControl(String document) {
        if (document == null || document.length() > 65_536
                || !document.startsWith("(set-logic QF_BV)\n")
                || document.contains("(echo ") || document.contains("(exit)")) {
            throw new IllegalArgumentException("unrecognized scalar SMT document");
        }
        String marker = "(push 1)\n";
        int first = document.indexOf(marker);
        int second = first < 0 ? -1 : document.indexOf(marker, first + marker.length());
        if (first < 0 || second < 0
                || document.indexOf(marker, second + marker.length()) >= 0) {
            throw new IllegalArgumentException("expected exactly two scalar obligations");
        }
        String prelude = document.substring(0, first);
        if (!prelude.contains("(define-fun implementation ")
                || !prelude.contains("(define-fun contract ")
                || prelude.contains("(check-sat)")
                || prelude.contains("(assert ")) {
            throw new IllegalArgumentException("unexpected query prelude");
        }
        String positive = document.substring(first + marker.length(), second);
        String control = document.substring(second + marker.length());
        for (String body : List.of(positive, control)) {
            String termination = "\n(check-sat)\n(pop 1)\n";
            if (!body.startsWith("(assert ") || !body.endsWith(termination)
                    || body.indexOf(termination) != body.lastIndexOf(termination)
                    || body.indexOf("(check-sat)") != body.lastIndexOf("(check-sat)")
                    || body.contains("(push 1)") || body.contains("(echo ")) {
                throw new IllegalArgumentException("noncanonical scalar obligation");
            }
        }
        return List.of(prelude + marker + positive, prelude + marker + control);
    }

    private static void replaySeparated(String label, String source, String combined,
                                        String positiveVerdict) throws Exception {
        List<String> slices = separateProofAndControl(combined);
        String observedPositive = evaluateWithZ3(slices.get(0));
        String observedControl = evaluateWithZ3(slices.get(1));
        writeReceipt(label + "-implementation", source, slices.get(0), observedPositive);
        writeReceipt(label + "-negative-control", source, slices.get(1), observedControl);
        assertEquals(positiveVerdict, observedPositive,
                "actual checked implementation SMT disagrees: " + label);
        assertEquals("sat", observedControl,
                "required independent incorrect-implementation control is absent: " + label);
    }

    @Test
    void genuineCheckedJavaAstRefinesAllThreeCoreFunctions() throws Exception {
        String source = core();
        for (String name : new String[]{"min_int","max_int","clamp_int"}) {
            String smt = TypedScalarSmt.fromSource(source, "core", name);
            assertTrue(smt.contains("(define-fun implementation"));
            String observed = evaluateWithZ3(smt);
            writeReceipt("checked-" + name, source, smt, observed);
            replaySeparated("checked-" + name, source, smt, "unsat");
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
        replaySeparated("mutated-min_int", badMin, minSmt, "sat");
        assertEquals("sat\nsat", minVerdict);
        String clampSmt = TypedScalarSmt.fromSource(badClamp, "core", "clamp_int");
        String clampVerdict = evaluateWithZ3(clampSmt);
        writeReceipt("mutated-clamp_int", badClamp, clampSmt, clampVerdict);
        replaySeparated("mutated-clamp_int", badClamp, clampSmt, "sat");
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
    void separatedQueryParserRejectsPartialAndInjectedDocuments() throws Exception {
        String smt = TypedScalarSmt.fromSource(core(), "core", "min_int");
        assertEquals(2, separateProofAndControl(smt).size());
        assertThrows(IllegalArgumentException.class, () ->
                separateProofAndControl(smt.replaceFirst("\\(check-sat\\)", "(echo \"unsat\")")));
        assertThrows(IllegalArgumentException.class, () ->
                separateProofAndControl(smt + "(check-sat)\n"));
        assertThrows(IllegalArgumentException.class, () ->
                separateProofAndControl(smt.replace("(push 1)\n", "")));
        assertThrows(IllegalArgumentException.class, () ->
                separateProofAndControl(smt + "(push 1)\n(check-sat)\n(pop 1)\n"));
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
