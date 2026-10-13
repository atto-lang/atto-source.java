package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Executable replay regression: a failure propagating through nested
 * expression/statement continuations must never run catch/finally or a
 * previously committed source side effect twice.
 */
@Timeout(25)
final class SourceContinuationSingleDeliveryTest {
    @Test void completedAwaitThenDownstreamFailureRunsCleanupOnce() throws Exception {
        assertEquals("value:7:caught:finally:after", run("""
                async fnc answer(): int { return 7; }

                pub async routine main(): void {
                  try {
                    val result = await answer();
                    stdio.stdout.write("value:");
                    stdio.stdout.write(result);
                    stdio.stdout.write(":");
                    val Option<int> empty = None;
                    stdio.stdout.write(empty.unwrap());
                  } catch (err) {
                    stdio.stdout.write("caught:");
                  } finally {
                    stdio.stdout.write("finally:");
                  }
                  stdio.stdout.write("after");
                  return;
                }
                """, "source-continuation-completed-await.ores"));
    }

    @Test void cooperativeAwaitThenDownstreamFailureRunsCleanupOnce() throws Exception {
        assertEquals("value:9:caught:finally:after", run("""
                async fnc answer(): int {
                  rt cooperate;
                  return 9;
                }

                pub async routine main(): void {
                  try {
                    val result = await answer();
                    stdio.stdout.write("value:");
                    stdio.stdout.write(result);
                    stdio.stdout.write(":");
                    val Option<int> empty = None;
                    stdio.stdout.write(empty.unwrap());
                  } catch (err) {
                    stdio.stdout.write("caught:");
                  } finally {
                    stdio.stdout.write("finally:");
                  }
                  stdio.stdout.write("after");
                  return;
                }
                """, "source-continuation-cooperative-await.ores"));
    }

    @Test void closedChannelFailureAfterCommittedEffectDoesNotReplayCatch() throws Exception {
        assertEquals("before:caught:finally:after", run("""
                pub async routine main(): void {
                  val Channel<int> closed = Channel.new<int>(0);
                  closed.close();
                  try {
                    stdio.stdout.write("before:");
                    val data = readch closed;
                    stdio.stdout.write(data);
                  } catch (err) {
                    stdio.stdout.write("caught:");
                  } finally {
                    stdio.stdout.write("finally:");
                  }
                  stdio.stdout.write("after");
                  return;
                }
                """, "source-continuation-closed-channel.ores"));
    }

    private static String run(String text, String sourceName) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(bytes).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, text, sourceName)
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
