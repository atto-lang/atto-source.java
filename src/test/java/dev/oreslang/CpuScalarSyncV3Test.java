package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stage-v3 positive guest source execution for an intentionally closed CPU
 * scalar subset. This is NOT buffer/loan, actor-task, GPU or SIMD proof.
 */
@Timeout(20)
final class CpuScalarSyncV3Test {
    @Test void cpuScalarHasDedicatedAstAndPlainIntType() {
        String source = "pub fnc main(): int { return cpu { return 40 + 2; }; }";
        Ast.Program program = Parser.parse(source);
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().getFirst();
        Ast.ReturnStmt returning = (Ast.ReturnStmt) fn.body().getFirst();
        assertInstanceOf(Ast.CpuScalarRegionExpr.class, returning.value());
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test void realGuestRunsClosedSynchronousCpuScalarRegion() throws Exception {
        assertEquals("42|42|42", run("""
                pub fnc main(): void {
                  stdio.stdout.write(cpu { 40 + 2 });
                  stdio.stdout.write("|");
                  stdio.stdout.write(cpu { return 6 * 7; });
                  stdio.stdout.write("|");
                  stdio.stdout.write(cpu { return 45 - 3; });
                  return;
                }
                """));
    }

    @Test void dynamicReferencesAndHostEffectsCannotCrossTheScalarBoundary() {
        String prefix = "pub fnc main(): int { const int value = 42; return cpu { ";
        String suffix = " }; }";
        // Positive control: prove the surrounding function is valid, so the
        // negative corpus cannot pass only because main() was unterminated.
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                prefix + "40 + 2" + suffix));
        for (String body : new String[]{
                "value", "print(42)", "await value", "rt take value",
                "gpu { 42 }", "cpu task { return 42; }",
                "40 / 2", "\"host-effect\""
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(prefix + body + suffix),
                    "unadmitted scalar region: " + body);
            // These valid expression forms must fail at the *scalar-domain
            // admission gate*, not at an unrelated syntax/return-type error.
            if (body.equals("value") || body.equals("print(42)")
                    || body.equals("40 / 2") || body.equals("\"host-effect\"")) {
                assertTrue(error.getMessage().contains("cpu scalar execution region"),
                        () -> "wrong rejection gate for " + body + ": " + error.getMessage());
            }
        }
    }

    @Test void unloweredArbitraryCpuStatementsAndGpuStillFailClosed() {
        for (String body : new String[]{
                "let int x = 42; return x;",
                "return cpu { 42 };",
                "return gpu { 42 };"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(
                            "pub fnc main(): int { return cpu { " + body + " }; }"),
                    "unlowered domain body " + body);
        }
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(
                        "pub fnc main(): int { return gpu { 42 }; }"));
    }

    @Test void scalarOverflowIsCheckedNeverWrapped() {
        assertThrows(RuntimeException.class, () -> run("""
                pub fnc main(): void {
                  stdio.stdout.write(cpu { 9223372036854775807 + 1 });
                  return;
                }
                """));
    }

    @Test void checkedI64OverflowSurvivesBuildOptimization() {
        String source = "pub fnc main(): int { return cpu { -(-9223372036854775808) }; }";
        Ast.Program optimized = OresCompiler
                .compileForBuild(source, BuildOptions.executable())
                .program();
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                optimized.modules().getFirst().declarations().getFirst();
        Ast.ReturnStmt returning = (Ast.ReturnStmt) fn.body().getFirst();
        Ast.CpuScalarRegionExpr cpu = assertInstanceOf(
                Ast.CpuScalarRegionExpr.class, returning.value());
        assertInstanceOf(Ast.UnaryExpr.class, cpu.value(),
                "constant folding must not replace overflowing negation with wrapped MIN_VALUE");

        for (String expression : new String[]{
                "9223372036854775807 + 1",
                "-9223372036854775808 - 1",
                "9223372036854775807 * 2",
                "-(-9223372036854775808)"
        }) {
            String guest = "pub fnc main(): void { stdio.stdout.write(cpu { "
                    + expression + " }); return; }";
            assertThrows(RuntimeException.class, () -> run(guest),
                    "checked signed-i64 overflow must not wrap: " + expression);
        }
    }

    @Test void buildOptimizerPreservesTheExplicitCpuScalarExecutionBoundary() {
        String source = "pub fnc main(): int { return cpu { 40 + 2 }; }";
        Ast.Program optimized = OresCompiler
                .compileForBuild(source, BuildOptions.executable())
                .program();
        Ast.FunctionDecl fn = (Ast.FunctionDecl)
                optimized.modules().getFirst().declarations().getFirst();
        Ast.ReturnStmt returning = (Ast.ReturnStmt) fn.body().getFirst();
        assertInstanceOf(Ast.CpuScalarRegionExpr.class, returning.value(),
                "tree shaker may not silently erase a checked execution-domain boundary");
    }

    @Test void scalarRegionRejectsExcessiveDepthBeforeEvaluatorExecution() {
        // Depth counts AST nodes: 64 unary operators plus one literal is
        // 65 levels from root (depth 64) and is admitted.
        assertDoesNotThrow(() -> new Ast.CpuScalarRegionExpr(
                scalarUnaryTree(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.CpuScalarRegionExpr(scalarUnaryTree(65)));
        String deepSource = "pub fnc main():int{return cpu { "
                + "-".repeat(65) + "1 };}";
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(deepSource));
    }

    @Test void scalarRegionBoundsTotalNodesIndependentlyOfDepth() {
        // 256 leaves + 255 binary additions = 511 AST nodes. The next
        // leaf brings the balanced tree to 513 nodes, despite shallow depth.
        assertDoesNotThrow(() -> new Ast.CpuScalarRegionExpr(
                balancedScalarTree(256)));
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.CpuScalarRegionExpr(balancedScalarTree(257)));
    }

    @Test void lexerPreflightRejectsOversizedCpuRegionsBeforeRecursiveParsing() {
        String leading = "pub fnc main(): int { return cpu { ";
        String trailing = "1 }; }";
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(leading + "-".repeat(2000) + trailing));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(leading + "(".repeat(65) + "1"
                        + ")".repeat(65) + " }; }"));
        assertDoesNotThrow(() -> Parser.parse(leading + "(".repeat(64)
                + "1" + ")".repeat(64) + " }; }"));
    }

    @Test void directAstConstructionCannotSmuggleEffectsOrCaptures() {
        for (Ast.Expr invalid : new Ast.Expr[]{
                new Ast.NameExpr("secret"),
                new Ast.AwaitExpr(new Ast.LiteralExpr(42L)),
                new Ast.BinaryExpr("+", new Ast.LiteralExpr(1L),
                        new Ast.NameExpr("actor_state")),
                new Ast.LiteralExpr("42")
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> new Ast.CpuScalarRegionExpr(invalid));
        }
    }

    private static Ast.Expr scalarUnaryTree(int unaryCount) {
        Ast.Expr value = new Ast.LiteralExpr(1L);
        for (int i = 0; i < unaryCount; i++)
            value = new Ast.UnaryExpr("-", value);
        return value;
    }

    private static Ast.Expr balancedScalarTree(int leaves) {
        if (leaves == 1) return new Ast.LiteralExpr(1L);
        int left = leaves / 2;
        return new Ast.BinaryExpr("+",
                balancedScalarTree(left),
                balancedScalarTree(leaves - left));
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, sourceText, "cpu-sync-v3.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8).strip();
    }
}
