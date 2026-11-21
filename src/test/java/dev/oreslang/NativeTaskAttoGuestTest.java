package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import dev.oreslang.interop.MixedSourceUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Harness only: behavior lives in the .atto resource. */
@Timeout(20)
final class NativeTaskAttoGuestTest {
    @Test void attoTaskScopeDrainsSuspendingGuestCleanup() throws Exception {
        var resource = getClass().getResource("/atto/native-task-cleanup.atto");
        assertNotNull(resource);
        String text;
        try (var input = resource.openStream()) {
            text = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(MixedSourceUnit.PrimaryLanguage.ORES,
                MixedSourceUnit.parse("cleanup", "native-task-cleanup.atto", text).primaryLanguage());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, text, "native-task-cleanup.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("cleanup-enter\ncleanup-exit\nscope-retired\n",
                output.toString(StandardCharsets.UTF_8));
    }
}
