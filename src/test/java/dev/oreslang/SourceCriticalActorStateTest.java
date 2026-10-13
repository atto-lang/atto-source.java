package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Complete guest-source smoke for the synchronous scalar actor-state path. */
final class SourceCriticalActorStateTest {
    @Test void sourceCounterPersistsOnOneActorAndRetiresAfterDone() throws Exception {
        String source = """
            define actor Counter as
                state {
                    count: int = 0;
                }

                pub increment(): int with state.read, state.write {
                    return self.handle_state(critical write |s| -> {
                        s.count = s.count + 1;
                        return s.count;
                    });
                }

                receive(ActorMail<String> mail): void {
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
                    OresLanguage.ID, source, "critical-actor-state.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertDoesNotThrow(() -> context.eval(compiled));
        }
        assertEquals("1\n2\n3\n", output.toString(StandardCharsets.UTF_8));
    }
    @Test void compiledActorCanReturnDetachedIntegerListSnapshot() throws Exception {
        String source = """
            define actor Snapshot as
                state { items: List[int] = [1, 2]; }

                receive(ActorMail<String> mail): void {
                    val detached = self.handle_state(|s| -> { return s.items; });
                    detached[0] = 99;
                    val current = self.handle_state(|s| -> { return s.items; });
                    stdio.println(detached[0]);
                    stdio.println(current[0]);
                    self.end();
                    return;
                }
            end

            pub async routine main(): void {
                val worker = spawn Snapshot();
                await worker.ready;
                worker.send("read");
                await worker.done;
                return;
            }
            """;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            Source compiled = Source.newBuilder(
                    OresLanguage.ID, source, "actor-int-list-snapshot.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertDoesNotThrow(() -> context.eval(compiled));
        }
        assertEquals("99\\n1\\n", output.toString(StandardCharsets.UTF_8));
    }

}
