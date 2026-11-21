package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Java/Graal guest already has on_start. Pin the public-language contract:
 * run it once after actor field initialization, before READY and before mail.
 */
final class SourceActorStartupHookTest {
    @Test
    void sourceOnStartRunsExactlyOnceBeforeReceiveAndReady() throws Exception {
        String program = """
                define actor RenderActor as
                  on_start(): void {
                    stdio.stdout.write("start:");
                    return;
                  }
                  receive(ActorMail<String> mail): void {
                    stdio.stdout.write("receive");
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                    val worker = spawn RenderActor();
                    await worker.ready;
                    worker.send("render");
                    await worker.done;
                    return;
                }
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            Source source = Source.newBuilder(OresLanguage.ID, program, "startup-order.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertDoesNotThrow(() -> context.eval(source));
        }
        assertEquals("start:receive", out.toString(StandardCharsets.UTF_8));
    }
}
