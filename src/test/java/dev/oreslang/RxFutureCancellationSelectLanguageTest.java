package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Exact source primitive used by rx-oreslang-channels FutureSubscription:
 * cancellation selects a private stop channel, detaches the losing Future
 * waiter, and never revokes the borrowed producer.
 */
final class RxFutureCancellationSelectLanguageTest {
    @Test
    void stopChannelWinsAndSharedFutureRemainsAwaitable() throws Exception {
        String program = """
                async fnc compute(Channel<int> input): int {
                  return await (nb readch input);
                }

                pub routine main(): void {
                  val Channel<int> producer = Channel.new<int>(1);
                  val Future<int> shared = compute(producer);
                  val Channel<bool> stopped = Channel.new<bool>(1);
                  val SelectSet cases = SelectSet.new([
                    SelectCase.read(stopped),
                    SelectCase.future(shared)
                  ]);

                  writech stopped, true;
                  val Option<SelectResult> selected = select first from cases;
                  stdio.stdout.write(selected.unwrap().index);
                  stdio.stdout.write(":");

                  writech producer, 42;
                  stdio.stdout.write(await shared);
                  return;
                }
                """;

        OresCompiler.parseAndTypeCheck(program);
        assertEquals("0:42", run(program));
    }

    @Test
    void futureWinnerStillReturnsItsValue() throws Exception {
        String program = """
                async fnc compute(): int { return 17; }

                pub routine main(): void {
                  val Channel<bool> stopped = Channel.new<bool>(1);
                  val Future<int> shared = compute();
                  val SelectSet cases = SelectSet.new([
                    SelectCase.read(stopped),
                    SelectCase.future(shared)
                  ]);
                  val Option<SelectResult> selected = select first from cases;
                  stdio.stdout.write(selected.unwrap().index);
                  stdio.stdout.write(":");
                  stdio.stdout.write(selected.unwrap().value);
                  return;
                }
                """;

        OresCompiler.parseAndTypeCheck(program);
        assertEquals("1:17", run(program));
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, program, "rx-future-cancel-select.ores")
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
