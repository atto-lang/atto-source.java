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

final class ValueDispatchControlFlowTest {
    private static void admitted(String source) {
        var program = TypeChecker.check(Parser.parse(source));
        OwnershipChecker.check(program);
    }

    private static String execute(String source) throws Exception {
        admitted(source);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source built = Source.newBuilder(OresLanguage.ID, source, "value-dispatch.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(built);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void expressionArmsYieldLocallyWhileDoArmsReturnFromFunction() throws Exception {
        String output = execute("""
                fnc matched(int v): string {
                  const x = match v over
                    on 1 { return "one"; }
                    on _ { return "other"; }
                  end
                  return x;
                }

                fnc returnedMatch(int v): string {
                  return match v over
                    on 1 { return "one"; }
                    on _ { return "other"; }
                  end
                }

                fnc discardedMatch(int v): string {
                  match v over
                    on _ { return "discarded"; }
                  end
                  return "continued";
                }

                fnc proceduralMatch(int v): string {
                  do match v over
                    on 1 { return "stopped"; }
                  end
                  return "continued";
                }

                fnc terminalDoMatch(int v): string {
                  return do match v over
                    on 1 { return "stopped"; }
                    on _ { return "fallback"; }
                  end
                }

                fnc switched(int v): string {
                  const x = switch v
                    case 1 { return "one"; }
                    default { return "other"; }
                  end
                  return x;
                }

                fnc returnedSwitch(int v): string {
                  return switch v
                    case 1 { return "one"; }
                    default { return "other"; }
                  end
                }

                fnc proceduralSwitch(int v): string {
                  do switch v
                    case 1 { return "stopped"; }
                  end
                  return "continued";
                }

                fnc terminalDoSwitch(int v): string {
                  return do switch v
                    case 1 { return "stopped"; }
                    default { return "fallback"; }
                  end
                }

                pub fnc main(): void {
                  stdio.println(matched(1));
                  stdio.println(returnedMatch(2));
                  stdio.println(discardedMatch(2));
                  stdio.println(proceduralMatch(2));
                  stdio.println(terminalDoMatch(1));
                  stdio.println(switched(1));
                  stdio.println(returnedSwitch(2));
                  stdio.println(proceduralSwitch(2));
                  stdio.println(terminalDoSwitch(1));
                  return;
                }
                """);
        assertEquals("one\nother\ncontinued\ncontinued\nstopped\none\nother\ncontinued\nstopped\n", output);
    }

    @Test
    void rejectNonExhaustiveAndMissingArmValueButAllowPartialDo() {
        IllegalArgumentException noMatch = assertThrows(IllegalArgumentException.class,
                () -> admitted("""
                        fnc broken(int x): string {
                          return match x over
                            on 1 { return "one"; }
                          end
                        }
                        """));
        assertTrue(noMatch.getMessage().contains("non-exhaustive"), noMatch.getMessage());

        IllegalArgumentException noYield = assertThrows(IllegalArgumentException.class,
                () -> admitted("""
                        fnc broken(int x): string {
                          return match x over
                            on _ { stdio.println("side effect"); }
                          end
                        }
                        """));
        assertTrue(noYield.getMessage().contains("does not return a value"), noYield.getMessage());

        IllegalArgumentException noSwitch = assertThrows(IllegalArgumentException.class,
                () -> admitted("""
                        fnc broken(int x): string {
                          return switch x
                            case 1 { return "one"; }
                          end
                        }
                        """));
        assertTrue(noSwitch.getMessage().contains("non-exhaustive"), noSwitch.getMessage());

        assertDoesNotThrow(() -> admitted("""
                fnc okay(int x): string {
                  do match x over
                    on 1 { return "one"; }
                  end
                  do switch x
                    case 2 { return "two"; }
                  end
                  return "other";
                }
                """));

        IllegalArgumentException noReturn = assertThrows(IllegalArgumentException.class,
                () -> admitted("""
                        fnc broken(int x): string {
                          return do switch x
                            case 1 { return "one"; }
                            default { stdio.println("not terminal"); }
                          end
                        }
                        """));
        assertTrue(noReturn.getMessage().contains("must return"), noReturn.getMessage());
    }

    @Test
    void nestedLambdaReturnDoesNotEscapeIntoValueArm() {
        var ast = Parser.parse("""
                fnc scoped(int x): int {
                  return match x over
                    on _ {
                      const handler = || -> { return 7; };
                      return 42;
                    }
                  end
                }
                """);
        Ast.FunctionDecl function = (Ast.FunctionDecl)
                ast.modules().getFirst().declarations().getFirst();
        Ast.ReturnStmt outer = (Ast.ReturnStmt) function.body().getFirst();
        Ast.ValueMatchExpr value = (Ast.ValueMatchExpr) outer.value();
        Ast.BindingStmt binding = (Ast.BindingStmt) value.arms().getFirst().body().getFirst();
        Ast.LambdaExpr lambda = (Ast.LambdaExpr) binding.initializer();
        assertInstanceOf(Ast.ReturnStmt.class, lambda.blockBody().getFirst());
        assertInstanceOf(Ast.ArmReturnStmt.class, value.arms().getFirst().body().get(1));
    }

    @Test
    void awaitingValueArmsPreservesReturnTargetAndRunsCleanup() throws Exception {
        assertEquals("finally-match\ndefer-match\n42\ndefer-switch\n41\n", execute("""
                async fnc answer(): int {
                  return 41;
                }

                async fnc collected(): int {
                  const result = match 1 over
                    on _ {
                      defer stdio.println("defer-match");
                      try {
                        return (await answer()) + 1;
                      } catch (error) {
                        return 0;
                      } finally {
                        stdio.println("finally-match");
                      }
                    }
                  end
                  return result;
                }

                async fnc switched(): int {
                  return switch 1
                    default { return 0; }
                    case 1 {
                      defer stdio.println("defer-switch");
                      return await answer();
                    }
                  end
                }

                pub async fnc main(): void {
                  stdio.println(await collected());
                  stdio.println(await switched());
                  return;
                }
                """));
    }

    @Test
    void nestedObjectLoopAndTryMaySuspendInValueDispatch() throws Exception {
        assertEquals("41\nnested-finally\nnested-defer\n7\n", execute("""
                async fnc answer(): int { return 41; }

                async fnc nested(): int {
                  return match 1 over
                    on _ {
                      defer stdio.println("nested-defer");
                      try {
                        loop {
                          const payload = infer struct{data: await answer()};
                          stdio.println(payload.data);
                          break;
                        }
                      } catch (error) {
                        return 0;
                      } finally {
                        stdio.println("nested-finally");
                      }
                      return 7;
                    }
                  end
                }

                pub async fnc main(): void {
                  stdio.println(await nested());
                  return;
                }
                """));
    }

    @Test
    void proceduralReturnInsideAwaitedValueArmTargetsContainingFunction() throws Exception {
        assertEquals("cleanup-early\n99\n", execute("""
                async fnc answer(): int { return 41; }

                async fnc early(): int {
                  return match 1 over
                    on _ {
                      defer stdio.println("cleanup-early");
                      try {
                        const x = await answer();
                        do switch x
                          case 41 { return 99; }
                        end
                        return 0;
                      } catch (error) {
                        return 1;
                      } finally {
                        stdio.println("finally-early");
                      }
                    }
                  end
                }

                pub async fnc main(): void {
                  stdio.println(await early());
                  return;
                }
                """).replace("finally-early\n", ""));
    }

    @Test
    void lexicalArmReturnSurvivesTryFinallyAndRunsCleanupExactlyOnce() throws Exception {
        assertEquals("finally\narm-defer\narm\ndo-finally\ndo-defer\nearly\ncase\ndefault\n",
                execute("""
                fnc collected(): string {
                  return match 1 over
                    on _ {
                      defer stdio.println("arm-defer");
                      try {
                        return "arm";
                      } catch (error) {
                        return "caught";
                      } finally {
                        stdio.println("finally");
                      }
                    }
                  end
                }

                fnc early(): string {
                  do switch 1
                    default { return "wrong-default"; }
                    case 1 {
                      defer stdio.println("do-defer");
                      try {
                        return "early";
                      } catch (error) {
                        return "caught";
                      } finally {
                        stdio.println("do-finally");
                      }
                    }
                  end
                  return "continued";
                }

                fnc selected(int v): string {
                  return switch v
                    default { return "default"; }
                    case 1 { return "case"; }
                  end
                }

                pub fnc main(): void {
                  stdio.println(collected());
                  stdio.println(early());
                  stdio.println(selected(1));
                  stdio.println(selected(2));
                  return;
                }
                """));
    }

    @Test
    void tryCatchMustYieldOnAllNormalBranchesAndSwitchDefaultIsUnique() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> admitted("""
                        fnc broken(): string {
                          return match 1 over
                            on _ {
                              try { stdio.println("no return"); }
                              catch (failure) { return "caught"; }
                              finally { stdio.println("finally"); }
                            }
                          end
                        }
                        """));
        assertTrue(missing.getMessage().contains("does not return a value"), missing.getMessage());

        IllegalArgumentException duplicateDefault = assertThrows(IllegalArgumentException.class,
                () -> admitted("""
                        fnc broken(int v): string {
                          return switch v
                            default { return "fallback"; }
                            case 1 { return "one"; }
                            default { return "again"; }
                          end
                        }
                        """));
        assertTrue(duplicateDefault.getMessage().contains("only one default"), duplicateDefault.getMessage());
    }

    @Test
    void finallyCannotReplaceReturnButLambdaInsideFinallyCanReturnLocally() throws Exception {
        assertEquals("lambda\ncleanup\nouter\n", execute("""
                fnc outer(): string {
                  try {
                    return "outer";
                  } catch (error) {
                    return "caught";
                  } finally {
                    const local = (|| -> {
                      return "lambda";
                    })();
                    stdio.println(local);
                    stdio.println("cleanup");
                  }
                }

                pub fnc main(): void {
                  stdio.println(outer());
                  return;
                }
                """));
    }

    @Test
    void rejectDirectAndNestedFinallyReturnsButKeepIndependentLambdaScope() {
        String[] forbidden = {
                """
                fnc broken(): string {
                  try { return "try"; }
                  catch (e) { return "catch"; }
                  finally { return "invalid"; }
                }
                """,
                """
                fnc broken(): string {
                  try { return "try"; }
                  catch (e) { return "catch"; }
                  finally {
                    if true { return "invalid"; }
                  }
                }
                """,
                """
                fnc broken(int v): string {
                  try { return "try"; }
                  catch (e) { return "catch"; }
                  finally {
                    do switch v
                      case 1 { return "invalid"; }
                    end
                  }
                }
                """,
                """
                fnc broken(): string {
                  try { return "try"; }
                  catch (e) { return "catch"; }
                  finally {
                    const x = match 1 over
                      on _ { return "invalid"; }
                    end
                  }
                }
                """,
                """
                fnc broken(): string {
                  try { return "try"; }
                  catch (e) { return "catch"; }
                  finally {
                    try { return "invalid"; }
                    catch (nested) { stdio.println("caught"); }
                  }
                }
                """,
                """
                fnc broken(): string {
                  try { return "try"; }
                  catch (e) { return "catch"; }
                  finally {
                    const nested = (|| -> {
                      try { return "lambda"; }
                      catch (inner) { return "caught"; }
                      finally { return "invalid even in lambda's own finally"; }
                    })();
                  }
                }
                """
        };
        for (String source : forbidden) {
            IllegalArgumentException problem = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), source);
            assertTrue(problem.getMessage().contains("return is forbidden inside finally"),
                    problem.getMessage());
        }
        assertDoesNotThrow(() -> admitted("""
                fnc legal(): string {
                  try { return "try"; }
                  catch (error) { return "catch"; }
                  finally {
                    const nested = (|| -> {
                      return "lambda";
                    })();
                  }
                }
                """));
    }

    @Test
    void finallyCannotBreakOrContinueAnEnclosingLoop() {
        for (String control : new String[] { "break", "continue" }) {
            String source = """
                    fnc broken(): string {
                      loop {
                        try { return "pending"; }
                        catch (error) { return "caught"; }
                        finally {
                          %s;
                        }
                      }
                      return "continued";
                    }
                    """.formatted(control);
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> admitted(source), source);
            assertTrue(error.getMessage().contains(control + " cannot escape a finally block"),
                    error.getMessage());
        }
    }

    @Test
    void localLoopControlWithinFinallyMayFinishCleanupWithoutReplacingReturn() throws Exception {
        assertEquals("cleanup\noriginal\n", execute("""
                fnc scoped(): string {
                  try { return "original"; }
                  catch (error) { return "caught"; }
                  finally {
                    loop {
                      break;
                    }
                    stdio.println("cleanup");
                  }
                }

                pub fnc main(): void {
                  stdio.println(scoped());
                  return;
                }
                """));
    }

    @Test
    void typeCheckerRejectsDirectAstReturnInjectedIntoFinally() {
        Ast.Program program = Parser.parse("""
                fnc target(): string {
                  try { return "original"; }
                  catch (error) { return "caught"; }
                  finally { stdio.println("cleanup"); }
                }
                """);
        Ast.ModuleDecl module = program.modules().getFirst();
        Ast.FunctionDecl original = (Ast.FunctionDecl) module.declarations().getFirst();
        Ast.TryStmt attempted = (Ast.TryStmt) original.body().getFirst();
        Ast.TryStmt injected = new Ast.TryStmt(
                attempted.body(), attempted.errorName(), attempted.catchBody(),
                java.util.List.of(new Ast.ReturnStmt(new Ast.LiteralExpr("illegal"))));
        Ast.FunctionDecl forged = new Ast.FunctionDecl(
                original.name(), original.kind(), original.visibility(),
                original.async(), original.generator(), original.structural(),
                original.nonLexical(), original.trapped(), original.actorKind(),
                original.genericParameters(), original.parameters(),
                original.returnType(), original.annotations(), java.util.List.of(injected));
        Ast.Program invalid = new Ast.Program(
                program.namespace(), program.imports(),
                java.util.List.of(new Ast.ModuleDecl(
                        module.name(), module.annotations(), module.contracts(),
                        java.util.List.of(forged))));
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(invalid));
        assertTrue(failure.getMessage().contains("return is forbidden inside finally"),
                failure.getMessage());
    }

}
