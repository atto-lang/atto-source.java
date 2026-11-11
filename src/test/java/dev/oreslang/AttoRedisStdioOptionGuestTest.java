package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Compiler-backed stdin EOF and scalar Map tests for the Atto Redis bridge. */
final class AttoRedisStdioOptionGuestTest {
    @Test
    void typedLineReadAndEofExecuteInsideRealGuest() throws Exception {
        String source = """
                pub routine main(): void {
                  val Option<string> first = stdio.stdin.read_line();
                  if first.is_none() {
                    stdio.println("unexpected");
                    return;
                  } fi
                  stdio.println(first.unwrap());
                  val Option<string> eof = stdio.stdin.read_line();
                  stdio.println(eof.is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .in(new ByteArrayInputStream("42\n".getBytes(StandardCharsets.UTF_8)))
                .out(stdout).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source,
                    "atto-redis-stdio-option.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("42\ntrue\n", stdout.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void stringMapDistinguishesMissingAndEmptyInsideRealGuest() throws Exception {
        String source = """
                pub routine main(): void {
                  let Map<string,string> kv = ~map<string,string>();
                  stdio.println(kv.get_optional("a").is_none());
                  kv.set("a", "");
                  stdio.println(kv.get_optional("a").is_none());
                  stdio.println(kv.get_optional("a").unwrap() == "");
                  stdio.println(kv.delete("a"));
                  stdio.println(kv.get_optional("a").is_none());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(stdout).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source,
                    "atto-redis-string-map.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("true\nfalse\ntrue\ntrue\ntrue\n",
                stdout.toString(StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }
}
