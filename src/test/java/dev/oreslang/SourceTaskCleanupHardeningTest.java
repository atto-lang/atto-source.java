package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-level proof targets for single-owner lexical cleanup in SourceTask.
 * These are guest-execution tests, not static AST assertions; exact-head
 * execution on JDK25/GraalVM remains a blocking merge requirement.
 */
@Timeout(20)
final class SourceTaskCleanupHardeningTest {
    @Test
    void resumedNestedBlockRunsDefersInLifoOrderAndFinallyOnlyOnce()
            throws Exception {
        String output = run("""
                async fnc tick(): int {
                  rt cooperate;
                  return 17;
                }
                async fnc compute(): int {
                  let mut int counter = 0;
                  try {
                    defer stdio.stdout.write("1");
                    defer stdio.stdout.write("2");
                    counter = await tick();
                  } catch (err) {
                    counter = 99;
                  } finally {
                    stdio.stdout.write("F");
                  }
                  return counter;
                }
                pub fnc main(): void {
                  stdio.stdout.write(await compute());
                  return;
                }
                """);
        assertEquals("21F17", output,
                "each lexical cleanup scope must publish at most one completion");
    }

    @Test
    void implicitMutexGuardIsReleasedAfterLexicalBlockWithoutManualDefer()
            throws Exception {
        String output = run("""
                define class Counter as
                  pub let int value = 0;
                end
                pub async routine main(): void {
                  val mutex = Mutex.new(new Counter());
                  block {
                    val lock_guard = mutex.lock();
                    lock_guard.value = 7;
                  }
                  // A lexical block's exit must release its implicit guard,
                  // allowing another same-Task acquisition without a deadlock.
                  val second = mutex.lock();
                  stdio.stdout.write(second.value);
                  second.release();
                  return;
                }
                """);
        assertEquals("7", output);
    }

    @Test
    void lexicalScopeReleasesAllIndependentMutexGuardsBeforeNextAcquisition()
            throws Exception {
        assertEquals("3|4", run("""
                define class Counter as
                  pub let int value = 0;
                end
                pub async routine main(): void {
                  val first = Mutex.new(new Counter());
                  val second = Mutex.new(new Counter());
                  block {
                    val one = first.lock();
                    val two = second.lock();
                    one.value = 3;
                    two.value = 4;
                  }
                  val oneAgain = first.lock();
                  val twoAgain = second.lock();
                  stdio.stdout.write(oneAgain.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(twoAgain.value);
                  twoAgain.release();
                  oneAgain.release();
                  return;
                }
                """));
    }

    @Test
    void failedDeferDoesNotErasePreviouslyThrownOrdinarySourceFailure()
            throws Exception {
        String source = """
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end

                fnc bad_cleanup(): void {
                  val Animal animal = new Cat();
                  val Dog dog = animal as Dog;
                  return;
                }
                async fnc compute(): int {
                  rt cooperate;
                  defer bad_cleanup();
                  val Array<int> missing = new Array<int>();
                  return missing.get(0);
                }
                pub fnc main(): void {
                  val int impossible = await compute();
                  stdio.stdout.write(impossible);
                  return;
                }
                """;
        PolyglotException error = assertThrows(
                PolyglotException.class, () -> run(source));
        assertTrue(
                error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("index"),
                "the source's initial array bounds error must remain primary: " + error);
    }

    @Test
    void cleanupPanicStillEscapesEarlierRecoverableSourceFailure()
            throws Exception {
        String source = """
                define class Animal as
                end
                define class Dog extends Animal as
                end
                define class Cat extends Animal as
                end

                fnc fatal_cleanup(): void {
                  val Option<int> missing = None;
                  val int impossible = missing.unwrap();
                  return;
                }
                async fnc compute(): int {
                  rt cooperate;
                  defer fatal_cleanup();
                  val Animal animal = new Cat();
                  val Dog cast = animal as Dog;
                  return 0;
                }
                pub fnc main(): void {
                  val int impossible = await compute();
                  stdio.stdout.write(impossible);
                  return;
                }
                """;
        PolyglotException error = assertThrows(
                PolyglotException.class, () -> run(source));
        assertTrue(error.getMessage().contains("Option::unwrap"),
                "fatal cleanup panic must reach the supervisor, not be masked: " + error);
    }

    @Test
    void asyncIteratorBreakClosesProducerExactlyOnceAndRunsLexicalDefer()
            throws Exception {
        assertEquals("7DC", run("""
                async generator fnc values(): int {
                  // Generator cleanup must run exactly once when the consumer
                  // breaks early or the generator completes.
                  defer stdio.stdout.write("C");
                  yield 7;
                  yield 8;
                  return;
                }
                pub async routine main(): void {
                  for await const value of values() {
                    defer stdio.stdout.write("D");
                    stdio.stdout.write(value);
                    break;
                  }
                  return;
                }
                """));
    }

    @Test
    void completedAsyncGeneratorDoesNotDoubleCloseAcrossNestedFinally()
            throws Exception {
        assertEquals("12C|F", run("""
                async generator fnc values(): int {
                  // Generator cleanup must run exactly once when the consumer
                  // breaks early or the generator completes.
                  defer stdio.stdout.write("C");
                  yield 1;
                  yield 2;
                  return;
                }
                pub async routine main(): void {
                  try {
                    for await const value of values() {
                      stdio.stdout.write(value);
                    }
                  } catch (err) {
                    stdio.stdout.write("unexpected");
                  } finally {
                    stdio.stdout.write("|F");
                  }
                  return;
                }
                """));
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, sourceText, "source-task-cleanup-hardening.ores")
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
