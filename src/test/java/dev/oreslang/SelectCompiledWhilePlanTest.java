package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

/**
 * Guest-source regression: while is lowered to the same resumable loop
 * machinery as for; each visit re-evaluates read-channel operands, while
 * the actor/root site retains a reusable SelectPlan and fair cursor.
 */
final class SelectCompiledWhilePlanTest {
    @Test
    void whileSelectIsOneResumableLoopWithStaticSelectBody() {
        Ast.Program parsed = Parser.parse("""
                fnc iterate(Channel<int> a): void {
                  let int n = 0;
                  while n < 4 {
                    select {
                      when readch a: let value {
                        n = n + 1;
                      }
                    }
                  }
                  return;
                }
                """);
        Ast.FunctionDecl fun = (Ast.FunctionDecl) parsed.modules()
                .getFirst().declarations().getFirst();
        Ast.ForStmt loop = assertInstanceOf(
                Ast.ForStmt.class, fun.body().get(1));
        assertEquals(null, loop.initializer());
        assertEquals(null, loop.update());
        assertInstanceOf(Ast.SelectStmt.class, loop.body().getFirst());
    }

    @Test
    void compiledWhileSelectServicesThreeAlwaysReadyArmsWithoutStarvation()
            throws Exception {
        String source = """
                pub fnc main(): void {
                  val Channel<int> control = Channel.new<int>(1);
                  val Channel<int> workers = Channel.new<int>(1);
                  val Channel<int> timers = Channel.new<int>(1);
                  writech control, 0;
                  writech workers, 1;
                  writech timers, 2;
                  let int ticks = 0;
                  while ticks < 12 {
                    select {
                      when readch control: let event {
                        stdio.stdout.write("C");
                        writech control, event;
                      }
                      when readch workers: let event {
                        stdio.stdout.write("W");
                        writech workers, event;
                      }
                      when readch timers: let event {
                        stdio.stdout.write("T");
                        writech timers, event;
                      }
                    }
                    ticks = ticks + 1;
                  }
                  return;
                }
                """;
        assertEquals("CWTCWTCWTCWT",
                SelectNestedReturnContractTest.run(source));
    }

    @Test
    void compiledWhileSelectReevaluatesChangedChannelBinding()
            throws Exception {
        String source = """
                pub fnc main(): void {
                  val Channel<int> first = Channel.new<int>(1);
                  val Channel<int> second = Channel.new<int>(1);
                  writech first, 7;
                  writech second, 8;
                  let Channel<int> active = first;
                  let int tick = 0;
                  while tick < 2 {
                    if tick == 1 {
                      active = second;
                    }
                    select {
                      when readch active: let event {
                        stdio.stdout.write(event);
                      }
                    }
                    tick = tick + 1;
                  }
                  return;
                }
                """;
        assertEquals("78", SelectNestedReturnContractTest.run(source));
    }
}
