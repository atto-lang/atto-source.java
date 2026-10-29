package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
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
        for (String body : new String[]{
                "value", "print(42)", "await value", "rt take value",
                "gpu { 42 }", "cpu task { return 42; }",
                "40 / 2", "\"host-effect\""
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(prefix + body + " };"),
                    "unadmitted scalar region: " + body);
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
