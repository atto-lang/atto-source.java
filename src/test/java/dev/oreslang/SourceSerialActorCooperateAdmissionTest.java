package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real source guest regression: a serial receive keeps one logical lifetime
 * across rt cooperate and resumes on the actor continuation mailbox.
 * This is not a live HTTP transport test.
 */
final class SourceSerialActorCooperateAdmissionTest {
    @Test void serialMailboxReceiveRetainsAdmissionAcrossCooperate() throws Exception {
        String source = """
            define serial actor Counter as
                state { count: int = 0; }

                pub increment(): int using state.read, state.write {
                    return self.handle_state(|s| -> {
                        s.count = s.count + 1;
                        return s.count;
                    });
                }

                receive(ActorMail<String> mail): void {
                    rt cooperate;
                    val next = self.increment();
                    stdio.println(next);
                    if (next == 3) {
                        self.end();
                    } fi
                    return;
                }
            end

            pub async routine main(): void {
                val worker = spawn Counter();
                await worker.ready;
                worker.send("first");
                worker.send("second");
                worker.send("third");
                await worker.done;
                return;
            }
            """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            Source compiled = Source.newBuilder(
                    OresLanguage.ID, source, "serial-cooperate-lifetime.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertDoesNotThrow(() -> context.eval(compiled));
        }
        assertEquals("1\n2\n3\n", output.toString(StandardCharsets.UTF_8),
                "the next receive cannot overtake an actor's suspended receive");
    }
}
