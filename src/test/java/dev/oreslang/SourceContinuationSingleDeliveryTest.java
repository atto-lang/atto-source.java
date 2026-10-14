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


    @Test void completedChannelReadThenDownstreamFailureDeliversOnce() throws Exception {
        assertEquals("read:13:caught:finally:after", run("""
                pub async routine main(): void {
                  val Channel<int> ready = Channel.new<int>(1);
                  writech ready, 13;
                  try {
                    val value = readch ready;
                    stdio.stdout.write("read:");
                    stdio.stdout.write(value);
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
                """, "source-continuation-completed-channel.ores"));
    }

    @Test void nestedCallableAfterAwaitCannotReplayDownstreamFailure() throws Exception {
        assertEquals("call:14:caught:finally:after", run("""
                async fnc ready(): int { return 7; }
                fnc doubled(int input): int { return input * 2; }

                pub async routine main(): void {
                  try {
                    val result = doubled(await ready());
                    stdio.stdout.write("call:");
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
                """, "source-continuation-nested-invocation.ores"));
    }

    @Test void nestedBinaryAwaitThenLaterFailureDoesNotRepublishResult() throws Exception {
        assertEquals("sum:8:caught:finally:after", run("""
                async fnc answer(): int {
                  rt cooperate;
                  return 3;
                }

                pub async routine main(): void {
                  try {
                    val sum = 5 + (await answer());
                    stdio.stdout.write("sum:");
                    stdio.stdout.write(sum);
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
                """, "source-continuation-binary-await.ores"));
    }

    @Test void nestedIndexAwaitThenLaterFailureKeepsOriginalFlow() throws Exception {
        assertEquals("item:20:caught:finally:after", run("""
                async fnc index(): int {
                  rt cooperate;
                  return 1;
                }

                pub async routine main(): void {
                  try {
                    val items = arr[10, 20];
                    val item = items[await index()];
                    stdio.stdout.write("item:");
                    stdio.stdout.write(item);
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
                """, "source-continuation-index-await.ores"));
    }

    @Test void assignmentAwaitThenLaterFailureCannotDuplicateSideEffects() throws Exception {
        assertEquals("assigned:4:caught:finally:after", run("""
                async fnc answer(): int {
                  rt cooperate;
                  return 4;
                }

                pub async routine main(): void {
                  try {
                    let value = 0;
                    value = await answer();
                    stdio.stdout.write("assigned:");
                    stdio.stdout.write(value);
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
                """, "source-continuation-assign-await.ores"));
    }

    @Test
    void deferredAndFinallyCleanupExecuteOnceAcrossCooperativeAwait() throws Exception {
        assertEquals("defer:catch:finally:after", run("""
                async fnc fail(): int {
                  rt cooperate;
                  val Option<int> empty = None;
                  return empty.unwrap();
                }

                pub async routine main(): void {
                  try {
                    defer stdio.stdout.write("defer:");
                    val unused = await fail();
                  } catch (err) {
                    stdio.stdout.write("catch:");
                  } finally {
                    stdio.stdout.write("finally:");
                  }
                  stdio.stdout.write("after");
                  return;
                }
                """, "source-defer-finally-cooperation.ores"));
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
