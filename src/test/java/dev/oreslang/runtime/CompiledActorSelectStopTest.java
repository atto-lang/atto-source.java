package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.OresLanguage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

/**
 * Execute real compiled guest actor source while one receive blocks inside a
 * static channel select. Physical STOP must finish and must not retain the
 * underlying source-channel registration in the process-wide coordinator.
 */
final class CompiledActorSelectStopTest {
    @Test
    void stoppedSourceActorDoesNotRetainBlockedReadchSelect() throws Exception {
        int before = ChannelRuntime.activeSelectCountForTest();
        String source = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val Channel<int> neverReady = Channel.new<int>(0);
                    self.send("waiting");
                    select {
                      when readch neverReady: let event {
                        self.send("unexpected");
                      }
                    }
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("start");
                  val started = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(started.value);
                  worker.stop();
                  await worker.get_done();
                  stdio.stdout.write("|stopped");
                  return;
                }
                """;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source compiled = Source.newBuilder(
                OresLanguage.ID, source, "compiled-actor-select-stop.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(out)
                .build()) {
            context.eval(compiled);
        }
        assertEquals("waiting|stopped", out.toString(StandardCharsets.UTF_8));
        assertEquals(before, ChannelRuntime.activeSelectCountForTest(),
                "physical actor stop must detach every owned channel select arm");
    }
}
