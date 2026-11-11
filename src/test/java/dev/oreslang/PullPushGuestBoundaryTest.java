package dev.oreslang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Actual .atto guest compilation/execution regressions using already-admitted
 * generator + channel operations. The BoundedDemandChannel host adapter is not
 * yet a guest Flow intrinsic; these tests do not claim that it is.
 */
@Timeout(30)
final class PullPushGuestBoundaryTest {
    @Test
    void lazyPullGeneratorFeedsCapacityOneChannelWithoutMaterializing() throws Exception {
        assertEquals("1234", run("""
                generator fnc values(): int {
                  yield 1;
                  yield 2;
                  yield 3;
                  yield 4;
                  return;
                }
                pub routine main(): void {
                  val Channel<int> output = Channel.new<int>(1);
                  for const value of values() {
                    writech output, value;
                    stdio.stdout.write(readch output);
                  }
                  return;
                }
                """));
    }

    @Test
    void asyncPullAndRendezvousCompleteWithoutParkingProducerCarrier() throws Exception {
        assertEquals("12", run("""
                async generator fnc values(): int {
                  yield 1;
                  yield 2;
                  return;
                }
                pub async routine main(): void {
                  val Channel<int> output = Channel.new<int>(0);
                  for await const value of values() {
                    val Future<int> read = nb readch output;
                    await nb writech output, value;
                    stdio.stdout.write(await read);
                  }
                  return;
                }
                """));
    }

    @Test
    void borrowedNestedChannelPayloadFailsBeforeGuestEmission() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc unsafe(Channel<Option<&int>> borrowed): void {
                          return;
                        }
                        """)));
    }

    @Test
    void aMoveOnlyPayloadCannotBeSelectedIntoTwoSends() {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse("""
                        fnc unsafe(): void {
                          val Channel<Fnc<int, int>> first = Channel.new<Fnc<int, int>>(1);
                          val Channel<Fnc<int, int>> second = Channel.new<Fnc<int, int>>(1);
                          val Fnc<int, int> callback = |x| -> { return x * 2; };
                          nb select {
                            case writech first, callback: { }
                            case writech second, callback: { }
                          }
                          return;
                        }
                        """)));
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText,
                "pull-push-v1.atto").mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
