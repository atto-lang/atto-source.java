package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Native prelude, channel alias identity, and stream-direction conformance. */
@Timeout(30)
final class GlobalStdioStreamTest {
    private record Output(String stdout, String stderr) { }
    private static Output run(String code, String input) throws Exception {
        OresCompiler.parseAndTypeCheck(code);
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var src = Source.newBuilder(OresLanguage.ID, code, "global-stdio-stream.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .in(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)))
                .out(out).err(err).build()) {
            context.eval(src);
        }
        return new Output(out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void globalAliasesPointToTheSameContextOwnedDescriptorObjects() throws Exception {
        var result = run("""
                pub routine main(): void {
                  stdio.println(process.stdin === stdio.in);
                  stdio.println(process.stdin === stdio.stdin);
                  stdio.println(process.stdin === stdin.in);
                  stdio.println(process.stdout === stdio.out);
                  stdio.println(process.stderr === stdio.err);
                  stdio.println(stdio.err === stdio.stderr);
                  return;
                }
                """, "");
        assertEquals("true\ntrue\ntrue\ntrue\ntrue\ntrue\n", result.stdout());
    }

    @Test
    void globalObservableNeedsNoImportAndClosesOnEof() throws Exception {
        var result = run("""
                pub routine main(): void {
                  for const line of Observable.from_ch(process.stdin) {
                    stdio.println(line);
                  }
                  return;
                }
                """, "alpha\nbeta\n");
        assertEquals("alpha\nbeta\n", result.stdout());
    }

    @Test
    void stdinInStreamHasNativeReadableStreamTypeAndPreservesEof() throws Exception {
        var result = run("""
                pub routine main(): void {
                  val ReadableStream<String> stream = stdin.in.stream();
                  for const line of stream {
                    stdio.println(line);
                  }
                  return;
                }
                """, "line1\nline2\n");
        assertEquals("line1\nline2\n", result.stdout());
    }

    @Test
    void outputTeeIsAnExplicitComposedWriter() throws Exception {
        var result = run("""
                pub async routine main(): void {
                  val WritableTee tee = stdio.out.tee(stdio.err);
                  await tee.write("paired");
                  return;
                }
                """, "");
        assertEquals("paired", result.stdout());
        assertEquals("paired", result.stderr());
    }

    @Test
    void ambientDescriptorCannotBeClosedThroughAnyAlias() {
        for (String descriptor : List.of(
                "stdio.in", "stdin.in", "process.stdin",
                "stdio.out", "process.stdout", "stdio.err", "process.stderr")) {
            String program = """
                    pub routine main(): void {
                      val Channel<String> alias = %s;
                      alias.close();
                      return;
                    }
                    """.formatted(descriptor);
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program),
                    "guest descriptor close must be well-typed before the runtime denies it: " + descriptor);
            Exception failure = assertThrows(Exception.class, () -> run(program, ""));
            assertTrue(failure.toString().contains("process-owned"),
                    "runtime must reject the ambient close, not an unrelated failure: "
                            + descriptor + ": " + failure);
        }
    }

    @Test
    void ordinaryUserOwnedChannelCanStillClose() throws Exception {
        var result = run("""
                pub routine main(): void {
                  val Channel<String> mine = Channel.new<String>(1);
                  mine.close();
                  stdio.println(mine.is_closed());
                  return;
                }
                """, "");
        assertEquals("true\\n", result.stdout());
    }

    @Test
    void cannotStreamWriteOnlySinkOrTeeInputChannel() {
        assertThrows(Exception.class, () -> run("""
                pub routine main(): void {
                  val stream = stdio.err.stream();
                  return;
                }
                """, ""));
        assertThrows(Exception.class, () -> run("""
                pub routine main(): void {
                  val tee = stdin.in.tee(stdio.out);
                  return;
                }
                """, ""));
    }
}
