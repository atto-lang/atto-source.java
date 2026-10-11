package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class SourceSupervisorExecutionTest {
    @Test
    void guestPanicHookRunsOnOwningParentActorAndSignalsCompletion() throws Exception {
        String program = """
                // Spawn the same source actor class in a new generation.
                // This preserves the compiler's sibling-code import boundary.
                define actor Parent as
                  on_throw(String summary): void { return; }
                  on_raise(String summary): void { return; }
                  on_panic(String summary): void {
                    self.send("guest-handled");
                    self.end();
                    return;
                  }

                  receive(ActorMail<String> mail): void {
                    if mail.value == "start"; do
                      val child = spawn Parent();
                      child.send("fail");
                      return;
                    fi
                    val Option<int> empty = None;
                    val int impossible = empty.unwrap();
                    return;
                  }
                end

                pub async routine main(): void {
                    val parent = spawn Parent();
                    await parent.ready;
                    parent.send("start");
                    val output = (await parent.outputs.next()).value.unwrap();
                    stdio.stdout.write(output.value);
                    await parent.done;
                    return;
                }
                """;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            Source source = Source.newBuilder(OresLanguage.ID, program, "source-supervisor.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build();
            assertDoesNotThrow(() -> context.eval(source));
        }
        assertEquals("guest-handled", out.toString(StandardCharsets.UTF_8));
    }
}
