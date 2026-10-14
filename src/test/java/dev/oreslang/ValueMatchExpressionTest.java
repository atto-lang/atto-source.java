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
    void typedValueMatchIsDistinctFromProceduralMatch() {
        String source = """
                fnc value(Option<int> x): int {
                  return match x over {
                    on Some(n) { return n + 1; }
                    on None { return 0; }
                  };
                }
                """;
        Ast.Program ast = Parser.parse(source);
        Ast.FunctionDecl fn = (Ast.FunctionDecl)ast.modules().getFirst().declarations().getFirst();
        Ast.ReturnStmt ret = (Ast.ReturnStmt)fn.body().getFirst();
        assertInstanceOf(Ast.MatchExpr.class, ret.value());
        assertEquals(2, ((Ast.MatchExpr)ret.value()).arms().size());
        assertDoesNotThrow(() -> OwnershipChecker.check(TypeChecker.check(ast)));
    }

    @Test
    void compiledGuestReturnsSelectedValueWithoutEscapingScope() throws Exception {
        String source = """
                fnc compute(Option<int> x): int {
                  val int offset = match x over {
                    on Some(n) { return n + 1; }
                    on None { return 0; }
                  };
                  return offset + 1;
                }
                pub fnc main(): void {
                  stdio.println(compute(Some(40)));
                  stdio.println(compute(None));
                  return;
                }
                """;
        assertDoesNotThrow(() -> OwnershipChecker.check(TypeChecker.check(Parser.parse(source))));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source program = Source.newBuilder(OresLanguage.ID,source,"value-match.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx=Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            ctx.eval(program);
        }
        assertEquals("42\n1",out.toString(StandardCharsets.UTF_8).trim().replace("\r",""));
    }

    @Test
    void nonExhaustiveAndBrokenArmsFailClosed() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                fnc bad(Option<int> x): int {
                  return match x over {
                    on Some(n) { return n; }
                  };
                }
                """)));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(Option<int> x): int {
                  return match x over {
                    on Some(n) { n + 1; }
                    on None { return 0; }
                  };
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                fnc bad(Option<int> x): int {
                  return match x over {
                    on Some(n) { return n; return 2; }
                    on None { return 0; }
                  };
                }
                """));
    }
}
