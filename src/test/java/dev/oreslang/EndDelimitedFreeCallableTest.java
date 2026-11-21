package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** End-delimited free/module bodies use the ordinary executable function AST. */
final class EndDelimitedFreeCallableTest {
    private static Ast.FunctionDecl first(String source) {
        return assertInstanceOf(Ast.FunctionDecl.class,
                Parser.parse(source).modules().getFirst().declarations().getFirst());
    }

    @Test
    void braceNewlineAndDoubleColonBodiesProduceEquivalentAst() {
        String braced = "fnc value(int n) -> int { if n > 0 then return n; fi return 0; }";
        String newline = """
                fnc value(int n) -> int
                  if n > 0 then
                    return n;
                  fi
                  return 0;
                end
                """;
        String marked = """
                fnc value(int n) -> int ::
                  if n > 0 then
                    return n;
                  fi
                  return 0;
                end
                """;
        assertEquals(first(braced).body(), first(newline).body());
        assertEquals(first(braced).body(), first(marked).body());
    }

    @Test
    void nestedMatchOwnsItsOwnEndKeyword() {
        Ast.FunctionDecl fn = first("""
                fnc check(int port) -> Result<int, string> ::
                  match port over
                    on 0 -> { return Err("bad"); }
                    on _ -> { return Ok(port); }
                  end
                end
                """);
        assertEquals("Result", fn.returnType().name());
        assertEquals(1, fn.body().size());
    }

    @Test
    void compactBodiesRequireExplicitMarkerAndEnd() {
        assertEquals(1, first("fnc identity(int n) -> int :: return n; end").body().size());
        for (String invalid : new String[] {
                "fnc identity(int n) -> int return n; end",
                "fnc identity(int n): int :: return n; end",
                "fnc identity(int n) -> int\n  return n;",
                "fnc identity(int n) -> int\n  return n;\nend\nend"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(invalid), invalid);
        }
    }

    @Test
    void endDelimitedFreeAndModuleFunctionsExecute() throws Exception {
        String program = """
                fnc add_one(int n) -> int
                  if n > 0 then
                    return n + 1;
                  fi
                  return 0;
                end

                define module math
                  pub fnc twice(int n) -> int ::
                    return n * 2;
                  end
                end

                pub fnc main(): void {
                  stdio.stdout.write(add_one(41));
                  stdio.stdout.write(":");
                  stdio.stdout.write(math.twice(4));
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "end-callables.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42:8", output.toString(StandardCharsets.UTF_8));
    }
}
