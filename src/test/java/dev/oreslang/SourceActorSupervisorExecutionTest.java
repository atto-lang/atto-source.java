package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class SourceActorSupervisorExecutionTest {

    @Test
    void sourceThrowRaiseAndPanicReachTypedHiddenControlHandlers() throws Exception {
        String program = """
                define actor ThrowWorker as
                  receive(ActorMail<String> mail): void {
                    throw "throw-child";
                  }
                end

                define actor RaiseWorker as
                  receive(ActorMail<String> mail): void {
                    raise "raise-child";
                  }
                end

                define actor PanicWorker as
                  receive(ActorMail<String> mail): void {
                    panic "panic-child";
                  }
                end

                define supervisor actor Manager as
                  on_throw(ChildFailure failure): void {
                    self.send(failure.kind);
                    failure.acknowledge();
                    return;
                  }

                  on_raise(ChildFailure failure): void {
                    self.send(failure.kind);
                    failure.acknowledge();
                    return;
                  }

                  on_panic(ChildFailure failure): void {
                    self.send(failure.kind);
                    failure.acknowledge();
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    if mail.value == "throw"; do
                      val child = spawn ThrowWorker();
                      child.send("go");
                      return;
                    fi
                    if mail.value == "raise"; do
                      val child = spawn RaiseWorker();
                      child.send("go");
                      return;
                    fi
                    if mail.value == "panic"; do
                      val child = spawn PanicWorker();
                      child.send("go");
                      return;
                    fi
                    if mail.value == "stop"; do
                      self.end();
                    fi
                    return;
                  }
                end

                pub async routine main(): void {
                  val manager = spawn Manager();
                  await manager.get_ready();

                  manager.send("throw");
                  val thrown = (await manager.outputs.next()).value.unwrap();
                  stdio.stdout.write(thrown.value);

                  manager.send("raise");
                  val raised = (await manager.outputs.next()).value.unwrap();
                  stdio.stdout.write("|");
                  stdio.stdout.write(raised.value);

                  manager.send("panic");
                  val panicked = (await manager.outputs.next()).value.unwrap();
                  stdio.stdout.write("|");
                  stdio.stdout.write(panicked.value);

                  manager.send("stop");
                  await manager.get_done();
                  return;
                }
                """;

        assertEquals(
                "throw|raise|panic",
                run(program, "source-supervisor-control.ores"));
    }

    @Test
    void sourcePanicHandlerCanSelectSameIdRestart() throws Exception {
        String program = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    panic "restart-child";
                  }
                end

                define supervisor actor Manager as
                  on_throw(ChildFailure failure): void {
                    failure.acknowledge();
                    return;
                  }
                  on_raise(ChildFailure failure): void {
                    failure.acknowledge();
                    return;
                  }
                  on_panic(ChildFailure failure): void {
                    self.send(failure.generation);
                    failure.restart();
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    if mail.value == "start" then
                      val child = spawn Worker();
                      child.send("fail");
                    fi
                    if mail.value == "stop" then
                      self.end();
                    fi
                    return;
                  }
                end

                pub async routine main(): void {
                  val manager = spawn Manager();
                  await manager.get_ready();
                  manager.send("start");
                  val generation = (await manager.outputs.next()).value.unwrap();
                  stdio.stdout.write(generation);
                  manager.send("stop");
                  await manager.get_done();
                  return;
                }
                """;

        String output = run(program, "source-supervisor-restart.ores");
        assertTrue(Long.parseLong(output) > 0L);
    }

    @Test
    void hiddenControlHandlersCannotBeExtractedAsOrdinaryMethods() {
        String program = """
                define supervisor actor Manager as
                  on_throw(ChildFailure failure): void {
                    failure.acknowledge();
                    return;
                  }
                  on_raise(ChildFailure failure): void {
                    failure.acknowledge();
                    return;
                  }
                  on_panic(ChildFailure failure): void {
                    failure.acknowledge();
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    const leaked = self.on_panic;
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val manager = spawn Manager();
                  await manager.get_ready();
                  manager.send("bad");
                  await manager.get_done();
                  return;
                }
                """;

        PolyglotException failure = assertThrows(
                PolyglotException.class,
                () -> run(program, "source-supervisor-hidden-method.ores"));
        assertTrue(
                failure.getMessage().contains("supervisor CONTROL handlers"),
                failure.getMessage());
    }

    @Test
    void typedTerminalEffectsRequireStringFailureValues() {
        String program = """
                pub fnc bad(): void {
                  panic 42;
                }

                pub fnc main(): void {
                  bad();
                  return;
                }
                """;

        PolyglotException failure = assertThrows(
                PolyglotException.class,
                () -> run(program, "source-panic-value-contract.ores"));
        assertTrue(
                failure.getMessage().contains("panic currently requires a string failure value"),
                failure.getMessage());
    }

    private static String run(String sourceText, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source =
                Source.newBuilder(OresLanguage.ID, sourceText, name)
                        .mimeType(OresLanguage.MIME_TYPE)
                        .build();

        try (Context context =
                Context.newBuilder(OresLanguage.ID)
                        .allowAllAccess(false)
                        .out(output)
                        .build()) {
            context.eval(source);
        }

        return output.toString(StandardCharsets.UTF_8);
    }
}
