package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class UnwindPrioritySemanticsTest {
    private static Source source(String body) throws Exception {
        return Source.newBuilder(OresLanguage.ID, body, "unwind-priority.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
    }

    @Test
    void everyDeferredCleanupRunsInLifoEvenWhenMiddleCleanupPanics() throws Exception {
        String program = """
                pub fnc main(): void {
                    val Option<int> empty = None;
                    defer stdio.stdout.write("A");
                    defer empty.unwrap();
                    defer stdio.stdout.write("C");
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            PolyglotException error = assertThrows(PolyglotException.class,
                    () -> context.eval(source(program)));
            assertTrue(error.getMessage().contains("Option::unwrap"));
        }
        assertEquals("CA", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void finallyRunsAfterPanicWithoutTreatingPanicAsOrdinaryThrow() throws Exception {
        String program = """
                pub fnc main(): void {
                    val Option<int> empty = None;
                    try {
                        val int failed = empty.unwrap();
                    } catch (error) {
                        stdio.stdout.write("caught");
                    } finally {
                        stdio.stdout.write("final");
                    }
                    return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            PolyglotException error = assertThrows(PolyglotException.class,
                    () -> context.eval(source(program)));
            assertTrue(error.getMessage().contains("Option::unwrap"));
        }
        assertEquals("final", output.toString(StandardCharsets.UTF_8));
    }
}
