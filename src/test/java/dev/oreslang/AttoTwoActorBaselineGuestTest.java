package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Executes two independent compiler-lowered Atto guest actors. This is an
 * actor-start/mailbox/DONE baseline, NOT a fault-containment or CONTROL proof.
 */
final class AttoTwoActorBaselineGuestTest {
    @Test
    @Timeout(value = 25, unit = TimeUnit.SECONDS)
    void twoGuestActorsOwnIndependentMailboxesAndComplete() throws Exception {
        String code;
        try (InputStream source = getClass().getResourceAsStream(
                "/atto/actor-two-peer-baseline-v1.atto")) {
            assertNotNull(source, "the source-controlled .atto fixture is missing");
            byte[] bytes = source.readAllBytes();
            assertTrue(bytes.length > 100 && bytes.length < 8192);
            code = new String(bytes, StandardCharsets.UTF_8);
        }
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code),
                "two-actor baseline must pass real compiler admission");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .arguments(OresLanguage.ID,
                        new String[] {"--ores-capabilities=ACTOR_SPAWN,SHARED_MEMORY"})
                .out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, code,
                    "actor-two-peer-baseline-v1.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("TWO_ACTOR_BASELINE_OK|42|7\n",
                output.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }
}
