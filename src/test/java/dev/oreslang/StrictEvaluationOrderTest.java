package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

final class StrictEvaluationOrderTest {
    @Test void ordinaryCallArgumentsAreEagerLeftToRight() throws Exception {
        String program = """
                fnc observe(int value): int {
                  stdio.stdout.write(value);
                  return value;
                }
                fnc sum(int a, int b): int {
                  return a + b;
                }
                pub fnc main(): void {
                  stdio.stdout.write(sum(observe(1), observe(2)));
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "eager.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("123", output.toString(StandardCharsets.UTF_8));
    }

    @Test void controlFlowExclusionsAreSelective() throws Exception {
        String program = """
                fnc mark(): bool {
                  stdio.stdout.write("BAD");
                  return true;
                }
                fnc observe(int x): int {
                  stdio.stdout.write(x);
                  return x;
                }
                pub fnc main(): void {
                  val bool a = false && mark();
                  val bool b = true || mark();
                  val int selected = true ? observe(4) : observe(5);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "selective.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        assertEquals("4", output.toString(StandardCharsets.UTF_8));
    }
}
