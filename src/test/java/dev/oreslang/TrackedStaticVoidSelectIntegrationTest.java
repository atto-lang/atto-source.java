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

final class TrackedStaticVoidSelectIntegrationTest {
    @Test
    void producesFutureVoidAstWithoutConflatingProceduralSelect() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                actor fnc invoke(Channel<int> inbox, Future<int> pending): void {
                  const Future<void> done = nb select first {
                    when readch inbox: int message -> { stdio.stdout.write(message); }
                    when await pending: int result -> { stdio.stdout.write(result); }
                  };
                  await done;
                  return;
                }
                """));
        Ast.FunctionDecl function = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().getFirst();
        Ast.BindingStmt binding = assertInstanceOf(
                Ast.BindingStmt.class, function.body().getFirst());
        Ast.StaticSelectExpr expression = assertInstanceOf(
                Ast.StaticSelectExpr.class, binding.initializer());
        assertEquals(Ast.WaitMode.NONBLOCKING, expression.select().mode());
        assertEquals(Ast.SelectPolicy.PRIORITY, expression.select().policy());
        assertEquals(Ast.ChannelOperation.AWAIT,
                expression.select().arms().get(1).operation());
    }

    @Test
    void defaultArmCompletionIsObservableOnlyAfterDefer() throws Exception {
        assertEquals("before:arm:cleanup:after", run("""
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    const Future<void> pending = nb select first {
                      default: -> {
                        stdio.stdout.write("arm:");
                        defer stdio.stdout.write("cleanup:");
                      }
                    };
                    stdio.stdout.write("before:");
                    await pending;
                    stdio.stdout.write("after");
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = await spawn Worker();
                  worker.send("go");
                  await worker.get_done();
                  return;
                }
                """));
    }

    @Test
    void immediatelyAwaitedFormUsesSameActorLane() throws Exception {
        assertEquals("start:arm:cleanup:done", run("""
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    stdio.stdout.write("start:");
                    await nb select first {
                      default: -> {
                        stdio.stdout.write("arm:");
                        defer stdio.stdout.write("cleanup:");
                      }
                    };
                    stdio.stdout.write("done");
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = await spawn Worker();
                  worker.send("go");
                  await worker.get_done();
                  return;
                }
                """));
    }

    @Test
    void futureArmWinsEmptyReadWithoutConsumingUnrelatedChannel() throws Exception {
        assertEquals("future:17:done", run("""
                async fnc compute(): int { return 17; }
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val Channel<int> inbox = Channel.new<int>(1);
                    const Future<int> pending = compute();
                    const Future<void> selected = nb select first {
                      when readch inbox: int message -> {
                        stdio.stdout.write("unexpected");
                      }
                      when await pending: int value -> {
                        stdio.stdout.write("future:");
                        stdio.stdout.write(value);
                      }
                    };
                    await selected;
                    stdio.stdout.write(":done");
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = await spawn Worker();
                  worker.send("go");
                  await worker.get_done();
                  return;
                }
                """));
    }

    @Test
    void losingFutureObserverIsDetachedButProducerRemainsAvailable() throws Exception {
        assertEquals("read:5:producer:23", run("""
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val Channel<int> inbox = Channel.new<int>(1);
                    val Channel<int> producerInput = Channel.new<int>(1);
                    const Future<int> producer = nb readch producerInput;
                    const Future<void> selected = nb select first {
                      when readch inbox: int message -> {
                        stdio.stdout.write("read:");
                        stdio.stdout.write(message);
                      }
                      when await producer: int result -> {
                        stdio.stdout.write("wrong-winner");
                      }
                    };
                    writech inbox, 5;
                    await selected;
                    writech producerInput, 23;
                    const int result = await producer;
                    stdio.stdout.write(":producer:");
                    stdio.stdout.write(result);
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = await spawn Worker();
                  worker.send("go");
                  await worker.get_done();
                  return;
                }
                """));
    }

    @Test
    void nonactorAndValueProducingArmsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        fnc bad(): void {
                          const Future<void> pending = nb select first {
                            default: -> { stdio.stdout.write("no"); }
                          };
                          return;
                        }
                        """));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        actor fnc bad(): void {
                          const Future<void> pending = nb select first {
                            default: -> { return 42; }
                          };
                          return;
                        }
                        """));
        IllegalArgumentException procedural = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        actor fnc bad(): void {
                          await do select first {
                            default: -> { return; }
                          };
                          return;
                        }
                        """));
        assertTrue(procedural.getMessage().contains("await do select"),
                procedural::getMessage);
    }

    @Test
    void voidAwaitIsAnEffectButCannotBeStoredAsAValue() {
        for (String prefix : new String[] {"const void result", "const result"}) {
            String source = """
                    actor fnc bad(): void {
                      %s = await nb select first {
                        default: -> { stdio.stdout.write("unreachable"); }
                      };
                      return;
                    }
                    """.formatted(prefix);
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), prefix);
            assertTrue(error.getMessage().contains("void"), error::getMessage);
        }
    }

    @Test
    void staticSelectDoesNotDegradeToDetachedLegacyStatement() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                actor fnc f(): void {
                  nb select first {
                    default: -> { stdio.stdout.write("legacy"); }
                  }
                  return;
                }
                """));
        Ast.FunctionDecl function = (Ast.FunctionDecl)
                program.modules().getFirst().declarations().getFirst();
        assertInstanceOf(Ast.SelectStmt.class, function.body().getFirst());
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "tracked-void-select.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context ctx = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            ctx.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
