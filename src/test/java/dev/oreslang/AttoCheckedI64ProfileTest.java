package dev.oreslang;

import dev.oreslang.runtime.AttoCheckedI64Profile;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** Exact-source guest tests shared with atto-source.js; not a Native Image receipt. */
final class AttoCheckedI64ProfileTest {
    private static final String HEADER = "// oreslang:checked-i64-v1\n";
    private static final String EXPECTED_CORPUS_SHA256 =
            "e56dfccea11757ff18fc72a81ba5d643380e69184ab309eedf45e1e7b940e133";
    private static final String FUNCTIONS = """
            fnc sum(int a,int b): int {return a+b;}
            fnc subtract(int a,int b): int {return a-b;}
            fnc multiply(int a,int b): int {return a*b;}
            fnc opposite(int a): int {return -a;}
            fnc remainder(int a,int b): int {return Integer.rem(a,b);}
            """;

    private record Case(String id, String kind, String expression, String expected) { }

    private static List<Case> cases() throws Exception {
        byte[] bytes = Files.readAllBytes(Path.of(
                "contracts/checked-i64-java-js-v1/cases.tsv"));
        assertTrue(bytes.length > 0 && bytes.length <= 16384, "bounded corpus");
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        assertEquals(EXPECTED_CORPUS_SHA256, sha, "exact shared Java/JS corpus SHA-256");
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertFalse(text.contains("\r"));
        assertTrue(text.endsWith("\n"));
        String[] lines = text.split("\n", -1);
        assertEquals(16, lines.length, "15 entries plus LF");
        assertEquals("", lines[15]);
        List<Case> cases = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int good = 0, overflows = 0, zero = 0;
        for (int i = 0; i < 15; i++) {
            String[] parts = lines[i].split("\\|", -1);
            assertEquals(4, parts.length, "invalid case columns " + i);
            assertTrue(parts[0].matches("[a-z]+(?:-[a-z]+)*"), "invalid ID");
            assertTrue(seen.add(parts[0]), "duplicate case");
            assertTrue(parts[2].matches("[A-Za-z0-9(),+\\- ]{1,160}"), "unsafe fixture expression");
            switch (parts[1]) {
                case "value" -> {
                    assertTrue(parts[3].matches("(?:0|-?[1-9][0-9]*)"));
                    good++;
                }
                case "overflow" -> {
                    assertEquals("ORES_I64_OVERFLOW", parts[3]);
                    overflows++;
                }
                case "zero" -> {
                    assertEquals("DIVISION_BY_ZERO", parts[3]);
                    zero++;
                }
                default -> fail("unknown case outcome");
            }
            cases.add(new Case(parts[0], parts[1], parts[2], parts[3]));
        }
        assertEquals(7, good);
        assertEquals(7, overflows);
        assertEquals(1, zero);
        return cases;
    }

    private static String guest(String expression, boolean checked) {
        return (checked ? HEADER : "") + FUNCTIONS
                + "pub fnc main(): int {return " + expression + ";}\n";
    }

    @TestFactory
    Stream<DynamicTest> realCheckedGraalGuestsMatchPinnedJavaJsCases() throws Exception {
        return cases().stream().map(c -> DynamicTest.dynamicTest(c.id(), () -> {
            try (Context context = Context.newBuilder("ores").allowAllAccess(false).build()) {
                String source = guest(c.expression(), true);
                if (c.kind().equals("value")) {
                    Value value = context.eval("ores", source);
                    assertTrue(value.fitsInLong(), "expected typed i64 guest result");
                    assertEquals(Long.parseLong(c.expected()), value.asLong(), c.id());
                } else {
                    PolyglotException failure = assertThrows(PolyglotException.class,
                            () -> context.eval("ores", source), c.id());
                    if (c.kind().equals("overflow")) {
                        assertTrue(failure.getMessage().contains("ORES_I64_OVERFLOW"),
                                () -> c.id() + ": missing overflow classification: "
                                        + failure.getMessage());
                    } else {
                        assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT)
                                        .contains("zero"),
                                () -> "zero denominator incorrectly classified: "
                                        + failure.getMessage());
                    }
                }
            }
        }));
    }

    @Test
    void unmarkedLegacyGuestRetainsObservedWrappingBehavior() {
        try (Context context = Context.newBuilder("ores").allowAllAccess(false).build()) {
            Value result = context.eval("ores",
                    guest("sum(9223372036854775807,1)", false));
            assertEquals(Long.MIN_VALUE, result.asLong(),
                    "legacy wrapping remains separate from explicit checked profile");
        }
    }

    @Test
    void malformedDisplacedAndDuplicateSelectorsFailClosed() {
        String body = FUNCTIONS + "pub fnc main(): int {return 42;}\n";
        for (String invalid : List.of(
                " " + HEADER + body,
                "\n" + HEADER + body,
                "//oreslang:checked-i64-v1\n" + body,
                "//  oreslang:checked-i64-v1\n" + body,
                "// oreslang:checked-i64-v2\n" + body,
                "// oreslang:checked-i64-v1 trailing\n" + body,
                HEADER + HEADER + body,
                HEADER + body + HEADER)) {
            IllegalArgumentException rejected = assertThrows(IllegalArgumentException.class,
                    () -> AttoCheckedI64Profile.select(invalid));
            assertTrue(rejected.getMessage().contains("checked-i64-v1"));
        }
        assertTrue(AttoCheckedI64Profile.select(HEADER + body));
        assertFalse(AttoCheckedI64Profile.select(body));
        assertFalse(AttoCheckedI64Profile.select(
                "/* outer /* // oreslang:checked-i64-v2 */ nested */\n" + body));
        assertTrue(AttoCheckedI64Profile.select(
                HEADER + "/* outer /* // oreslang:checked-i64-v2 */ nested */\n" + body));
        assertTrue(AttoCheckedI64Profile.select(
                HEADER + "fnc text(): String {return \"// oreslang:checked-i64-v2\";}\n"));
    }

    @Test
    void checkedCallerCannotSilentlyExecuteAnUncheckedImportedUnit() {
        // Java's per-code-unit evaluator profile must not be mistaken for a
        // transitive module-graph guarantee before the linker checks imports.
        String source = HEADER + "import * as imported from \"./unchecked\";\n"
                + FUNCTIONS + "pub fnc main(): int {return 42;}\n";
        try (Context context = Context.newBuilder("ores").allowAllAccess(false).build()) {
            PolyglotException failure = assertThrows(PolyglotException.class,
                    () -> context.eval("ores", source));
            assertTrue(failure.getMessage().contains("ATTO_CHECKED_I64_IMPORT_GRAPH_UNSUPPORTED"),
                    () -> "no mixed-profile import graph admitted: " + failure.getMessage());
        }
    }

    @Test
    void runtimeRejectsSpoofedProfileBeforeGuestEvaluation() {
        String source = HEADER + HEADER + FUNCTIONS
                + "pub fnc main(): int {return 42;}\n";
        try (Context context = Context.newBuilder("ores").allowAllAccess(false).build()) {
            PolyglotException failure = assertThrows(PolyglotException.class,
                    () -> context.eval("ores", source));
            assertTrue(failure.getMessage().contains("checked-i64-v1"));
        }
    }
}
