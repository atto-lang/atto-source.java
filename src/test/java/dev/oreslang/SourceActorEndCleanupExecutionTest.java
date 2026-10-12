package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

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
    void sourceUntrustedCleanupCannotExecuteInTrustedHostContext() throws Exception {
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

        // The trusted bootstrap must never execute UNTRUSTED actor guest
        // state, including teardown callbacks. The matching positive actor
        // lifecycle scenario belongs in the real guest-isolate E2E suite.
        org.graalvm.polyglot.PolyglotException rejected = assertThrows(
                org.graalvm.polyglot.PolyglotException.class,
                () -> run(program, "source-untrusted-actor-end-cleanup.ores"));
        assertTrue(rejected.getMessage().contains("shared sandboxed Graal isolate"),
                rejected::getMessage);
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
