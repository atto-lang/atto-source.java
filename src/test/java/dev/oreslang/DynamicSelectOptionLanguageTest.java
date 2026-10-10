package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import dev.oreslang.compiler.OresCompiler;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class DynamicSelectOptionLanguageTest {

    @Test
    void blockingAndNonblockingDynamicSelectUseOptionResults() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> first = Channel.new<int>(1);
                  writech first, 7;
                  val SelectSet blocking_cases =
                      SelectSet.new([SelectCase.read(first)]);
                  val Option<SelectResult> blocking =
                      select first from blocking_cases;

                  stdio.stdout.write(blocking.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(blocking.unwrap().value);

                  val Channel<int> second = Channel.new<int>(1);
                  writech second, 9;
                  val SelectSet async_cases =
                      SelectSet.new([SelectCase.read(second)]);
                  val Future<Option<SelectResult>> pending =
                      nb select first from async_cases;
                  val Option<SelectResult> async_result = await pending;

                  stdio.stdout.write(":");
                  stdio.stdout.write(async_result.is_some());
                  stdio.stdout.write(":");
                  stdio.stdout.write(async_result.unwrap().value);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("true:7:true:9", run(program));
    }

    @Test
    void dynamicSelectCanAwaitCompletedFutureAndExposeWinningOperation() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> channel = Channel.new<int>(1);
                  writech channel, 9;
                  val Future<Option<SelectResult>> ready =
                      nb select first from [SelectCase.read(channel)];
                  val SelectSet mixed = SelectSet.new([
                    SelectCase.await(ready),
                    SelectCase.timeout(1000)
                  ]);
                  val Option<SelectResult> winner = select first from mixed;
                  stdio.stdout.write(winner.unwrap().operation);
                  stdio.stdout.write(":");
                  val Option<SelectResult> stillReadable = await ready;
                  stdio.stdout.write(stillReadable.unwrap().value);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("await:9", run(program));
    }

    @Test
    void dynamicSelectZeroDeadlineWinsBeforeAnUnreadyChannel() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> channel = Channel.new<int>(1);
                  val SelectSet choices = SelectSet.new([
                    SelectCase.read(channel),
                    SelectCase.timeout(0)
                  ]);
                  val Option<SelectResult> winner = try select first from choices;
                  stdio.stdout.write(winner.unwrap().operation);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("timeout", run(program));
    }

    @Test
    void selectingAWriteDescriptorMovesPayloadAtRegistrationConstruction() {
        String source = """
                pub fnc main(): void {
                  val Channel<Array<int>> output = Channel.new<Array<int>>(1);
                  val Array<int> owned = [1, 2];
                  val SelectSet choices = SelectSet.new([
                    SelectCase.write(output, owned),
                    SelectCase.timeout(0)
                  ]);
                  stdio.stdout.write(owned.size);
                  return;
                }
                """;
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    @Test
    void mixedCaseFactoriesRejectNonFutureAndNonIntegerTimeoutArguments() {
        for (String invalid : new String[] {
                "SelectCase.await(42)",
                "SelectCase.cancelled(true)",
                "SelectCase.timeout(\"not milliseconds\")"
        }) {
            String source = """
                    pub fnc main(): void {
                      val SelectSet cases = SelectSet.new([%s]);
                      return;
                    }
                    """.formatted(invalid);
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheck(source));
        }
    }

    @Test
    void sourceSelectPlanSupportsVersionedAddRemoveReplaceAndLiveSelect() throws Exception {
        String program = """
                pub fnc main(): void {
                  val Channel<int> inbox = Channel.new<int>(1);
                  let mut SelectPlan plan = SelectPlan.new([
                    SelectCase.read(inbox)
                  ]);
                  plan.addCase(SelectCase.timeout(0));
                  val Option<SelectResult> first = try select first from plan;
                  stdio.stdout.write(first.unwrap().operation);
                  stdio.stdout.write(":");
                  stdio.stdout.write(plan.revision);

                  plan.replaceCase(0, SelectCase.timeout(0));
                  plan.removeCase(1);
                  stdio.stdout.write(":");
                  stdio.stdout.write(plan.revision);
                  val Option<SelectResult> second = try select first from plan;
                  stdio.stdout.write(":");
                  stdio.stdout.write(second.unwrap().operation);
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("timeout:1:3:timeout", run(program));
    }

    @Test
    void selectPlanCannotTravelAsAnActorMailPayload() {
        String source = """
                define actor Worker as
                  receive(ActorMail<SelectPlan> mail): void { return; }
                end
                """;
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "select-option.ores")
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
