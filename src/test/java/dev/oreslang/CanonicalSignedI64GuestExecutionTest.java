package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.graalvm.polyglot.PolyglotException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiled guest execution tests of the EXACT canonical signed-i64 contract.
 * A source-file SHA or a Java arithmetic model alone does not satisfy these
 * tests: LinkedProgramRunner must validate AND actually execute guest .atto.
 * This does not claim Native Image or cross-backend execution.
 */
final class CanonicalSignedI64GuestExecutionTest {
    @TempDir Path temp;

    private static final Path CONTRACT =
            Path.of("contracts/v1/strict-named-integer-arithmetic-v1.json");
    private static final String CONTRACT_BLOB =
            "3f65f557f06e8cd0d34a738a0d357d6c2e477665";
    private static final Pattern ROW = Pattern.compile(
            "\\{\\s*\"id\"\\s*:\\s*\"([a-z0-9_]+)\"\\s*,\\s*"
            + "\"operation\"\\s*:\\s*\"(rem|mod|pow|bit_xor)\"\\s*,\\s*"
            + "\"left\"\\s*:\\s*\"(-?[0-9]+)\"\\s*,\\s*"
            + "\"right\"\\s*:\\s*\"(-?[0-9]+)\"\\s*,\\s*"
            + "\"expect\"\\s*:\\s*\\{\\s*\"(value|error)\"\\s*:\\s*"
            + "\"(-?[A-Z0-9_]+|-?[0-9]+)\"\\s*\\}\\s*\\}");

    private record Vector(String id, String operation, long left, long right,
                          String expected, boolean error) { }

    private static String gitBlob(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
        md.update(data);
        return HexFormat.of().formatHex(md.digest());
    }

    /** Strictly consume every case: an unrecognized or duplicate row fails. */
    private static List<Vector> vectors() throws Exception {
        assertFalse(Files.isSymbolicLink(CONTRACT), "symlinked contract");
        assertTrue(Files.isRegularFile(CONTRACT, LinkOption.NOFOLLOW_LINKS), "missing contract");
        byte[] bytes = Files.readAllBytes(CONTRACT);
        assertTrue(bytes.length > 0 && bytes.length <= 8192, "contract size");
        assertEquals(CONTRACT_BLOB, gitBlob(bytes), "unreviewed canonical contract");
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"contract\": \"oreslang.strict-named-integer-arithmetic.v1\""));
        assertTrue(json.contains("\"integer\": \"signed64\""));
        int arrayName = json.indexOf("\"cases\": [");
        assertTrue(arrayName >= 0, "missing canonical array");
        int start = json.indexOf('[', arrayName);
        int end = json.lastIndexOf(']');
        assertTrue(start >= 0 && end > start, "truncated canonical array");
        String rows = json.substring(start + 1, end);
        Matcher matches = ROW.matcher(rows);
        List<Vector> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int cursor = 0;
        int errors = 0;
        while (matches.find()) {
            String separator = rows.substring(cursor, matches.start());
            assertTrue(separator.matches(result.isEmpty() ? "\\s*" : "\\s*,\\s*"),
                    "unrecognized contract case separator");
            String id = matches.group(1);
            assertTrue(seen.add(id), "duplicate case " + id);
            boolean error = matches.group(5).equals("error");
            String expect = matches.group(6);
            if (error) {
                assertTrue(Set.of("DIVISOR_ZERO", "OVERFLOW", "NEGATIVE_EXPONENT").contains(expect),
                        "unknown canonical arithmetic error " + id);
                errors++;
            } else {
                Long.parseLong(expect); // fail on non-i64 expectation
            }
            result.add(new Vector(id, matches.group(2),
                    Long.parseLong(matches.group(3)),
                    Long.parseLong(matches.group(4)), expect, error));
            cursor = matches.end();
        }
        assertTrue(rows.substring(cursor).matches("\\s*"), "unparsed contract cases");
        assertEquals(26, result.size(), "every canonical vector required");
        assertEquals(4, errors, "all four canonical error cases required");
        return List.copyOf(result);
    }

    private static String guestOperation(String op) {
        return switch (op) {
            case "rem" -> "guest_rem";
            case "mod" -> "guest_mod";
            case "pow" -> "guest_pow";
            case "bit_xor" -> "guest_bit_xor";
            default -> throw new AssertionError("unknown operation " + op);
        };
    }

    private static String guestDefinitions() {
        return """
                fnc guest_rem(int a, int b): int { return Integer.rem(a, b); }
                fnc guest_mod(int a, int b): int { return Integer.mod(a, b); }
                fnc guest_pow(int a, int b): int { return Integer.pow(a, b); }
                fnc guest_bit_xor(int a, int b): int { return Integer.bitXor(a, b); }
                """;
    }

    private Path guestFile(String name, String text) throws Exception {
        Path entry = temp.resolve(name + ".atto");
        Files.writeString(entry, text, StandardCharsets.UTF_8);
        return entry;
    }

    private static String programFor(List<Vector> cases) {
        StringBuilder src = new StringBuilder(guestDefinitions());
        src.append("pub routine main(): void {\n");
        for (Vector v : cases) {
            src.append("  stdio.println(").append(guestOperation(v.operation()))
                    .append('(').append(v.left()).append(", ").append(v.right())
                    .append("));\n");
        }
        src.append("  return;\n}\n");
        return src.toString();
    }

    @Test
    void allCanonicalGuestValuesCompileAndExecuteAsActualAttoSource() throws Exception {
        List<Vector> values = vectors().stream().filter(v -> !v.error()).toList();
        assertEquals(22, values.size());
        Path entry = guestFile("canonical-i64-values", programFor(values));
        assertNotNull(LinkedProgramRunner.validate(entry, Map.of()),
                "real compiler must admit the full canonical guest program");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out, err);
        StringBuilder expected = new StringBuilder();
        for (Vector v : values) expected.append(v.expected()).append('\n');
        assertEquals(expected.toString(), out.toString(StandardCharsets.UTF_8),
                "wrong real guest result or missing/extra output line");
        assertEquals("", err.toString(StandardCharsets.UTF_8), "guest stderr");
    }

    @TestFactory
    Stream<DynamicTest> allCanonicalGuestErrorsCompileButTrapAtExecution() throws Exception {
        return vectors().stream().filter(Vector::error).map(v -> DynamicTest.dynamicTest(
                "guest-trap-" + v.id(), () -> {
                    Path entry = guestFile("canonical-error-" + v.id(), programFor(List.of(v)));
                    assertNotNull(LinkedProgramRunner.validate(entry, Map.of()),
                            "error case must pass compile-time checking: " + v.id());
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    PolyglotException failure = assertThrows(PolyglotException.class,
                            () -> LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                                    ExecutionProfile.serverJit(), Set.of(), Map.of(), out,
                                    new ByteArrayOutputStream()), v.id());
                    // A generic guest failure (e.g. missing function) is not
                    // evidence of the required arithmetic exception.
                    String reason = switch (v.expected()) {
                        case "DIVISOR_ZERO" -> "integer remainder by zero";
                        case "OVERFLOW" -> "overflow";
                        case "NEGATIVE_EXPONENT" -> "nonnegative";
                        default -> throw new AssertionError("unknown arithmetic error case");
                    };
                    assertNotNull(failure.getMessage(), "guest exception missing diagnostic");
                    assertTrue(failure.getMessage().contains(reason),
                            "wrong guest error for " + v.id() + ": " + failure.getMessage());
                    assertEquals("", out.toString(StandardCharsets.UTF_8),
                            "failed arithmetic cannot produce successful output: " + v.id());
                }));
    }

    private record Rejection(String name, String expression, String diagnostic) { }

    @TestFactory
    Stream<DynamicTest> invalidNamedCallShapesRejectBeforeGuestExecution() {
        List<Rejection> rejected = List.of(
                new Rejection("missing-args", "Integer.mod()", "Integer.mod"),
                new Rejection("one-arg", "Integer.rem(1)", "Integer.rem"),
                new Rejection("three-args", "Integer.mod(1, 2, 3)", "Integer.mod"),
                new Rejection("float-left", "Integer.pow(2.5, 2)", "Integer.pow"),
                new Rejection("bool-right", "Integer.bitXor(1, true)", "Integer.bitXor"),
                new Rejection("string-left", "Integer.rem(\"1\", 2)", "Integer.rem"),
                new Rejection("type-arguments", "Integer.pow<int>(2, 3)", "type arguments"),
                new Rejection("inferred-type-args", "Integer.mod<>(9, 4)", "type arguments")
        );
        return rejected.stream().map(v -> DynamicTest.dynamicTest("compile-deny-" + v.name(), () -> {
            String src = "pub routine main(): void { stdio.println(" + v.expression()
                    + "); return; }";
            assertNotNull(Parser.parse(src), "must reach semantic/type checking, not fail parsing");
            Path entry = guestFile("denied-" + v.name(), src);
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(entry, Map.of()), v.name());
            assertNotNull(failure.getMessage());
            assertTrue(failure.getMessage().contains(v.diagnostic()),
                    "wrong diagnostic for " + v.name() + ": " + failure.getMessage());
        }));
    }

    @Test
    void shadowedIntegerNameCannotSilentlyResolveSealedHostIntrinsic() throws Exception {
        String src = """
                pub routine main(): void {
                    const Integer = 7;
                    stdio.println(Integer.mod(9, 4));
                    return;
                }
                """;
        assertNotNull(Parser.parse(src));
        Path entry = guestFile("shadowed-integer", src);
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry, Map.of()),
                "lexically bound Integer cannot be captured as a sealed host intrinsic");
    }

    @Test
    void importedIntegerNamespaceCannotCaptureSealedIntrinsic() throws Exception {
        String src = """
                import * as Integer from "ores.core/numeric";
                pub routine main(): void {
                    stdio.println(Integer.mod(9, 4));
                    return;
                }
                """;
        assertNotNull(Parser.parse(src));
        Path entry = guestFile("imported-integer-namespace", src);
        assertThrows(IllegalArgumentException.class,
                () -> LinkedProgramRunner.validate(entry, Map.of()),
                "a real imported namespace named Integer cannot invoke the sealed builtin");
    }

    @TestFactory
    Stream<DynamicTest> reservedOperatorsRejectAtParserBoundary() {
        List<Rejection> rejected = List.of(
                new Rejection("modulus", "a % b", "Integer.rem"),
                new Rejection("bit-xor", "a ^ b", "Integer.bitXor"),
                new Rejection("logical-xor", "a ^^ b", "Xor(a, b)")
        );
        return rejected.stream().map(v -> DynamicTest.dynamicTest(
                "parser-deny-" + v.name(), () -> {
                    String src = "fnc f(int a, int b): int { return "
                            + v.expression() + "; }";
                    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                            () -> Parser.parse(src), v.name());
                    assertTrue(failure.getMessage().contains(v.diagnostic()),
                            "reserved operator must report named replacement");
                }));
    }

    /** Standalone fixture integrity check, NOT compiler or guest execution. */
    public static void main(String[] args) throws Exception {
        List<Vector> v = vectors();
        long errors = v.stream().filter(Vector::error).count();
        System.out.println("CANONICAL_GUEST_FIXTURES|PASS|vectors=" + v.size()
                + "|values=" + (v.size() - errors) + "|errors=" + errors);
    }
}
