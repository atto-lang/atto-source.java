package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class ValueMatchExpressionTest {
    @Test
    void valueMatchProducesValueWithoutEscapingCallable() throws Exception {
        String program = """
            fnc fromValue(Option<int> v): int {
                val int result = match v over {
                    on Some(n) { return n + 1; }
                    on None { return 0; }
                };
                return result + 100;
            }
            fnc fromDo(Option<int> v): int {
                do match v over {
                    on Some(n) { return n + 1; }
                    on None { return 0; }
                }
            }
            pub fnc main(): void {
                stdio.println(fromValue(Some(41)));
                stdio.println(fromValue(None));
                stdio.println(fromDo(Some(41)));
                stdio.println(fromDo(None));
                return;
            }
            """;
        var typed = TypeChecker.check(Parser.parse(program));
        OwnershipChecker.check(typed);
        var output = new ByteArrayOutputStream();
        var guest = Source.newBuilder(OresLanguage.ID,program,"value-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            ctx.eval(guest);
        }
        assertEquals("142\n100\n42\n0",
            output.toString(StandardCharsets.UTF_8).trim().replace("\r",""));
    }

    @Test
    void returnMatchHasDistinctAst() {
        var p=Parser.parse("""
            fnc f(bool x): int {
                return match x over {
                    on true { return 1; }
                    on false { return 0; }
                };
            }
            """);
        var fn=(Ast.FunctionDecl)p.modules().getFirst().declarations().getFirst();
        var value=(Ast.ReturnStmt)fn.body().getFirst();
        assertInstanceOf(Ast.MatchExpr.class,value.value());
        assertEquals(2,((Ast.MatchExpr)value.value()).arms().size());
    }

    @Test
    void missingValueAndMultipleStatementsFailClosed() {
        for(String arm : new String[]{
            "on Some(n) { return; }",
            "on Some(n) { n + 1; }",
            "on Some(n) { val int x = n; return x; }"
        }) {
            String program="fnc f(Option<int> v): int { return match v over { "+
                arm+" on None { return 0; } }; }";
            assertThrows(IllegalArgumentException.class,()->Parser.parse(program));
        }
    }

    @Test
    void nonExhaustiveAndMixedTypesFail() {
        assertThrows(IllegalArgumentException.class,()->TypeChecker.check(Parser.parse("""
            fnc f(Option<int> v): int {
                return match v over { on Some(n) { return n; } };
            }
            """)));
        assertThrows(IllegalArgumentException.class,()->TypeChecker.check(Parser.parse("""
            fnc f(bool x): int {
                return match x over {
                    on true { return 1; }
                    on false { return "wrong"; }
                };
            }
            """)));
    }
}
