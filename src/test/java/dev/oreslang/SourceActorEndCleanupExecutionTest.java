package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SourceActorEndCleanupExecutionTest {

    @Test
    void selfEndCleanupIsFinalSourceActionAndMayEmitFinalOutput() throws Exception {
        String program = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    self.send("before|");

                    self.endWithCleanup(|| -> {
                      self.send("cleanup");
                    });

                    // self.endWithCleanup is non-blocking for the current turn. This runs
                    // before cleanup, but no later receive() can start.
                    self.send("after|");
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("go");

                  for await const output of worker.outputs {
                    stdio.stdout.write(output.value);
                  }

                  await worker.get_done();
                  return;
                }
                """;

        assertEquals(
                "before|after|cleanup",
                run(program, "source-actor-end-cleanup.ores"));
    }


    @Test
    void sourceTearDownRunsBetweenReceiveAndExplicitFinalCleanup() throws Exception {
        String program = """
                define actor Worker as
                  @Implements
                  tear_down(): void {
                    self.send("tear_down|");
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    self.send("receive|");
                    self.endWithCleanup(|| -> {
                      self.send("final");
                    });
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("go");
                  for await const output of worker.outputs {
                    stdio.stdout.write(output.value);
                  }
                  await worker.get_done();
                  return;
                }
                """;

        assertEquals(
                "receive|tear_down|final",
                run(program, "source-actor-tear-down.ores"));
    }

    @Test
    void sourceTearDownCannotBeInvokedLikeAnOrdinaryMethod() throws Exception {
        String program = """
                define actor Worker as
                  tear_down(): void { return; }
                  receive(ActorMail<String> mail): void {
                    self.tear_down();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("go");
                  await worker.get_done();
                  return;
                }
                """;
        assertThrows(Exception.class,
                () -> run(program, "source-actor-tear-down-not-callable.ores"));
    }

    @Test
    void untrustedActorAlsoFinishesCurrentTurnBeforeFinalCleanup() throws Exception {
        String program = """
                define untrusted actor Worker as
                  receive(ActorMail<String> mail): void {
                    self.send("turn|");
                    self.endWithCleanup(|| -> {
                      self.send("cleanup");
                    });
                    self.send("after|");
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("go");

                  for await const output of worker.outputs {
                    stdio.stdout.write(output.value);
                  }

                  await worker.get_done();
                  return;
                }
                """;

        assertEquals(
                "turn|after|cleanup",
                run(program, "source-untrusted-actor-end-cleanup.ores"));
    }

    @Test
    void finalCleanupMayCaptureActorSelfButNotAnOrdinaryBorrow() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<String> mail): void {
                            let int value = 7;
                            val view = rt borrow value;
                            self.endWithCleanup(|| -> {
                              stdio.println(view);
                            });
                            return;
                          }
                        end
                        """)));
        assertTrue(
                failure.getMessage().contains("closure cannot capture borrowed value 'view'"),
                failure.getMessage());
    }

    @Test
    void actorOutputFormattingTemporarilyLoansEnvelopeWithoutCertifyingUnknownPayload() {
        String positive = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    self.send("ready");
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = spawn Worker();
                  for await const output of worker.outputs {
                    stdio.stdout.write(output.value);
                  }
                  return;
                }
                """;
        assertDoesNotThrow(() ->
                dev.oreslang.types.OwnershipChecker.check(Parser.parse(positive)),
                "temporary formatting may read the VM-owned output envelope");

        String negative = positive.replace(
                "stdio.stdout.write(output.value);",
                "val unsafe_view = rt borrow output.value;");
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.OwnershipChecker.check(Parser.parse(negative)));
        assertTrue(failure.getMessage().contains(
                        "projected borrow requires a known owned field type for 'value'"),
                "unknown payloads must not gain persistent projected-borrow authority: "
                        + failure.getMessage());
    }

    private static String run(String program, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, name)
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
