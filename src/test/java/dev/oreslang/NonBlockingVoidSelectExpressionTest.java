package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class NonBlockingVoidSelectExpressionTest {
    @Test
    void trackedFutureVoidSettlesAfterReadArmAndDeferredCleanup() throws Exception {
        String source = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val Channel<int> inbox = Channel.new<int>(1);
                    const Future<void> selected = nb select first {
                      when readch inbox: int message -> {
                        stdio.stdout.write("processed:");
                        stdio.stdout.write(message);
                        defer stdio.stdout.write(":cleanup");
                      }
                    };
                    writech inbox, 9;
                    stdio.stdout.write("registered:");
                    await selected;
                    stdio.stdout.write(":after");
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = await spawn Worker();
                  worker.send("start");
                  await worker.get_done();
                  return;
                }
                """;
        assertEquals("registered:processed:9:cleanup:after", run(source));
    }

    @Test
    void immediatelyAwaitedVoidSelectRunsDeferredArmExactlyOnce() throws Exception {
        String source = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    stdio.stdout.write("before:");
                    await nb select first {
                      default: -> {
                        stdio.stdout.write("arm:");
                        defer stdio.stdout.write("cleanup:");
                      }
                    };
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
                """;
        assertEquals("before:arm:cleanup:after", run(source));
    }

    @Test
    void defaultArmIsDeferredEvenWithoutResultPayload() throws Exception {
        String source = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    const Future<void> pending = nb select first {
                      default: -> { stdio.stdout.write("arm:"); }
                    };
                    stdio.stdout.write("before:");
                    await pending;
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
                """;
        assertEquals("before:arm:done", run(source));
    }

    @Test
    void mixedVoidAndValueArmsCannotImplicitlyEraseTheResult() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        actor fnc invalid(Channel<int> inbox): void {
                          const Future<void> pending = nb select first {
                            when readch inbox: int x -> { stdio.stdout.write(x); }
                            default: -> { return 42; }
                          };
                          return;
                        }
                        """));
    }

    @Test
    void bareValueCannotBeBoundFromVoidAwaitOrMixedWithInt() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        actor fnc invalid(): void {
                          const int answer = await nb select first {
                            default: -> { stdio.stdout.write("ready"); }
                          };
                          return;
                        }
                        """));
    }

    @Test
    void awaitingVoidSelectNeverCreatesAStorableVoidValue() {
        for (String declaration : new String[] {
                "const void result", "const result"
        }) {
            String source = """
                    actor fnc invalid(): void {
                      %s = await nb select first {
                        default: -> { stdio.stdout.write("side-effect"); }
                      };
                      return;
                    }
                    """.formatted(declaration);
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), declaration);
        }
    }

    @Test
    void voidSelectStillRequiresActorExecutionDomain() {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        fnc invalid(): void {
                          const Future<void> pending = nb select first {
                            default: -> { stdio.stdout.write("side-effect"); }
                          };
                          return;
                        }
                        """));
    }

    private static String run(String code) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code, "nb-select-void.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }
}
