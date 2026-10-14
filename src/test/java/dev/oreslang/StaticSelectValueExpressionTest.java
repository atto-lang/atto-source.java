package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StaticSelectValueExpressionTest {
    @Test
    void parsesValueSelectAsExpressionWithLexicalArmReturn() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc compute(Channel<int> ch): int {
                  const int result = select over
                    when readch ch: int x -> {
                      return x * 2;
                    }
                    default -> {
                      return 0;
                    }
                  end;
                  return result + 1;
                }
                """));
        Ast.FunctionDecl function = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().getFirst();
        Ast.BindingStmt binding = assertInstanceOf(
                Ast.BindingStmt.class, function.body().getFirst());
        Ast.StaticSelectExpr expression = assertInstanceOf(
                Ast.StaticSelectExpr.class, binding.initializer());
        Ast.SelectArm arm = expression.select().arms().getFirst();
        assertEquals("int", arm.declaredBindingType().name());
        Ast.ReturnStmt returned = assertInstanceOf(
                Ast.ReturnStmt.class, arm.body().getFirst());
        assertEquals(Ast.ReturnTarget.SELECT_VALUE, returned.target());
        assertEquals(Ast.ReturnTarget.FUNCTION,
                ((Ast.ReturnStmt) function.body().getLast()).target());
    }

    @Test
    void blockingValueSelectReturnsTypedReadPayloadInSourceTask() throws Exception {
        String code = """
                fnc compute(Channel<int> ch): int {
                  const int result = select over
                    when readch ch: int x -> {
                      return x * 2;
                    }
                    default -> {
                      return 0;
                    }
                  end;
                  return result + 1;
                }
                pub fnc main(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  writech ch, 7;
                  stdio.stdout.write(compute(ch));
                  return;
                }
                """;
        assertEquals("15", run(code));
    }

    @Test
    void defaultResultIsAnExpressionAndSupportsNestedResults() throws Exception {
        String code = """
                pub fnc main(): void {
                  const int v = select among
                    default -> {
                      const int inner = select over
                        default -> {
                          return 12;
                        }
                      end;
                      return inner + 3;
                    }
                  end;
                  stdio.stdout.write(v);
                  return;
                }
                """;
        assertEquals("15", run(code));
    }

    @Test
    void exhaustiveIfBranchesReturnValue() throws Exception {
        String code = """
                pub fnc main(): void {
                  const int v = select over
                    default -> {
                      if true then
                        return 4;
                      else
                        return 5;
                      fi
                    }
                  end;
                  stdio.stdout.write(v);
                  return;
                }
                """;
        assertEquals("4", run(code));
    }

    @Test
    void nestedProceduralReturnStillExitsContainingCallable() throws Exception {
        String code = """
                pub fnc compute(): int {
                  const int v = select over
                    default -> {
                      do select over
                        default -> {
                          return 9;
                        }
                      end
                      return 1;
                    }
                  end;
                  return v + 100;
                }
                pub fnc main(): void {
                  stdio.stdout.write(compute());
                  return;
                }
                """;
        assertEquals("9", run(code));
    }

    @Test
    void valueArmReturnRunsDeferredCleanupExactlyOnce() throws Exception {
        String code = """
                pub fnc main(): void {
                  const int v = select over
                    default -> {
                      defer stdio.stdout.write("cleanup:");
                      return 7;
                    }
                  end;
                  stdio.stdout.write(v);
                  return;
                }
                """;
        assertEquals("cleanup:7", run(code));
    }

    @Test
    void valueArmReturnPreservesTryFinally() throws Exception {
        String code = """
                pub fnc main(): void {
                  const int v = select over
                    default -> {
                      try {
                        return 3;
                      } catch (err) {
                        return 4;
                      } finally {
                        stdio.stdout.write("finally:");
                      }
                    }
                  end;
                  stdio.stdout.write(v);
                  return;
                }
                """;
        assertEquals("finally:3", run(code));
    }

    @Test
    void valueExpressionRejectsMissingReturnAndTypeErrors() {
        String[] invalid = {
            "select over default -> { 7; } end",
            "select over default -> {} end",
            "select over default -> { return; } end",
            "select over default -> { if true then return 1; fi } end",
            "select over default -> { return 1; } default -> { return 2; } end",
            "select over default -> { return 1; } when readch ch: int x -> { return x; } end",
            "select over when readch ch: string x -> { return 1; } default -> { return 0; } end",
            "select over when readch ch: int x -> { return 1; } default -> { return \"x\"; } end",
            "select over end",
        };
        for (String expression : invalid) {
            String source = """
                    fnc bad(Channel<int> ch): int {
                      const int selected = """ + expression + """
                    ;
                      return selected;
                    }
                    """;
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(source)), expression);
        }
    }

    @Test
    void nonblockingEndDelimitedValueSelectIsNotSilentlyAStatement() {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse("""
                actor fnc wrong(): void {
                  const int selected = nb select over
                    default -> { return 1; }
                  end;
                  return;
                }
                """));
    }

    @Test
    void compilerAcceptsValueReturnWithoutSelectReturnWarnings() {
        String code = """
                fnc answer(): int {
                  const int result = select over
                    default -> { return 9; }
                  end;
                  return result;
                }
                """;
        assertTrue(OresCompiler.parseAndTypeCheckWithDiagnostics(code)
                .warnings().isEmpty());
    }

    private static String run(String code) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "select-value.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            ctx.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
