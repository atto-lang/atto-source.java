package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SelectOverAmongProceduralTest {
    @Test
    void endDelimitedOverAndAmongAreEquivalentProceduralForms() {
        String program = """
                fnc forms(Channel<int> ch): void {
                  do select first over
                    when readch ch: val first -> {
                      stdio.println(first);
                    }
                    default -> {
                    }
                  end

                  do select fair among
                    case readch ch: const second -> {
                      stdio.println(second);
                    }
                    default -> {
                    }
                  end
                  return;
                }
                """;
        Ast.Program checked = TypeChecker.check(Parser.parse(program));
        Ast.FunctionDecl function =
                (Ast.FunctionDecl) checked.modules().getFirst().declarations().getFirst();
        Ast.SelectStmt first = (Ast.SelectStmt) function.body().get(0);
        Ast.SelectStmt second = (Ast.SelectStmt) function.body().get(1);
        assertTrue(first.explicitDo());
        assertTrue(second.explicitDo());
        assertEquals(Ast.SelectPolicy.PRIORITY, first.policy());
        assertEquals(Ast.SelectPolicy.FAIR, second.policy());
        assertEquals(2, first.arms().size());
        assertEquals(2, second.arms().size());
    }

    @Test
    void blockingDoSelectAmongPropagatesReturnToCallable() throws Exception {
        String program = """
                pub fnc answer(): int {
                  val Channel<int> incoming = Channel.new<int>(1);
                  writech incoming, 6;
                  do select among
                    when readch incoming: val n -> {
                      return n + 8;
                    }
                    default -> {
                      return -1;
                    }
                  end
                  return -2;
                }

                pub fnc main(): void {
                  stdio.stdout.write(answer());
                  return;
                }
                """;
        assertEquals(0,
                OresCompiler.parseAndTypeCheckWithDiagnostics(program).warnings().size());
        assertEquals("14", run(program));
    }

    @Test
    void nestedOverAmongCloseStructurally() {
        String program = """
                fnc nested(Channel<int> outer, Channel<int> inner): void {
                  do select over
                    when readch outer: val x -> {
                      do select among
                        when readch inner: val y -> {
                          stdio.println(x + y);
                        }
                      end
                    }
                  end
                  return;
                }
                """;
        TypeChecker.check(Parser.parse(program));
    }

    @Test
    void malformedDelimitersAndAmbiguousValueSyntaxFailClosed() {
        String[] invalid = {
            "do select over default -> {} }",
            "do select among default -> {}",
            "do select over default -> {} default -> {} end",
            "do select over default -> {} when readch ch: val x -> {} end",
            "select over default -> { return 1; } end;",
            "nb select among default -> { return 1; } end;"
        };
        for (String form : invalid) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("fnc bad(Channel<int> ch): void { "
                            + form + " return; }"), form);
        }
    }

    @Test
    void contextualDelimitersRemainLegalBindingNames() {
        TypeChecker.check(Parser.parse("""
                fnc names(int over, int among): int {
                  val int result = over + among;
                  return result;
                }
                """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "select-over-among.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
