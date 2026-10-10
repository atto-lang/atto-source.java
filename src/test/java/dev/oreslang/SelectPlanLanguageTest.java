package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

final class SelectPlanLanguageTest {
    @Test
    void immutableAndMutablePlansRemainReusableAcrossSelectIterations() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> a = Channel.new<int>(3);
                  val Channel<int> b = Channel.new<int>(3);
                  val SelectPlan fixed = SelectPlan.new([SelectCase.read(a)]);
                  writech a, 11;
                  stdio.stdout.write((select first from fixed).unwrap().value);
                  writech a, 12;
                  stdio.stdout.write(":");
                  stdio.stdout.write((select first from fixed).unwrap().value);

                  val DynamicSelectPlan dynamic = DynamicSelectPlan.new();
                  dynamic.put("a", SelectCase.read(a));
                  dynamic.put("b", SelectCase.read(b));
                  writech b, 21;
                  stdio.stdout.write(":");
                  stdio.stdout.write((select first from dynamic).unwrap().value);
                  dynamic.remove("a");
                  writech b, 22;
                  stdio.stdout.write(":");
                  stdio.stdout.write((select first from dynamic).unwrap().value);
                  dynamic.put("b", SelectCase.read(a));
                  writech a, 23;
                  stdio.stdout.write(":");
                  stdio.stdout.write((select first from dynamic).unwrap().value);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("11:12:21:22:23", run(program));
    }

    @Test
    void refusesIncorrectDynamicMutationArgumentsAtCompileTime() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                pub fnc main(): void {
                  val DynamicSelectPlan plan = DynamicSelectPlan.new();
                  plan.put(123, 456);
                }
                """));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "select-plan.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
