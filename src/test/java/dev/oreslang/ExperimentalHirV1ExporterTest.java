package dev.oreslang;

import dev.oreslang.compiler.ExperimentalHirV1Exporter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Fail-closed, actual-parser source-to-HIR smoke and rejection fixtures. */
final class ExperimentalHirV1ExporterTest {
    private static final String SIMPLE = """
            pub fnc counter_response(int count): int {
                return count * 43;
            }
            pub fnc main(): int {
                return counter_response(1);
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
}
