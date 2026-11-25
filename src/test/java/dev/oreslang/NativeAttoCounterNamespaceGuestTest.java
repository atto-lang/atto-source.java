package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real .atto guest actor lifecycle baseline for namespace header compatibility.
 * Inline actors and legacy self fields do NOT certify declared-actor mtd/state lowering.
 */
final class NativeAttoCounterNamespaceGuestTest {
    @Test
    void bothNamespaceHeadersExecuteNativeActorMailboxAndTermination() throws Exception {
        String canonical = Files.readString(Path.of("contracts/native-actor-counter-v1/counter.atto"));
        assertTrue(canonical.startsWith("use namespace actor_counter;"));
        for (String header : new String[]{"use namespace actor_counter;", "namespace actor_counter;"}) {
            String program = header + canonical.substring("use namespace actor_counter;".length());
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            try (Context context = Context.newBuilder(OresLanguage.ID)
                    .allowAllAccess(false)
                    .out(stdout).build()) {
                context.eval(Source.newBuilder(OresLanguage.ID, program,
                                "native-counter-namespace.atto")
                        .mimeType(OresLanguage.MIME_TYPE)
                        .buildLiteral());
            }
            assertEquals("1:0|2:1|done", stdout.toString(StandardCharsets.UTF_8),
                    "Both forms must run the same Atto actor, order two mailbox sends,"
                            + " emit consecutive output sequence numbers, and terminate");
        }
    }
}
