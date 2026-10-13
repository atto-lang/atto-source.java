package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoSelectReturnScopeTest {
    @Test
    void blockingDoSelectReturnExitsEnclosingCallableWithoutDiscardWarning() throws Exception {
        String program = """
                fnc calculate(): int {
                  val Channel<int> incoming = Channel.new<int>(1);
                  writech incoming, 7;
                  do select {
                    case readch incoming: val number {
                      stdio.stdout.write("selected:");
                      stdio.stdout.write(number);
                      stdio.stdout.write(":");
                      return number * 2;
                    }
                  }
                  return -1;
                }
                pub routine main(): void {
                  stdio.stdout.write(calculate());
                  return;
                }
                """;

        OresCompiler.AnalysisResult result =
                OresCompiler.parseAndTypeCheckWithDiagnostics(program);
        assertEquals(0, result.warnings().size(), result.warnings().toString());
        assertEquals("selected:7:14", run(program));
    }

    @Test
    void nonblockingDoSelectReturnValueIsWarnedInsteadOfBecomingCallableReturn() {
        String program = """
                actor fnc consume(): void {
                  val Channel<int> incoming = Channel.new<int>(1);
                  do nb select {
                    case readch incoming: val number {
                      return number * 3;
                    }
                  }
                  return;
                }
                """;
        OresCompiler.AnalysisResult result =
                OresCompiler.parseAndTypeCheckWithDiagnostics(program);
        assertEquals(1, result.warnings().size(), result.warnings().toString());
        assertTrue(result.warnings().getFirst().contains("W-SELECT-RETURN"));
    }

    @Test
    void bareReturnInBlockingDoSelectExitsEnclosingCallable() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> incoming = Channel.new<int>(1);
                  writech incoming, 1;
                  do select {
                    case readch incoming: val number {
                      return;
                    }
                  }
                  stdio.stdout.write("not-executed");
                  return;
                }
                """;
        OresCompiler.AnalysisResult result =
                OresCompiler.parseAndTypeCheckWithDiagnostics(program);
        assertEquals(0, result.warnings().size(), result.warnings().toString());
        assertEquals("", run(program));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "do-select-return.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
