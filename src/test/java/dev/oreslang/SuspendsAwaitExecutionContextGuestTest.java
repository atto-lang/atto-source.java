package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real .atto guest compiler and execution gates for the suspends/await model.
 * Does not claim NativeAOT or actor-scalar AWAIT lowering.
 */
@Timeout(20)
final class SuspendsAwaitExecutionContextGuestTest {
    private static final Path FIXTURES =
            Path.of("tests", "fixtures", "suspends-await-v1");

    private static String fixture(String name) throws Exception {
        Path file = FIXTURES.resolve(name);
        assertTrue(Files.isRegularFile(file), "missing .atto fixture: " + file);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void nestedSuspendsCallsAwaitTaskAndRetainTaskContext() throws Exception {
        String code = fixture("nested-task-context.atto");
        assertFalse(code.contains("async fnc"), "do not use legacy async in canonical fixtures");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(code),
                "the compiler must admit nested suspending function calls");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, code, "nested-task-context.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("42|req-7", output.toString(StandardCharsets.UTF_8),
                "nested suspended frames and task-local context must be restored");
    }

    @Test
    void plainFunctionCannotEraseSuspensionEffect() throws Exception {
        String code = fixture("reject-plain-caller.atto");
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(code));
        String message = String.valueOf(failure.getMessage()).toLowerCase(Locale.ROOT);
        assertTrue(message.contains("susp") || message.contains("effect"),
                () -> "expected suspension/effect diagnostic, got: " + message);
    }
}
