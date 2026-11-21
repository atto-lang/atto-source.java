package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class IncrementalDiagnosticsTest {
    private static String sixIndependentParserErrors() {
        StringBuilder code = new StringBuilder("define module diagnostics as\n");
        for (int n = 1; n <= 6; n++) {
            code.append("  pub fnc bad").append(n)
                    .append("(): int { return 1 + ; }\n");
        }
        return code.append("end\n").toString();
    }

    @Test
    void sixRealSyntaxErrorsWithinOneSourceProduceFiveThenSix() {
        Map<String, String> sources = Map.of("src/diagnostics.ores", sixIndependentParserErrors());
        var compiler = new IncrementalCompiler();
        CompilationDiagnosticsException five = assertThrows(
                CompilationDiagnosticsException.class,
                () -> compiler.compileWithDiagnostics(sources, 5));
        assertEquals(5, five.diagnostics().size());
        assertTrue(five.mayHaveMore());
        assertEquals(List.of(2, 3, 4, 5, 6),
                five.diagnostics().stream().map(d -> d.primary().line()).toList());
        assertTrue(five.diagnostics().stream()
                .allMatch(d -> d.phase() == DiagnosticCollector.Phase.PARSE));

        CompilationDiagnosticsException ten = assertThrows(
                CompilationDiagnosticsException.class,
                () -> compiler.compileWithDiagnostics(sources, 10));
        assertEquals(6, ten.diagnostics().size());
        assertEquals(List.of(2, 3, 4, 5, 6, 7),
                ten.diagnostics().stream().map(d -> d.primary().line()).toList());
        assertFalse(ten.mayHaveMore());

        CompilationDiagnosticsException one = assertThrows(
                CompilationDiagnosticsException.class,
                () -> compiler.compileWithDiagnostics(sources, 1));
        assertEquals(1, one.diagnostics().size());
        assertTrue(one.mayHaveMore());
    }

    @Test
    void multipleFilesAreOrderedRegardlessOfInputMapIteration() {
        var inputs = new LinkedHashMap<String, String>();
        inputs.put("z.ores", sixIndependentParserErrors());
        inputs.put("a.ores", sixIndependentParserErrors());
        var failure = assertThrows(CompilationDiagnosticsException.class,
                () -> new IncrementalCompiler().compileWithDiagnostics(inputs, 5));
        assertEquals(5, failure.diagnostics().size());
        assertTrue(failure.diagnostics().stream()
                .allMatch(d -> d.primary().unitId().endsWith("a.ores")));
    }

    @Test
    void nonBracedMalformedSourceStopsRatherThanInventingSiblingErrors() {
        var collector = new DiagnosticCollector(10);
        String malformed = "define module broken as\n"
                + "  pub fnc first(): int\n"
                + "    return 1 + ;\n"
                + "  end\n"
                + "  pub fnc second(): int { return 1 + ; }\n"
                + "end\n";
        assertTrue(dev.oreslang.parser.Parser.collectSyntaxDiagnostics(
                malformed, "broken.ores", collector));
        assertEquals(1, collector.diagnostics().size());
        assertTrue(collector.mayHaveMore());
    }

    @Test
    void bracesInStringLiteralsDoNotBecomeRecoveryBoundaries() {
        String source = "define module strings as\n"
                + "  pub fnc first(): int { let string sample = \"}\"; return 1 + ; }\n"
                + "  pub fnc second(): int { return 2 + ; }\n"
                + "end\n";
        var failure = assertThrows(CompilationDiagnosticsException.class,
                () -> new IncrementalCompiler().compileWithDiagnostics(
                        Map.of("strings.ores", source), 10));
        assertEquals(2, failure.diagnostics().size());
    }

    @Test
    void missingBraceCannotBeRecoveredByGuessingATopLevelBoundary() {
        String source = "define module unclosed as\n"
                + "  pub fnc first(): int { return 1 + ;\n"
                + "  pub fnc second(): int { return 2 + ; }\n"
                + "end\n";
        var failure = assertThrows(CompilationDiagnosticsException.class,
                () -> new IncrementalCompiler().compileWithDiagnostics(
                        Map.of("unclosed.ores", source), 10));
        assertEquals(1, failure.diagnostics().size());
        assertTrue(failure.mayHaveMore());
    }

    @Test
    void cleanParseStillUsesNormalSemanticAndOwnershipChecks() {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> new IncrementalCompiler().compileWithDiagnostics(
                        Map.of("broken.ores", """
                                define module broken as
                                  pub fnc amount(): int { return "wrong"; }
                                end
                                """), 5));
        assertFalse(failure instanceof CompilationDiagnosticsException,
                "type failure must not be reclassified as a recovered parse error");
    }

    @Test
    void independentSemanticFailuresInDifferentUnitsAreCollectedWithoutFabricatingSpans() {
        var sources = new LinkedHashMap<String, String>();
        sources.put("z.ores", """
                define module zed as
                  pub fnc value(): int { return "wrong"; }
                end
                """);
        sources.put("a.ores", """
                define module alpha as
                  pub fnc value(): int { return "wrong"; }
                end
                """);
        var failure = assertThrows(CompilationDiagnosticsException.class,
                () -> new IncrementalCompiler().compileWithDiagnostics(sources, 5));
        assertEquals(2, failure.diagnostics().size());
        assertEquals(List.of("a.ores", "z.ores"),
                failure.diagnostics().stream().map(d -> d.primary().unitId()).toList());
        assertTrue(failure.diagnostics().stream().allMatch(d ->
                d.phase() == DiagnosticCollector.Phase.CHECK
                    && d.primary().line() == 1
                    && d.message().contains("exact source span unavailable")));
        assertFalse(failure.mayHaveMore());
    }

    @Test
    void sixDifferentSemanticSourceUnitsRespectFiveErrorCeiling() {
        var sources = new LinkedHashMap<String, String>();
        for (int i = 6; i >= 1; i--) {
            sources.put("src/unit" + i + ".ores",
                    "define module mod" + i + " as\n"
                    + "  pub fnc broken(): int { return \"bad\"; }\n"
                    + "end\n");
        }
        var five = assertThrows(CompilationDiagnosticsException.class,
                () -> new IncrementalCompiler().compileWithDiagnostics(sources, 5));
        assertEquals(5, five.diagnostics().size());
        assertTrue(five.mayHaveMore());
        assertEquals(List.of("src/unit1.ores", "src/unit2.ores",
                            "src/unit3.ores", "src/unit4.ores", "src/unit5.ores"),
                five.diagnostics().stream().map(d -> d.primary().unitId()).toList());

        var all = assertThrows(CompilationDiagnosticsException.class,
                () -> new IncrementalCompiler().compileWithDiagnostics(sources, 10));
        assertEquals(6, all.diagnostics().size());
        assertFalse(all.mayHaveMore());
    }

    @Test
    void rejectedCompilationNeverCommitsPartialCacheState() {
        var compiler = new IncrementalCompiler();
        assertThrows(CompilationDiagnosticsException.class,
                () -> compiler.compileWithDiagnostics(
                        Map.of("broken.ores", sixIndependentParserErrors()), 5));
        // Independent valid source must compile after rejection without relying
        // on a partial parse unit or incorrectly cached graph.
        var valid = compiler.compileWithDiagnostics(Map.of("good.ores", """
                define module good as
                  pub fnc one(): int { return 1; }
                end
                """), 5);
        assertTrue(valid.units().keySet().stream().anyMatch(id -> id.endsWith("good.ores")));
    }
}
