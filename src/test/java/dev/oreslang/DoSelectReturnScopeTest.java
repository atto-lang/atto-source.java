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
    void blockingDoSelectReturnExitsContainingFunction() throws Exception {
        String program = """
                pub fnc compute(): int {
                  val Channel<int> incoming = Channel.new<int>(1);
                  writech incoming, 7;
                  do select {
                    case readch incoming: val number {
                      stdio.stdout.write("selected:");
                      return number * 2;
                    }
                  }
                  stdio.stdout.write("unreachable");
                  return 99;
                }
                pub fnc main(): void {
                  stdio.stdout.write(compute());
                  return;
                }
                """;

        OresCompiler.AnalysisResult result =
                OresCompiler.parseAndTypeCheckWithDiagnostics(program);
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        assertEquals("selected:14", run(program));
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
    void bareReturnInBlockingDoSelectExitsCallable() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> incoming = Channel.new<int>(1);
                  writech incoming, 1;
                  do select {
                    case readch incoming: val number {
                      return;
                    }
                  }
                  stdio.stdout.write("unreachable");
                  return;
                }
                """;
        OresCompiler.AnalysisResult result =
                OresCompiler.parseAndTypeCheckWithDiagnostics(program);
        assertTrue(result.warnings().isEmpty(), result.warnings().toString());
        assertEquals("", run(program));
    }

    @Test
    void blockingDoSelectReturnMustMatchEnclosingFunctionType() {
        String source = """
                pub fnc compute(): int {
                  do select {
                    default: {
                      return "not an integer";
                    }
                  }
                  return 12;
                }
                """;
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
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
