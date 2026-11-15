package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** {@code try { } finally { }} without catch: finally runs, the failure propagates. */
@Timeout(20)
final class SourceTryFinallyTest {
    @Test
    void finallyRunsOnNormalExit() throws Exception {
        assertEquals("body|finally|after", run("""
                pub fnc main(): void {
                  try {
                    stdio.stdout.write("body");
                  } finally {
                    stdio.stdout.write("|finally");
                  }
                  stdio.stdout.write("|after");
                  return;
                }
                """, new ByteArrayOutputStream()));
    }

    @Test
    void failurePropagatesAfterFinallyRuns() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PolyglotException error = assertThrows(PolyglotException.class, () -> run("""
                pub fnc main(): void {
                  try {
                    val Array<int> missing = new Array<int>();
                    stdio.stdout.write(missing.get(0));
                  } finally {
                    stdio.stdout.write("finally");
                  }
                  stdio.stdout.write("unreachable");
                  return;
                }
                """, output));
        assertEquals("finally", output.toString(StandardCharsets.UTF_8));
        assertTrue(error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("index"),
                error::getMessage);
    }

    @Test
    void asyncFinallyRunsAcrossAwaitAndReturnValueIsKept() throws Exception {
        assertEquals("F17", run("""
                async fnc tick(): int {
                  rt cooperate;
                  return 17;
                }
                async fnc compute(): int {
                  try {
                    return await tick();
                  } finally {
                    stdio.stdout.write("F");
                  }
                }
                pub fnc main(): void {
                  stdio.stdout.write(await compute());
                  return;
                }
                """, new ByteArrayOutputStream()));
    }

    @Test
    void cancellationRunsFinallyOfASuspendedTask() throws Exception {
        assertEquals("finally|#cancelled", run("""
                pub async fnc main(): void {
                  let Future<void> child = task {
                    try {
                      loop {
                        rt cooperate;
                      }
                    } finally {
                      stdio.stdout.write("finally");
                    }
                    return;
                  };
                  rt cooperate;
                  child.cancel();
                  val outcome = await child.get_done();
                  stdio.stdout.write("|");
                  stdio.stdout.write(outcome);
                  return;
                }
                """, new ByteArrayOutputStream()));
    }

    @Test
    void generatorTryFinallyClosesOnceWhenConsumerBreaks() throws Exception {
        assertEquals("7C", run("""
                async generator fnc values(): int {
                  try {
                    yield 7;
                    yield 8;
                  } finally {
                    stdio.stdout.write("C");
                  }
                  return;
                }
                pub async routine main(): void {
                  for await const value of values() {
                    stdio.stdout.write(value);
                    break;
                  }
                  return;
                }
                """, new ByteArrayOutputStream()));
    }

    @Test
    void tryWithNeitherCatchNorFinallyIsRejected() {
        PolyglotException error = assertThrows(PolyglotException.class, () -> run("""
                pub fnc main(): void {
                  try {
                    stdio.stdout.write("x");
                  }
                  return;
                }
                """, new ByteArrayOutputStream()));
        assertTrue(error.getMessage().contains("expected catch or finally"), error::getMessage);
    }

    private static String run(String sourceText, ByteArrayOutputStream output) throws Exception {
        Source source = Source.newBuilder(
                        OresLanguage.ID, sourceText, "source-try-finally.ores")
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
