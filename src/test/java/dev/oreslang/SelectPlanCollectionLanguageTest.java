package dev.oreslang;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import dev.oreslang.compiler.OresCompiler;

final class SelectPlanCollectionLanguageTest {
    @Test
    void guestListAndRecordMapPlanInputsSelectThroughSharedArbiter() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Channel<int> first = Channel.new<int>(1);
                  writech first, 7;
                  val SelectPlan reusable = SelectPlan.new([SelectCase.read(first)]);
                  val Option<SelectResult> winner = select first from reusable;
                  stdio.stdout.write(winner.unwrap().value);

                  val Channel<int> second = Channel.new<int>(1);
                  writech second, 9;
                  val DynamicSelectPlan keyed =
                      DynamicSelectPlan.new(record { ready: SelectCase.read(second) });
                  val Future<Option<SelectResult>> pending = nb select first from keyed;
                  val Option<SelectResult> result = await pending;
                  stdio.stdout.write(":");
                  stdio.stdout.write(result.unwrap().value);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        var output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "plan-input.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        assertEquals("7:9", output.toString(StandardCharsets.UTF_8));
    }

    @Test
    void typedInvalidCasesFailClosedBeforeRuntimeAdmission() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc bad(): void {
                  val SelectPlan invalid = SelectPlan.new([123]);
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc bad(): void {
                  val Channel<int> c = Channel.new<int>(1);
                  val DynamicSelectPlan invalid =
                      DynamicSelectPlan.new(record { good: SelectCase.read(c), bad: 123 });
                  return;
                }
                """));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc bad(): Option<SelectResult> {
                  return select from [1, 2];
                }
                """));
    }
}
