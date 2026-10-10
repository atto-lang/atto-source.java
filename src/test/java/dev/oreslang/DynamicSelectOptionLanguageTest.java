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
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("await", run(program));
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
