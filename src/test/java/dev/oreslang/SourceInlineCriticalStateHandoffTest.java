package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compiled guest-source handoff regression: a sealed state callback cannot
 * escape into the source-task scheduler's implicit loop safepoint.
 */
@Timeout(30)
final class SourceInlineCriticalStateHandoffTest {
    @Test
    void inlineWriteLoanRetiresBeforeEachCarrierHandoffAndMailboxTurn() throws Exception {
        String guest = """
                define actor Worker as
                  state { count: int = 0; }

                  receive(ActorMail<String> mail): void with state.read, state.write, suspend {
                    if mail.value == "first"; do
                      for (let i = 0; i < 2048; i = i + 1) do
                        self.handle_state(critical write |s| -> {
                          s.count = s.count + 1;
                        });
                      done
                      self.send(self.handle_state(|s| -> s.count));
                      return;
                    fi
                    if mail.value == "second"; do
                      self.send(self.handle_state(|s| -> s.count) + 1);
                      self.end();
                    fi
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.ready;
                  worker.send("first");
                  worker.send("second");
                  val first = (await worker.outputs.next()).value.unwrap();
                  val second = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(first.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(second.value);
                  await worker.done;
                  stdio.stdout.write("|");
                  stdio.stdout.write(process.descriptor.source_loop_cooperates);
                  return;
                }
                """;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(bytes).build()) {
            context.eval(Source.newBuilder(
                    OresLanguage.ID, guest, "canonical-inline-state-handoff.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        String result = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(result.startsWith("2048|2049|"),
                "mailbox ordering, exactly-once state updates and response delivery: " + result);
        long cooperations = Long.parseLong(result.substring("2048|2049|".length()));
        assertTrue(cooperations > 0,
                "implicit loop cooperation must occur only after critical state loan retirement");
    }
}
