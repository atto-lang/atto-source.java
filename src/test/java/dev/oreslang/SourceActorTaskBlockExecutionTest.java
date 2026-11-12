package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Guest-language actor Task blocks must use actor-owned continuation scheduling. */
@Timeout(20)
final class SourceActorTaskBlockExecutionTest {
    @Test
    void trustedActorRunsCheckedTaskAndAwaitsItInsideItsMailboxTurn() throws Exception {
        assertEquals("42", run("""
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val int base = 40;
                    let Future<int> child = task {
                      rt cooperate;
                      return base + 2;
                    };
                    val int answer = await child;
                    self.send(answer);
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("run");
                  val result = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(result.value);
                  await worker.get_done();
                  return;
                }
                """));
    }

    @Test
    void actorStateCannotEscapeThroughTaskCapture() {
        PolyglotException error = assertThrows(PolyglotException.class, () -> run("""
                define actor Worker as
                  let int count;
                  on_start(): void { self.count = 7; return; }
                  receive(ActorMail<String> mail): void {
                    let Future<int> child = task {
                      return self.count;
                    };
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("run");
                  await worker.get_done();
                  return;
                }
                """));
        assertNotNull(error.getMessage());
    }

    @Test
    void untrustedActorCannotBypassItsExistingTaskQuotaWithTrackedTask() {
        PolyglotException error = assertThrows(PolyglotException.class, () -> run("""
                define untrusted actor Worker as
                  receive(ActorMail<String> mail): void {
                    let Future<int> child = task { return 1; };
                    self.end();
                    return;
                  }
                end
                pub routine main(): void { return; }
                """));
        assertTrue(error.getMessage().contains("quota-aware tracked task admission"),
                error::getMessage);
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, sourceText, "actor-task-block.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
