package dev.oreslang;

import dev.oreslang.compiler.ExperimentalHirV1Exporter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Fail-closed, actual-parser source-to-HIR smoke and rejection fixtures. */
final class ExperimentalHirV1ExporterTest {
    private static final String SIMPLE = """
            pub fnc counter_response(int count): int {
                return count;
            }
            pub fnc main(): int {
                return counter_response(43);
            }
            """;

    @Test
    void emitsVersionedDeterministicHirFromRealFrontend() {
        String json = ExperimentalHirV1Exporter.fromSource(SIMPLE);
        assertEquals(json, ExperimentalHirV1Exporter.fromSource(SIMPLE));
        assertTrue(json.contains("\"ir_family\":\"ores-hir\""), json);
        assertTrue(json.contains("\"schema_version\":\"1.0.0-experimental\""), json);
        assertTrue(json.contains("\"module_id\":\"source\""), json);
        assertTrue(json.contains("\"name\":\"counter_response\""), json);
        assertTrue(json.contains("\"function\":\"counter_response\""), json);
        assertTrue(json.contains("\"value\":\"43\""), json);
        assertTrue(json.indexOf("\"name\":\"counter_response\"") < json.indexOf("\"name\":\"main\""));
    }

    @Test
    void rejectsDivisionsAndEffectsInsteadOfSilentlyLowering() {
        assertThrows(RuntimeException.class, () -> ExperimentalHirV1Exporter.fromSource(
                "pub fnc main(): int { return 3 / 2; }"));
        assertThrows(RuntimeException.class, () -> ExperimentalHirV1Exporter.fromSource(
                "pub fnc main(): int { return rt borrow 3; }"));
    }

    @Test
    void rejectsMutableStatementsAndClassDeclarations() {
        assertThrows(RuntimeException.class, () -> ExperimentalHirV1Exporter.fromSource(
                "pub fnc main(): int { let int x = 4; return x; }"));
        assertThrows(RuntimeException.class, () -> ExperimentalHirV1Exporter.fromSource(
                "define class Box as end\n" + SIMPLE));
    }

    @Test
    void rejectsSourceWithUnknownFunctionAndWrongArgumentTypes() {
        assertThrows(RuntimeException.class, () -> ExperimentalHirV1Exporter.fromSource(
                "pub fnc main(): int { return unavailable(1); }"));
        assertThrows(RuntimeException.class, () -> ExperimentalHirV1Exporter.fromSource(
                "pub fnc main(): int { return true; }"));
    }
    @Test
    void rejectsPotentialRuntimeOverflowButAdmitsUniversallySafeArithmetic() {
        assertThrows(IllegalArgumentException.class, () -> ExperimentalHirV1Exporter.fromSource("""
                pub fnc scale(int count): int { return count * 43; }
                pub fnc main(): int { return scale(1); }
                """));
        assertThrows(IllegalArgumentException.class, () -> ExperimentalHirV1Exporter.fromSource("""
                pub fnc main(): int { return 9223372036854775807 + 1; }
                """));
        String safe = ExperimentalHirV1Exporter.fromSource("""
                pub fnc identity(int value): int { return value + 0; }
                pub fnc main(): int { return identity(40 + 3); }
                """);
        assertTrue(safe.contains("\"op\":\"add\""));
    }

    @Test
    void rejectsRecursiveFunctionsAtExperimentalContractBoundary() {
        assertThrows(RuntimeException.class, () -> ExperimentalHirV1Exporter.fromSource("""
                pub fnc main(): int { return main(); }
                """));
        assertThrows(RuntimeException.class, () -> ExperimentalHirV1Exporter.fromSource("""
                pub fnc first(): int { return second(); }
                pub fnc second(): int { return first(); }
                """));
    }


    @Test
    void checkedDirectiveNegotiationMatchesTheExecutableParser() {
        String ordinary = "pub fnc main(): int { return 43; }\n";
        String directive = "// oreslang:checked-i64-v1\n";
        assertDoesNotThrow(() -> ExperimentalHirV1Exporter.fromSource(ordinary),
                "ordinary universally safe scalar code remains exportable");
        assertDoesNotThrow(() -> ExperimentalHirV1Exporter.fromSource(directive + ordinary),
                "the exact v1 directive remains accepted");

        String[] malformed = {
                "\n" + directive + ordinary,
                " " + directive + ordinary,
                "\t" + directive + ordinary,
                "\uFEFF" + directive + ordinary,
                "// oreslang:checked-i64-v1\r\n" + ordinary,
                "// oreslang:checked-i64-v2\n" + ordinary,
                "// oreslang:checked-i64-v1 trailing\n" + ordinary,
                directive + directive + ordinary,
                ordinary + directive
        };
        for (String source : malformed) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> ExperimentalHirV1Exporter.fromSource(source),
                    "malformed checked directive must never fall back to unmarked HIR");
            assertTrue(failure.getMessage().contains("checked-i64-v1"),
                    "diagnostic must identify the rejected checked profile");
        }
    }

}
