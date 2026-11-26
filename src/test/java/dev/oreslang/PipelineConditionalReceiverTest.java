package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class PipelineConditionalReceiverTest {
    private static Ast.Expr returned(String expr) {
        var program = Parser.parseStrictAtto("fnc f(): int { return " + expr + "; }");
        var fn = (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        return ((Ast.ReturnStmt) fn.body().getFirst()).value();
    }
    @Test void ordinaryConditionalAstIsReused() {
        var expected = returned("if 42 is type int then 30 else 20 fi");
        assertEquals(expected, returned("42 |> if is int then 30 else 20 fi"));
        assertEquals(expected, returned("42 |> if is type int then 30 else 20 fi"));
        assertEquals(expected, returned("42 |> is type int ? 30 : 20"));
        assertEquals(returned("true ? 30 : 20"), returned("true |> ? 30 : 20"));
        assertEquals(returned("42 > 10 ? 30 : 20"), returned("42 |> > 10 ? 30 : 20"));
        var continued = assertInstanceOf(Ast.CallExpr.class,
                returned("true |> ? 30 : 20 |> increment"));
        assertEquals(returned("true ? 30 : 20"), continued.arguments().getFirst());
    }
    @Test void malformedAndIllTypedReceiversFailClosed() {
        for (String expr : new String[] {
                "42 |> if 42 > 10 then 30 else 20 fi",
                "42 |> if > 10 then 30 fi",
                "true |> ? 30",
                "42 |> if then 30 else 20 fi",
                "true |> ? 30 : false"
        }) {
            assertThrows(IllegalArgumentException.class, () ->
                    OresCompiler.parseAndTypeCheckStrictAtto("fnc f(): int { return " + expr + "; }"), expr);
        }
    }
    @Test void realGuestEvaluatesReceiverOnceAndOnlySelectedBranches() throws Exception {
        String source = """
            fnc input(): int { stdio.stdout.write("S"); return 42; }
            fnc losing(): int { stdio.stdout.write("L"); return 20; }
            fnc increment(int n): int { return n + 1; }
            pub routine main(): void {
                const int a = input() |> if is int then 30 else losing() fi;
                const int b = 42 |> is type int ? 30 : losing();
                const int c = 42 |> > 10 ? 30 : losing();
                const int d = true |> ? 30 : losing();
                const int e = true |> if then 30 else losing() fi;
                const int f = 42 |> await |> if > 10 then 30 else losing() fi
                    |> increment |> > 30 |> ? 100 : losing();
                stdio.stdout.write(a+b+c+d+e+f);
            }
            """;
        var program = OresCompiler.parseAndTypeCheckStrictAtto(source);
        var output = new ByteArrayOutputStream();
        String name = "pipeline-conditional.ores";
        try (var lease = OresLanguage.openPrecheckedProgram(name, source, program);
             Context context = Context.newBuilder(OresLanguage.ID).allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, name).build());
        }
        assertEquals("S250", output.toString(StandardCharsets.UTF_8));
    }
}
