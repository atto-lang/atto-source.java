package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class MaybeTryIntegrationTest {
    private static String run(String code) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "maybe-try.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    @Test
    void keywordTryCanContainReturnMaybeAndRunFinallyExactlyOnce() throws Exception {
        String code = """
                fnc choose(Result<int, String> input): int {
                  try
                    return maybe
                      Ok(value) ?= input;
                      value + 1
                    else
                      on Err(reason) -> { 0 };
                    end;
                  catch()
                    return 99;
                  finally
                    stdio.stdout.write("F");
                  end
                }
                pub fnc main(): void {
                  stdio.stdout.write(choose(Ok(7)));
                  stdio.stdout.write("|");
                  stdio.stdout.write(choose(Err("bad")));
                  return;
                }
                """;
        var checked = TypeChecker.check(Parser.parse(code));
        OwnershipChecker.check(checked);
        assertEquals("F8|F0", run(code));
    }

    @Test
    void doMaybeAndKeywordTryKeepTheirDistinctEndDelimiters() throws Exception {
        String code = """
                fnc choose(Result<int, String> input): int {
                  try
                    do maybe
                      Ok(value) ?= input;
                      return value + 1;
                    else
                      Err(reason) -> { return 0; };
                    end
                  finally
                    stdio.stdout.write("F");
                  end
                }
                pub fnc main(): void {
                  stdio.stdout.write(choose(Ok(7)));
                  stdio.stdout.write("|");
                  stdio.stdout.write(choose(Err("bad")));
                  return;
                }
                """;
        var ast = Parser.parse(code);
        var fn = (Ast.FunctionDecl) ast.modules().getFirst().declarations().getFirst();
        var outer = (Ast.TryStmt) fn.body().getFirst();
        assertEquals(1, outer.finallyBody().size());
        assertInstanceOf(Ast.BlockStmt.class, outer.body().getFirst());
        OwnershipChecker.check(TypeChecker.check(ast));
        assertEquals("F8|F0", run(code));
    }

    @Test
    void badMaybeBodyDeferUnderTryCannotEscapeCleanupGate() {
        String source = """
                fnc choose(Result<int, String> input): int {
                  try
                    return maybe
                      Ok(value) ?= input;
                      defer stdio.stdout.write("WRONG");
                      value
                    else
                      Err(reason) -> { 0 };
                    end;
                  finally
                    stdio.stdout.write("F");
                  end
                }
                """;
        var problem = assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(problem.getMessage().contains("typed cleanup CFG"), problem::getMessage);
    }

    @Test
    void bareMaybeIdentifierDoesNotBecomeKeywordMaybeExpression() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc id(int maybe): int {
                  try
                    return maybe;
                  finally
                    stdio.stdout.write("F");
                  end
                }
                """));
    }
}
