package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** End-to-end EOF/Option static type parity for stdin.read_line(). */
final class StdinReadLineOptionTypeTest {
    @Test
    void readsOptionalStringWithoutImplicitUnwrap() {
        String source = """
                pub fnc main(): void {
                  val Option<string> request = stdio.stdin.read_line();
                  if request.is_none(); do
                    return;
                  fi
                  stdio.println(request.unwrap());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void rejectsAssigningOptionalReadDirectlyToString() {
        String source = """
                pub fnc main(): void {
                  val string unsafe_request = stdio.stdin.read_line();
                  stdio.println(unsafe_request);
                  return;
                }
                """;
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains("initializer for unsafe_request"),
                "no implicit Option<string> -> string conversion: " + failure);
    }

    @Test
    void readLinePreservesSomeContentAndNoneAtEof() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Option<string> first = stdio.stdin.read_line();
                  stdio.println(first.unwrap_or("unexpected-eof"));
                  val Option<string> second = stdio.stdin.read_line();
                  stdio.println(second.is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .in(new ByteArrayInputStream("hello\n".getBytes(StandardCharsets.UTF_8)))
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "stdin-option.atto")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .build());
        }
        assertEquals("hello\ntrue\n", output.toString(StandardCharsets.UTF_8)
                .replace("\\r\n", "\n"));
    }
}
