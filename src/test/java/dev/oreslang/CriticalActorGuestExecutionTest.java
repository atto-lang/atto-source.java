package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A real native .ores guest fixture, not a Java substitution for critical
 * source lowering. It is only a verified pass after Maven executes the test
 * at the exact checked compiler source tree.
 */
@Timeout(30)
final class CriticalActorGuestExecutionTest {
    @Test void actorCriticalGuestReturnsExpectedValueAndReleasesBeforeStop()
            throws Exception {
        String source = Files.readString(Path.of("examples/critical-actor-checked-v1.ores"));
        OresCompiler.parseAndTypeCheck(source);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "critical-actor-checked-v1.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        assertEquals("42", output.toString(StandardCharsets.UTF_8));
    }

    @Test void unverifiedFreeFunctionCriticalSourceDoesNotExecute() {
        String source = """
                pub suspends fnc unsafe(): int {
                  critical { return 42; }
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains(
                "requires a suspending actor method with actor-owned gate"),
                failure::toString);
    }
}
