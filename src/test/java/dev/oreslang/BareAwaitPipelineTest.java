package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class BareAwaitPipelineTest {
    private static Ast.Expr returned(String expr) {
        var p = Parser.parseStrictAtto("fnc f(Future<int> x): int { return " + expr + "; }");
        var fn = (Ast.FunctionDecl) p.modules().getFirst().declarations().getFirst();
        return ((Ast.ReturnStmt) fn.body().getFirst()).value();
    }

    @Test void bareStageUsesOrdinaryAwaitAst() {
        assertEquals(returned("await x"), returned("x |> await"));
        var call = assertInstanceOf(Ast.CallExpr.class, returned("x |> await |> identity"));
        assertEquals(returned("await x"), call.arguments().getFirst());
        assertFalse(((Ast.AwaitExpr) call.arguments().getFirst()).coreAdapter());
    }

    @Test void rejectsExtraOperandAndTypeArguments() {
        for (String expr : new String[] {"x |> await()", "x |> await(x)", "x |> await<int>"}) {
            assertThrows(IllegalArgumentException.class, () -> returned(expr));
        }
    }

    @Test void guestAwaitsThenMatchesThenCalls() throws Exception {
        String source = """
            async fnc answer(): int { rt cooperate; return 41; }
            fnc identity(int x): int { return x; }
            pub fnc main(): void {
                val int n = answer() |> await
                    |> match<int> over { on 41 -> 42, default -> 0, }
                    |> identity;
                stdio.stdout.write(n);
                return;
            }
            """;
        var program = OresCompiler.parseAndTypeCheckStrictAtto(source);
        var output = new ByteArrayOutputStream();
        String name = "bare-await-pipeline.atto";
        try (var lease = OresLanguage.openPrecheckedProgram(name, source, program);
             Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, name).build());
        }
        assertEquals("42", output.toString(StandardCharsets.UTF_8));
    }

    @Test void immediateIntIsIdentityInBothFormsAndThroughContinuation() throws Exception {
        String source = """
            fnc identity(int x): int { return x; }
            fnc prefixed(int x): int { return await x; }
            pub fnc main(): void {
                const f2 = 42 |> await;
                const f3 = 42 |> await |> identity;
                stdio.stdout.write(f2);
                stdio.stdout.write(f3);
                stdio.stdout.write(prefixed(42));
                const f4 = 42 |> |x| -> await x |> |x| -> x;
                stdio.stdout.write(f4);
                return;
            }
            """;
        var program = OresCompiler.parseAndTypeCheckStrictAtto(source);
        var output = new ByteArrayOutputStream();
        String name = "immediate-int-await.atto";
        try (var lease = OresLanguage.openPrecheckedProgram(name, source, program);
             Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, name).build());
        }
        assertEquals("42424242", output.toString(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () ->
            OresCompiler.parseAndTypeCheckStrictAtto("pub fnc main(): int { return 42 |> std.await; }"));
    }
}
