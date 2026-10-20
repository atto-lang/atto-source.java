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
    void invalidSourceRejectedBeforeFactProduction() {
        assertThrows(IllegalArgumentException.class,
                () -> StaticFactsV0Exporter.fromSource("fnc x(): int { return unknown; }"));
    }
}
