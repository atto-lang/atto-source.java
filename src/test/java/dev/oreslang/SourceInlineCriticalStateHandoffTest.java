package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guest-source handoff regression: inline sealed state callbacks in a bounded
 * actor method cannot escape into implicit source-task loop safepoints.
 */
@Timeout(30)
final class SourceInlineCriticalStateHandoffTest {
    @Test
    void ordinaryClassMethodWithCpuLoopStillReturnsScalarSynchronously() throws Exception {
        String guest = """
                define class Calculator as
                  pub count(): int {
                    let total = 0;
                    for (let i = 0; i < 1024; i = i + 1) do
                      total = total + 1;
                    done
                    return total;
                  }
                end

                define actor Worker as
                  state { count: int = 0; }
                  count(): void with state.read, state.write, suspend {
                    for (let i = 0; i < 1024; i = i + 1) do
                      self.handle_state(critical write |s| -> {
                        s.count = s.count + 1;
                      });
                    done
                    return;
                  }
                  receive(ActorMail<String> mail): void {
                    self.count();
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val calculator = new Calculator();
                  stdio.stdout.write(calculator.count());
                  return;
                }
                """;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(bytes).build()) {
            context.eval(Source.newBuilder(
                    OresLanguage.ID, guest, "plain-class-loop-stays-sync.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        org.junit.jupiter.api.Assertions.assertEquals(
                "1024", bytes.toString(StandardCharsets.UTF_8),
                "actor method loop admission must not change ordinary class return values");
    }

    @Test
    void inlineWriteLoanRetiresBeforeEachCarrierHandoffAndMailboxTurn() throws Exception {
        String guest = """
                define actor Worker as
                  state { count: int = 0; }

                  tick(): void with state.read, state.write, suspend {
                    for (let i = 0; i < 2048; i = i + 1) do
                      self.handle_state(critical write |s| -> {
                        s.count = s.count + 1;
                      });
                    done
                    return;
                  }

                  snapshot(): int with state.read {
                    return self.handle_state(|s| -> s.count);
                  }

                  receive(ActorMail<String> mail): void {
                    if mail.value == "first"; do
                      self.tick();
                      self.send(self.snapshot());
                      return;
                    fi
                    if mail.value == "second"; do
                      self.send(self.snapshot() + 1);
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
