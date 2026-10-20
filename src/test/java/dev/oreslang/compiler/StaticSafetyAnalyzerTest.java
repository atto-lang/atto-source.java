package dev.oreslang.compiler;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class StaticSafetyAnalyzerTest {
    private static boolean hasRule(List<StaticSafetyAnalyzer.Finding> findings, String rule) {
        return findings.stream().anyMatch(f -> f.rule().equals(rule));
    }

    @Test
    void analyzesActualTypecheckedSourceWithLoop() {
        var warnings = OresCompiler.analyzeStaticSafety("""
                pub routine main(): void {
                    loop {
                        rt cooperate;
                    }
                }
                """);
        assertTrue(hasRule(warnings, "ORES-TERM-002"), warnings.toString());
        assertTrue(warnings.stream().allMatch(f -> f.confidence().equals("potential")));
    }

    @Test
    void findsFunctionRecursionInParsedSource() {
        var warnings = StaticSafetyAnalyzer.analyze(Parser.parse("""
                fnc again(): int { return again(); }
                pub routine main(): void { return; }
                """));
        assertTrue(hasRule(warnings, "ORES-AMP-004"), warnings.toString());
    }

    @Test
    void findsActorSpawnCycleUsingRealParserAst() {
        var warnings = StaticSafetyAnalyzer.analyze(Parser.parse("""
                define actor Worker as
                    pub receive(): void {
                        spawn Worker();
                        return;
                    }
                end
                """));
        assertTrue(hasRule(warnings, "ORES-AMP-001"), warnings.toString());
    }

    @Test
    void flagsRepeatedActorSpawnInLoop() {
        var warnings = StaticSafetyAnalyzer.analyze(Parser.parse("""
                define actor Worker as
                    pub receive(): void { return; }
                end
                pub routine main(): void {
                    loop {
                        spawn Worker();
                    }
                }
                """));
        assertTrue(hasRule(warnings, "ORES-AMP-005"), warnings.toString());
    }

    @Test
    void doesNotPretendOrdinaryFiniteCodeIsRecursive() {
        var warnings = StaticSafetyAnalyzer.analyze(Parser.parse("""
                fnc answer(): int { return 42; }
                pub routine main(): void {
                    val x = answer();
                    return;
                }
                """));
        assertFalse(hasRule(warnings, "ORES-AMP-004"), warnings.toString());
        assertFalse(hasRule(warnings, "ORES-TERM-002"), warnings.toString());
    }

    @Test
    void hostProcessExecutionCallsRequireSeparateSecurityProof() {
        var warnings = StaticSafetyAnalyzer.analyze(Parser.parse("""
                pub routine main(): void {
                    process.run("external");
                    return;
                }
                """));
        assertTrue(hasRule(warnings, "ORES-SEC-010"), warnings.toString());
        assertTrue(warnings.stream().allMatch(f -> f.confidence().equals("potential")));
    }
}
