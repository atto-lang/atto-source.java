package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class NonBlockingSelectValueExpressionTest {
    @Test
    void winningReadCompletesFutureAfterActorLaneArmAndDeferredCleanup() throws Exception {
        String source = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val Channel<int> incoming = Channel.new<int>(1);
                    val Future<int> pending = nb select over
                      when readch incoming: int x -> {
                        defer stdio.stdout.write("cleanup:");
                        return x * 3;
                      }
                    end;
                    writech incoming, 7;
                    val int answer = await pending;
                    self.send(answer);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = await spawn Worker();
                  worker.send("run");
                  val out = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(out.value);
                  await worker.get_done();
                  return;
                }
                """;
        assertEquals("cleanup:21", run(source));
    }

    @Test
    void defaultArmIsDeferredUntilOwningActorLane() throws Exception {
        String source = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val Future<int> pending = nb select fair among
                      default -> {
                        stdio.stdout.write("arm:");
                        return 5;
                      }
                    end;
                    stdio.stdout.write("before:");
                    val int answer = await pending;
                    self.send(answer);
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = await spawn Worker();
                  worker.send("go");
                  val out = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(out.value);
                  await worker.get_done();
                  return;
                }
                """;
        assertEquals("before:arm:5", run(source));
    }

    @Test
    void actorStopRevokesPendingValueSelectWithoutHangingFinalization() throws Exception {
        String source = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val Channel<int> incoming = Channel.new<int>(1);
                    val Future<int> pending = nb select over
                      when readch incoming: int x -> {
                        self.send("unexpected");
                        return x;
                      }
                    end;
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = await spawn Worker();
                  worker.send("go");
                  await worker.get_done();
                  stdio.stdout.write("stopped");
                  return;
                }
                """;
        assertEquals("stopped", run(source));
    }

    @Test
    void rejectsNonActorValueSelectAndUnsafeNestedCallableReturn() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc wrong(): Future<int> {
                          return nb select over
                            default -> { return 1; }
                          end;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor fnc wrong(): Future<int> {
                          return nb select over
                            default -> {
                              do select over
                                default -> { return 99; }
                              end
                              return 1;
                            }
                          end;
                        }
                        """)));
    }

    @Test
    void rejectsMixedResultTypesAndImplicitFallthrough() {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor fnc bad(Channel<int> ch): Future<int> {
                          return nb select over
                            when readch ch: int x -> { return x; }
                            default -> { return "bad"; }
                          end;
                        }
                        """)));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        actor fnc bad(): Future<int> {
                          return nb select over
                            default -> { 1; }
                          end;
                        }
                        """)));
    }


    @Test
    void readyDefaultArmCannotRunAfterActorStopBeforeDeferredDispatch() throws Exception {
        String source = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val Future<int> pending = nb select over
                      default -> {
                        stdio.stdout.write("unexpected-arm:");
                        return 12;
                      }
                    end;
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = await spawn Worker();
                  worker.send("go");
                  await worker.get_done();
                  stdio.stdout.write("stopped");
                  return;
                }
                """;
        assertEquals("stopped", run(source));
    }

    private static String run(String text) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, text, "nb-select-values.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
