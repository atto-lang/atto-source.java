package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Executable integration proof: state loans end before source-loop handoff. */
@Timeout(25)
final class SourceSealedStateReductionIntegrationTest {
    @Test void directInlineSealedStateCallRunsInSuspendableReceiveWithoutLoanEscape()
            throws Exception {
        String guest = """
            define actor Worker as
              state { count: int = 0; }

              receive(ActorMail<String> mail): void {
                if mail.value == "first"; do
                  for (let i = 0; i < 2048; i = i + 1) do
                    self.handle_state(critical |s| -> {
                      s.count = s.count + 1;
                    });
                  done
                  self.send(self.handle_state(critical |s| -> s.count));
                  return;
                fi
                if mail.value == "second"; do
                  self.send(self.handle_state(critical |s| -> s.count) + 1);
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
                    OresLanguage.ID, guest, "inline-sealed-state-preemption.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.startsWith("2048|2049|"), output);
        long cooperates = Long.parseLong(output.substring("2048|2049|".length()));
        assertTrue(cooperates > 0,
                "direct critical callbacks must retire before source-task carrier handoff");
    }

    @Test void sealedActorStateSurvivesPreemptionWithoutMailOvertakingOrReplay() throws Exception {
        String guest = """
            define actor Worker as
              state { count: int = 0; }

              pub increment(): int using state.read, state.write {
                return self.handle_state(critical |s| -> {
                  s.count = s.count + 1;
                  return s.count;
                });
              }

              pub snapshot(): int using state.read {
                return self.handle_state(critical |s| -> s.count);
              }

              receive(ActorMail<String> mail): void {
                if mail.value == "first"; do
                  for (let i = 0; i < 2048; i = i + 1) do
                    self.increment();
                  done
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
                    OresLanguage.ID, guest, "sealed-state-preemption.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        String output = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(output.startsWith("2048|2049|"), output);
        long cooperates = Long.parseLong(output.substring("2048|2049|".length()));
        assertTrue(cooperates > 0, "long actor receive must hand off after exiting each state loan");
    }
}
