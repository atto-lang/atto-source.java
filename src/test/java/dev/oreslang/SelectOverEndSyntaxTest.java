package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SelectOverEndSyntaxTest {
    @Test
    void supportsBlockingProceduralAndNonblockingOverEndForms() {
        Ast.Program program = Parser.parse("""
                pub fnc forms(): void {
                  select first over
                    default: {}
                  end

                  do select fair over
                    default: {}
                  end

                  nb select random over
                    default: {}
                  end
                  return;
                }
                """);

        Ast.FunctionDecl fn =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();

        Ast.SelectStmt blocking = (Ast.SelectStmt) fn.body().get(0);
        assertEquals(Ast.WaitMode.BLOCKING, blocking.mode());
        assertEquals(Ast.SelectPolicy.PRIORITY, blocking.policy());
        assertFalse(blocking.explicitDo());

        Ast.SelectStmt procedural = (Ast.SelectStmt) fn.body().get(1);
        assertEquals(Ast.WaitMode.BLOCKING, procedural.mode());
        assertEquals(Ast.SelectPolicy.FAIR, procedural.policy());
        assertTrue(procedural.explicitDo());

        Ast.SelectStmt nonblocking = (Ast.SelectStmt) fn.body().get(2);
        assertEquals(Ast.WaitMode.NONBLOCKING, nonblocking.mode());
        assertEquals(Ast.SelectPolicy.RANDOM, nonblocking.policy());
        assertFalse(nonblocking.explicitDo());

        assertEquals(1, blocking.arms().size());
        assertEquals(1, procedural.arms().size());
        assertEquals(1, nonblocking.arms().size());
    }

    @Test
    void bracedAndOverEndFormsLowerToEquivalentSelectShape() {
        Ast.Program program = Parser.parse("""
                pub fnc forms(): void {
                  select { default: {} }
                  select over default: {} end
                  do select { default: {} }
                  do select over default: {} end
                  nb select { default: {} }
                  nb select over default: {} end
                  do nb select { default: {} }
                  do nb select over default: {} end
                  return;
                }
                """);

        Ast.FunctionDecl fn =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();

        for (int i = 0; i < 8; i += 2) {
            Ast.SelectStmt braced = (Ast.SelectStmt) fn.body().get(i);
            Ast.SelectStmt ended = (Ast.SelectStmt) fn.body().get(i + 1);
            assertEquals(braced.mode(), ended.mode());
            assertEquals(braced.policy(), ended.policy());
            assertEquals(braced.explicitDo(), ended.explicitDo());
            assertEquals(braced.arms().size(), ended.arms().size());
            assertEquals(braced.arms().getFirst().operation(), ended.arms().getFirst().operation());
        }
    }
    @Test
    void overEndBlockingFormsExecuteThroughTheExistingSelectRuntime() throws Exception {
        String source = """
                pub fnc main(): void {
                  select over
                    default: {
                      stdio.stdout.write("plain:");
                    }
                  end
                  do select over
                    default: {
                      stdio.stdout.write("do");
                    }
                  end
                  return;
                }
                """;

        assertEquals(0,
                OresCompiler.parseAndTypeCheckWithDiagnostics(source).warnings().size());
        assertEquals("plain:do", run(source));
    }

    @Test
    void nonblockingOverEndKeepsExistingActorAdmissionSemantics() {
        String source = """
                actor fnc consume(): void {
                  nb select over
                    default: {}
                  end
                  return;
                }
                """;

        assertEquals(0,
                OresCompiler.parseAndTypeCheckWithDiagnostics(source).warnings().size());
    }

    @Test
    void overEndReadAndWriteArmsUseTheExistingAtomicSelectRuntime() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Channel<int> incoming = Channel.new<int>(1);
                  writech incoming, 7;
                  select over
                    when readch incoming: val number -> {
                      stdio.stdout.write(number);
                    }
                  end

                  val Channel<int> outgoing = Channel.new<int>(1);
                  do select over
                    when writech outgoing, 9: {
                      stdio.stdout.write(":sent:");
                    }
                  end
                  stdio.stdout.write(readch outgoing);
                  return;
                }
                """;

        assertEquals(0,
                OresCompiler.parseAndTypeCheckWithDiagnostics(source).warnings().size());
        assertEquals("7:sent:9", run(source));
    }

    @Test
    void doSelectOverReturnAndDeferPreserveProceduralControlFlow() throws Exception {
        String source = """
                pub fnc choose(): int {
                  do select over
                    default: {
                      defer stdio.stdout.write("cleanup:");
                      stdio.stdout.write("arm:");
                      return 23;
                    }
                  end
                  return 99;
                }

                pub fnc main(): void {
                  stdio.stdout.write(choose());
                  return;
                }
                """;

        assertEquals(0,
                OresCompiler.parseAndTypeCheckWithDiagnostics(source).warnings().size());
        assertEquals("arm:cleanup:23", run(source));
    }

    @Test
    void nestedOverEndSelectionsOwnTheirEndsStructurally() {
        Ast.Program program = Parser.parse("""
                pub fnc nested(): void {
                  select over
                    default: {
                      do select over
                        default: {}
                      end
                    }
                  end
                  return;
                }
                """);

        Ast.FunctionDecl fn =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.SelectStmt outer = (Ast.SelectStmt) fn.body().getFirst();
        Ast.SelectStmt inner = (Ast.SelectStmt) outer.arms().getFirst().body().getFirst();

        assertFalse(outer.explicitDo());
        assertTrue(inner.explicitDo());
        assertEquals(Ast.WaitMode.BLOCKING, outer.mode());
        assertEquals(Ast.WaitMode.BLOCKING, inner.mode());
    }


    @Test
    void overEndAwaitArmUsesTheExistingFutureSelectRuntime() throws Exception {
        String source = """
                async fnc compute(): int { return 14; }

                pub routine main(): void {
                  val Channel<int> empty = Channel.new<int>(1);
                  val Future<int> future = compute();
                  do select first over
                    when readch empty: int value -> {
                      stdio.stdout.write("bad");
                    }
                    when await future: int result -> {
                      stdio.stdout.write(result);
                    }
                  end
                  return;
                }
                """;

        assertEquals(0,
                OresCompiler.parseAndTypeCheckWithDiagnostics(source).warnings().size());
        assertEquals("14", run(source));
    }

    @Test
    void overEndBreakAndFinallyPropagateThroughProceduralSelect() throws Exception {
        String source = """
                pub fnc main(): void {
                  loop {
                    do select over
                      default: {
                        try {
                          stdio.stdout.write("arm:");
                          break;
                        } catch (err) {
                          stdio.stdout.write("bad:");
                        } finally {
                          stdio.stdout.write("finally:");
                        }
                      }
                    end
                  }
                  stdio.stdout.write("done");
                  return;
                }
                """;

        assertEquals(0,
                OresCompiler.parseAndTypeCheckWithDiagnostics(source).warnings().size());
        assertEquals("arm:finally:done", run(source));
    }


    @Test
    void overEndContinueAfterCooperateDoesNotReplaySelectedArm() throws Exception {
        String source = """
                pub fnc count(): int {
                  let int passes = 0;
                  loop {
                    passes = passes + 1;
                    do select over
                      default: {
                        rt cooperate;
                        if passes < 3; then
                          continue;
                        fi
                        break;
                      }
                    end
                  }
                  return passes;
                }

                pub async fnc main(): void {
                  stdio.stdout.write(count());
                  return;
                }
                """;

        assertEquals(0,
                OresCompiler.parseAndTypeCheckWithDiagnostics(source).warnings().size());
        assertEquals("3", run(source));
    }


    @Test
    void immediateTrySelectOverRetainsImmediateWaitMode() {
        Ast.Program program = Parser.parse("""
                pub fnc probe(): void {
                  try select over
                    default: {}
                  end
                  return;
                }
                """);

        Ast.FunctionDecl fn =
                (Ast.FunctionDecl) program.modules().getFirst().declarations().getFirst();
        Ast.SelectStmt selected = (Ast.SelectStmt) fn.body().getFirst();
        assertEquals(Ast.WaitMode.IMMEDIATE, selected.mode());
        assertFalse(selected.explicitDo());
    }


    private static String run(String text) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, text, "select-over-end.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }

}
