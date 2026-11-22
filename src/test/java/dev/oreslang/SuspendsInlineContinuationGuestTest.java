package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Actual .atto guest regression, not host-only scheduler or Native Image evidence. */
@Timeout(20)
final class SuspendsInlineContinuationGuestTest {
    @Test
    void nestedSuspendsCallResumesValueAndRunsCalleeDeferBeforeCaller() throws Exception {
        Path fixture = Path.of("tests", "fixtures", "suspends-await-v1",
                "inline-nested-defer.atto");
        assertTrue(Files.isRegularFile(fixture));
        String code = Files.readString(fixture, StandardCharsets.UTF_8);
        assertFalse(code.contains("async fnc"));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, code,
                        "inline-nested-defer.atto")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("defer:42|root", output.toString(StandardCharsets.UTF_8));
    }
}
