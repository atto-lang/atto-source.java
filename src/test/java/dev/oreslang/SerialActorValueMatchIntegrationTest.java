package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact combined #656/#661 guest regression. A genuine source serial actor
 * consumes a typed lexical ARM_RESULT and runs cleanup before publishing.
 *
 * A pending await inside the actor's typed match is a separate gate: the
 * compiler/runtime must not silently downgrade serial actor semantics.
 */
@Timeout(20)
final class SerialActorValueMatchIntegrationTest {
    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program,
                "serial-actor-typed-match-integration.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8).trim().replace("\r", "");
    }

    @Test
    void serialReceiveRunsValueMatchDeferBeforePublishingActorResult() throws Exception {
        assertEquals("arm-cleanup\n42", run("""
                define serial actor Worker as
                  receive(ActorMail<String> mail): void {
                    val result: int = match<ret=int> 7 over {
                      on 7 {
                        defer stdio.println("arm-cleanup");
                        return 42;
                      }
                      default { return 0; }
                    };
                    stdio.println(result);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("work");
                  await worker.get_done();
                  return;
                }
                """));
    }

    @Test
    void serialReceiveMaySuspendInsideTypedMatchWithoutReplayingCleanup() throws Exception {
        assertEquals("producer\narm-cleanup\n42", run("""
                define serial actor Worker as
                  pub static async fnc produce(): int {
                    stdio.println("producer");
                    rt cooperate;
                    return 7;
                  }

                  suspends receive(ActorMail<String> mail): void {
                    val result: int = match<ret=int> await Worker.produce() over {
                      on 7 {
                        defer stdio.println("arm-cleanup");
                        return 42;
                      }
                      default { return 0; }
                    };
                    stdio.println(result);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("work");
                  await worker.get_done();
                  return;
                }
                """));
    }
}
