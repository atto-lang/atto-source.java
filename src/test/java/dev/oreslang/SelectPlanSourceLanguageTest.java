package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Executable .ores frontend + runtime probes, not Java-only plan unit tests. */
final class SelectPlanSourceLanguageTest {
    @Test
    void immutableListAndMapPlansReuseTheirFairCursor() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Channel<int> left = Channel.new<int>(2);
                  val Channel<int> right = Channel.new<int>(2);
                  writech left, 10;
                  writech left, 11;
                  writech right, 20;
                  writech right, 21;

                  val SelectPlan list_plan = SelectPlan.from_list([
                    SelectCase.read(left), SelectCase.read(right)
                  ]);
                  val Option<SelectResult> a = try select fair from list_plan;
                  val Option<SelectResult> b = try select fair from list_plan;
                  stdio.stdout.write(a.unwrap().index);
                  stdio.stdout.write(":");
                  stdio.stdout.write(a.unwrap().value);
                  stdio.stdout.write(":");
                  stdio.stdout.write(b.unwrap().index);
                  stdio.stdout.write(":");
                  stdio.stdout.write(b.unwrap().value);

                  val SelectPlan map_plan = SelectPlan.from_map(record{
                    left: SelectCase.read(left), right: SelectCase.read(right)
                  });
                  val Option<SelectResult> c = try select fair from map_plan;
                  val Option<SelectResult> d = try select fair from map_plan;
                  stdio.stdout.write(":");
                  stdio.stdout.write(c.unwrap().index);
                  stdio.stdout.write(":");
                  stdio.stdout.write(c.unwrap().value);
                  stdio.stdout.write(":");
                  stdio.stdout.write(d.unwrap().index);
                  stdio.stdout.write(":");
                  stdio.stdout.write(d.unwrap().value);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("0:10:1:20:0:11:1:21", run(source));
    }

    @Test
    void mutableListPlanNormalizesIntKeysAcrossRemoveAndPut() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Channel<int> first = Channel.new<int>(1);
                  val Channel<int> second = Channel.new<int>(1);
                  writech first, 11;
                  writech second, 22;
                  val DynamicSelectPlan<int> plan = DynamicSelectPlan.from_list([
                    SelectCase.read(first), SelectCase.read(second)
                  ]);
                  val bool removed = plan.remove(0);
                  plan.put(0, SelectCase.read(first));
                  val Option<SelectResult> winner = try select first from plan;
                  stdio.stdout.write(removed);
                  stdio.stdout.write(":");
                  stdio.stdout.write(winner.unwrap().index);
                  stdio.stdout.write(":");
                  stdio.stdout.write(winner.unwrap().value);
                  stdio.stdout.write(":");
                  stdio.stdout.write(plan.size());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true:0:22:2", run(source));
    }

    @Test
    void mutableMapPlanKeepsKeysAndSupportsAsyncSelect() throws Exception {
        String source = """
                pub fnc main(): void {
                  val Channel<int> data = Channel.new<int>(1);
                  val Channel<int> control = Channel.new<int>(1);
                  writech data, 33;
                  val DynamicSelectPlan<string> plan = DynamicSelectPlan.from_map(record{
                    control: SelectCase.read(control), data: SelectCase.read(data)
                  });
                  val bool removed = plan.remove("control");
                  plan.put("control", SelectCase.read(control));
                  val Future<Option<SelectResult>> pending = nb select first from plan;
                  val Option<SelectResult> winner = await pending;
                  stdio.stdout.write(removed);
                  stdio.stdout.write(":");
                  stdio.stdout.write(winner.unwrap().index);
                  stdio.stdout.write(":");
                  stdio.stdout.write(winner.unwrap().value);
                  stdio.stdout.write(":");
                  stdio.stdout.write(plan.version());
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        assertEquals("true:0:33:4", run(source));
    }

    @Test
    void factoriesRejectWrongCollectionKindsAndCaseTypesAtCompileTime() {
        String[] rejected = {
                "SelectPlan.from_list([1])",
                "SelectPlan.from_map(record{bad: 123})",
                "SelectPlan.from_list(record{a: SelectCase.default()})",
                "SelectPlan.from_map([SelectCase.default()])",
                "DynamicSelectPlan.from_list([false])",
                "DynamicSelectPlan.from_map(record{bad: 123})",
                "DynamicSelectPlan.from_list([SelectCase.default()], 7)"
        };
        for (String expression : rejected) {
            String source = "pub fnc main(): void { val item = " + expression
                    + "; return; }";
            assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source), expression);
        }
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "select-plan-source.ores")
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
