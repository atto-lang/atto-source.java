package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoSelectReturnScopeTest {
    @Test
    void blockingDoSelectReturnValueExitsContainingCallable() throws Exception {
        String program = """
                pub fnc answer(): int {
                  val Channel<int> incoming = Channel.new<int>(1);
                  writech incoming, 7;
                  do select {
                    case readch incoming: val number {
                      return number * 2;
                    }
                  }
                  return 0;
                }

                pub fnc main(): void {
                  stdio.stdout.write(answer());
                  return;
                }
                """;

        OresCompiler.AnalysisResult result =
                OresCompiler.parseAndTypeCheckWithDiagnostics(program);
        assertEquals(0, result.warnings().size(), result.warnings().toString());
        assertEquals("14", run(program));
    }

    @Test
    void blockingDoSelectBareReturnExitsContainingCallable() throws Exception {
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
        assertEquals(0, result.warnings().size(), result.warnings().toString());
        assertEquals("", run(program));
    }

    @Test
    void blockingDoSelectFallthroughContinuesAfterArm() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> incoming = Channel.new<int>(1);
                  writech incoming, 7;
                  do select {
                    case readch incoming: val number {
                      stdio.stdout.write("selected:");
                      stdio.stdout.write(number);
                    }
                  }
                  stdio.stdout.write(":continued");
                  return;
                }
                """;

        assertEquals("selected:7:continued", run(program));
    }

    @Test
    void blockingDoSelectReturnMustMatchContainingCallableType() {
        String program = """
                fnc invalid(): void {
                  val Channel<int> incoming = Channel.new<int>(1);
                  do select {
                    case readch incoming: val number {
                      return number * 3;
                    }
                  }
                  return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void legacyDetachedDoNbSelectStillWarnsAboutDiscardedReturn() {
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
