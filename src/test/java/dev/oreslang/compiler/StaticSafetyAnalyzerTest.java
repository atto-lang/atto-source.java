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
    void reportsThirtyActorSpawnSitesInRecursiveActorHandler() {
        StringBuilder source = new StringBuilder("""
                define actor Worker as
                    pub receive(): void {
                """);
        for (int i = 0; i < 30; i++) source.append("spawn Worker();\\n".replace("\\n", "\n"));
        source.append("""
                        return;
                    }
                end
                """);
        var warnings = StaticSafetyAnalyzer.analyze(Parser.parse(source.toString()));
        assertTrue(warnings.stream().anyMatch(f ->
                        f.rule().equals("ORES-AMP-001") && f.message().contains("30 static actor-spawn site")),
                warnings.toString());
        assertTrue(hasRule(warnings, "ORES-AMP-002"),
                "multiple syntactic spawn sites inside a cycle require a fanout-bound proof");
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

    @Test
    void conditionalExitInUnconditionalLoopDoesNotProveTermination() {
        var warnings = StaticSafetyAnalyzer.analyze(Parser.parse("""
                pub routine main(bool stop): void {
                    loop {
                        if stop {
                            break;
                        } fi
                    }
                }
                """));
        assertTrue(hasRule(warnings, "ORES-TERM-003"), warnings.toString());
    }

    @Test
    void literalFalseExitIsNotCountedAsReachable() {
        var warnings = StaticSafetyAnalyzer.analyze(Parser.parse("""
                pub routine main(): void {
                    loop {
                        if false {
                            break;
                        } fi
                    }
                }
                """));
        assertTrue(hasRule(warnings, "ORES-TERM-002"), warnings.toString());
    }

    @Test
    void firstStatementUnconditionalBreakDoesNotWarnOnInfiniteLoop() {
        var warnings = StaticSafetyAnalyzer.analyze(Parser.parse("""
                pub routine main(): void {
                    loop {
                        break;
                    }
                    return;
                }
                """));
        assertFalse(hasRule(warnings, "ORES-TERM-002"), warnings.toString());
        assertFalse(hasRule(warnings, "ORES-TERM-003"), warnings.toString());
    }

    @Test
    void dynamicHostInputIsAReviewObligationNotProvenInjection() {
        var warnings = StaticSafetyAnalyzer.analyze(Parser.parse("""
                pub routine main(string command): void {
                    process.run(command);
                    return;
                }
                """));
        assertTrue(hasRule(warnings, "ORES-SEC-010"), warnings.toString());
        assertTrue(hasRule(warnings, "ORES-SEC-011"), warnings.toString());
        assertTrue(warnings.stream().allMatch(f -> f.confidence().equals("potential")));
    }

    @Test
    void LiteralHostArgumentDoesNotTriggerDynamicInputDiagnostic() {
        var warnings = StaticSafetyAnalyzer.analyze(Parser.parse("""
                pub routine main(): void {
                    process.run("static-command");
                    return;
                }
                """));
        assertTrue(hasRule(warnings, "ORES-SEC-010"), warnings.toString());
        assertFalse(hasRule(warnings, "ORES-SEC-011"), warnings.toString());
    }

    @Test
    void SingleStaticActorSpawnSiteIsNotCalledMultiplicative() {
        var warnings = StaticSafetyAnalyzer.analyze(Parser.parse("""
                define actor Worker as
                    pub receive(): void {
                        spawn Worker();
                        return;
                    }
                end
                """));
        assertTrue(hasRule(warnings, "ORES-AMP-001"), warnings.toString());
        assertFalse(hasRule(warnings, "ORES-AMP-002"), warnings.toString());
    }
}
