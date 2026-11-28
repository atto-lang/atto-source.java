package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Executable guest-to-host bridge for typed immediate and awaitable ActorRef sends. */
@Timeout(10)
final class SourceActorMailboxSendModesTest {
    @Test
    void sourceTrySendAndAwaitableSendUseActorMailboxChannel() throws Exception {
        String program = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    if mail.value == "stop"; do
                      self.end();
                    fi
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  val bool admitted = worker.try_send("start");
                  val Future<void> pending = worker.send_async("stop");
                  await pending;
                  await worker.get_done();
                  stdio.stdout.write(admitted);
                  return;
                }
                """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "actor-mailbox-send-modes.ores")
                    .mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        }
        assertEquals("true", output.toString(StandardCharsets.UTF_8));
    }
}
