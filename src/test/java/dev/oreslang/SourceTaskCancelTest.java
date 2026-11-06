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
 * Guest-source tests for task handles: a Future produced by {@code task { }}
 * can be cancelled from Oreslang code, cancellation unwinds the child's
 * defers and lock guards, and {@code get_done()} reports the outcome only
 * after the child has physically stopped.
 */
@Timeout(20)
final class SourceTaskCancelTest {
    @Test
    void cancellingASuspendedTaskRunsItsDeferAndReportsCancelled() throws Exception {
        assertEquals("started|cleanup|#cancelled", run("""
                pub async fnc main(): void {
                  let Future<void> child = task {
                    defer stdio.stdout.write("|cleanup");
                    stdio.stdout.write("started");
                    loop {
                      rt cooperate;
                    }
                    return;
                  };
                  rt cooperate;
                  rt cooperate;
                  child.cancel();
                  val outcome = await child.get_done();
                  stdio.stdout.write("|");
                  stdio.stdout.write(outcome);
                  return;
                }
                """));
    }

    @Test
    void completedTaskReportsSuccessAndCancelIsANoOp() throws Exception {
        assertEquals("#success|false", run("""
                pub async fnc main(): void {
                  let Future<int> child = task {
                    return 7;
                  };
                  stdio.stdout.write(await child.get_done());
                  stdio.stdout.write("|");
                  stdio.stdout.write(child.cancel());
                  return;
                }
                """));
    }

    @Test
    void cancelWithRecordsTheRequest() throws Exception {
        assertEquals("true|true|#cancelled", run("""
                pub async fnc main(): void {
                  let Future<void> child = task {
                    loop {
                      rt cooperate;
                    }
                    return;
                  };
                  rt cooperate;
                  stdio.stdout.write(child.cancel_with(#no_longer_needed));
                  stdio.stdout.write("|");
                  stdio.stdout.write(child.is_cancel_requested());
                  stdio.stdout.write("|");
                  stdio.stdout.write(await child.get_done());
                  return;
                }
                """));
    }

    @Test
    void cancellingInsideATaskScopeLetsTheScopeExit() throws Exception {
        assertEquals("cleanup|after-scope", run("""
                pub async fnc main(): void {
                  task scope {
                    let Future<void> child = task {
                      defer stdio.stdout.write("cleanup");
                      loop {
                        rt cooperate;
                      }
                      return;
                    };
                    rt cooperate;
                    child.cancel();
                  }
                  stdio.stdout.write("|after-scope");
                  return;
                }
                """));
    }

    /**
     * Cancellation is delivered only at suspension points, and the compiler
     * rejects any suspension while a MutexGuard is live. So a cancelled task
     * can never be caught holding a guard: it was released at block exit.
     */
    @Test
    void taskCannotSuspendWhileHoldingAGuardSoCancellationNeverStrandsALock() {
        PolyglotException error = assertThrows(PolyglotException.class, () -> run("""
                define class Counter as
                  pub let int value = 0;
                end
                pub async fnc main(): void {
                  val mutex = SharedMutex.new(new Counter());
                  let Future<void> child = task {
                    val guard = mutex.lock();
                    guard.value = 7;
                    rt cooperate;
                    return;
                  };
                  child.cancel();
                  return;
                }
                """));
        assertTrue(error.getMessage().contains("cannot cooperate while holding a MutexGuard"),
                error::getMessage);
    }

    @Test
    void cancelledTaskReleasesLockTakenInsideABlockBeforeSuspending() throws Exception {
        assertEquals("#cancelled|7", run("""
                define class Counter as
                  pub let int value = 0;
                end
                pub async fnc main(): void {
                  val mutex = SharedMutex.new(new Counter());
                  let Future<void> child = task {
                    loop {
                      block {
                        val guard = mutex.lock();
                        guard.value = 7;
                      }
                      rt cooperate;
                    }
                    return;
                  };
                  rt cooperate;
                  rt cooperate;
                  child.cancel();
                  stdio.stdout.write(await child.get_done());
                  val again = mutex.lock();
                  stdio.stdout.write("|");
                  stdio.stdout.write(again.value);
                  again.release();
                  return;
                }
                """));
    }

    @Test
    void borrowedFutureCannotBeCancelledByAnObserver() {
        PolyglotException error = assertThrows(PolyglotException.class, () -> run("""
                pub async fnc main(): void {
                  let Future<int> child = task {
                    return 1;
                  };
                  val Future<int> mapped = child.map(|int v| -> v + 1);
                  mapped.cancel();
                  return;
                }
                """));
        assertTrue(error.getMessage().contains("only available on a task { } handle"),
                error::getMessage);
    }

    private static String run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, sourceText, "source-task-cancel.ores")
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
