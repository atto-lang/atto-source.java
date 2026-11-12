package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compiler-produced Atto guest witnesses for generator-v1 g11/g12/g13.
 * They complement host GeneratorRuntime races; parser acceptance alone is
 * never counted as an executed generator.
 */
@Timeout(20)
final class GeneratorLifecycleGuestTest {
    @Test
    void syncEarlyCloseMakesSubsequentPullsStickyDone() throws Exception {
        assertEquals("42", run("""
                pub gen* values(): Iterator<int> {
                  yield 20;
                  yield 99;
                  return;
                }
                pub routine main(): void {
                  val Iterator<int> iter = values();
                  val IteratorResult<int> first = iter.next();
                  iter.close();
                  val IteratorResult<int> terminal = iter.next();
                  val IteratorResult<int> repeated = iter.next();
                  if terminal.done then
                    if repeated.done then
                      if terminal.value.is_none() then
                        stdio.stdout.write(first.value.unwrap() + 22);
                        return;
                      fi
                    fi
                  fi
                  stdio.stdout.write("bad");
                  return;
                }
                """));
    }

    @Test
    void asyncCloseIsAwaitedBeforeTerminalPulls() throws Exception {
        assertEquals("42", run("""
                pub async gen* values(): AsyncIterator<int> {
                  yield 20;
                  yield 99;
                  return;
                }
                pub async routine main(): void {
                  val AsyncIterator<int> iter = values();
                  val IteratorResult<int> first = await iter.next();
                  await iter.close();
                  val IteratorResult<int> terminal = await iter.next();
                  val IteratorResult<int> repeated = await iter.next();
                  if terminal.done then
                    if repeated.done then
                      if terminal.value.is_none() then
                        stdio.stdout.write(first.value.unwrap() + 22);
                        return;
                      fi
                    fi
                  fi
                  stdio.stdout.write("bad");
                  return;
                }
                """));
    }

    @Test
    void syncYieldDelegationPreservesOrderAndOneActivation() throws Exception {
        assertEquals("42", run("""
                pub gen* inner(): Iterator<int> {
                  yield 20;
                  yield 22;
                  return;
                }
                pub gen* outer(): Iterator<int> {
                  yield* inner();
                  return;
                }
                pub routine main(): void {
                  let int total = 0;
                  for const value of outer() {
                    total = total + value;
                  }
                  stdio.stdout.write(total);
                  return;
                }
                """));
    }

    private static String run(String sourceText) throws Exception {
        // Type-check the exact source that will be executed by the real guest.
        OresCompiler.parseAndTypeCheck(sourceText);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText,
                        "generator-lifecycle.ores")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(stdout)
                .build()) {
            context.eval(source);
        }
        return stdout.toString(StandardCharsets.UTF_8);
    }
}
