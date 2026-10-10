package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class DeferPanicPrecedenceTest {

    @Test
    void allDefersRunInLifoOrderWhenAnEarlierDeferPanics() throws Exception {
        String program = """
                pub fnc main(): void {
                  defer stdio.stdout.write("A");
                  defer None.unwrap();
                  defer stdio.stdout.write("C");
                  None.unwrap();
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "defer-panic.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            PolyglotException panic = assertThrows(
                    PolyglotException.class, () -> context.eval(source));
            assertTrue(panic.getMessage().contains("Option::unwrap"));
        }
        assertEquals("CA", output.toString(StandardCharsets.UTF_8));
    }
}
