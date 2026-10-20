package dev.oreslang;

import dev.oreslang.compiler.StaticFactsV0Exporter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Real .ores -> parser -> type/ownership checker -> v0 fact export. */
final class StaticFactsV0ExporterTest {
    private static final String DIRECT = """
            fnc double_it(int n): int { return n + n; }
            pub routine main(): int { return double_it(5); }
            """;

    @Test
    void compilerAdmittedCallsHaveDeterministicTypedFacts() {
        String actual = StaticFactsV0Exporter.fromSource(DIRECT);
        assertEquals(actual, StaticFactsV0Exporter.fromSource(DIRECT));
        assertTrue(actual.contains("\"version\":\"ores-static-facts/v0\""), actual);
        assertTrue(actual.contains("\"unit\":\"sha256:"), actual);
        assertTrue(actual.contains("\"id\":\"__root__.main/0\""), actual);
        assertTrue(actual.contains("\"id\":\"__root__.double_it/1\""), actual);
        assertTrue(actual.contains("\"kind\":\"call\",\"fanout\":1,\"guard\":\"unproved\""), actual);
        assertTrue(actual.contains("\"to\":\"__root__.double_it/1\""), actual);
        assertFalse(actual.contains("\"proved_terminates\""), actual);
    }

    @Test
    void recursiveCallRemainsAnUnprovedCycle() {
        String source = """
                fnc again(int value): int {
                    return again(value);
                }
                pub routine main(): int { return again(5); }
                """;
        String actual = StaticFactsV0Exporter.fromSource(source);
        assertTrue(actual.contains("\"from\":\"__root__.again/1\",\"to\":\"__root__.again/1\""), actual);
        assertTrue(actual.contains("\"guard\":\"unproved\""), actual);
    }

    @Test
    void whileLoopDoesNotFabricateTerminationOrSafepointProofs() {
        String source = """
                pub routine main(): void {
                    while (true) {
                        break;
                    }
                    return;
                }
                """;
        String actual = StaticFactsV0Exporter.fromSource(source);
        assertTrue(actual.contains("\"loops\":[{\"id\":\"L1\",\"exit\":\"unknown\",\"safepoint\":false}]"), actual);
    }

    @Test
    void byteDifferentSourcesCannotReuseUnitIdentity() {
        String a = StaticFactsV0Exporter.fromSource(DIRECT);
        String b = StaticFactsV0Exporter.fromSource(DIRECT + "\n");
        assertNotEquals(a, b);
    }

    @Test
    void unsupportedObjectsAndDynamicRuntimeEffectsFailClosed() {
        String source = """
                define class Box as
                    pub let int value = 0;
                end
                pub routine main(): void { return; }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> StaticFactsV0Exporter.fromSource(source));
    }

    @Test
    void nonScalarLiteralCannotBeLaunderedAsPureCompute() {
        String source = """
                pub routine main(): void {
                    "not-an-integer";
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> StaticFactsV0Exporter.fromSource(source));
    }

    @Test
    void producerAndConsumerShareExactProcedureBudget() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 513; i++) {
            source.append("fnc scalar_").append(i)
                    .append("(): int { return ").append(i).append("; }\n");
        }
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StaticFactsV0Exporter.fromSource(source.toString()));
        assertTrue(failure.getMessage().contains("oversized procedure collection"),
                failure.getMessage());
    }

    @Test
    void exporterRejectsMoreLoopsThanConsumerBudget() {
        StringBuilder source = new StringBuilder("pub routine main(): void {\n");
        for (int i = 0; i < 257; i++) {
            source.append("while (true) { break; }\n");
        }
        source.append("return; }\n");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StaticFactsV0Exporter.fromSource(source.toString()));
        assertTrue(failure.getMessage().contains("too many loops"),
                failure.getMessage());
    }

    @Test
    void deeplyNestedValidBooleanExpressionIsRejectedDeterministically() {
        String source = "pub routine main(): bool { return "
                + "!".repeat(150) + "true; }";
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StaticFactsV0Exporter.fromSource(source));
        assertTrue(failure.getMessage().contains("nested AST exceeds recursive traversal depth budget"),
                failure.getMessage());
    }

    @Test
    void repeatedCallSiteCannotBeSilentlyDeduplicated() {
        String source = """
                fnc leaf(): int { return 3; }
                pub routine main(): int {
                    leaf();
                    return leaf();
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> StaticFactsV0Exporter.fromSource(source));
        assertTrue(failure.getMessage().contains("multiple static call sites"),
                failure.getMessage());
    }

    @Test
    void invalidSourceRejectedBeforeFactProduction() {
        assertThrows(IllegalArgumentException.class,
                () -> StaticFactsV0Exporter.fromSource("fnc x(): int { return unknown; }"));
    }
}
