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
                define class Flag as
                  pub let int value = 0;
                end
                pub async fnc main(): void {
                  val flag = SharedMutex.new(new Flag());
                  let Future<void> child = task {
                    defer stdio.stdout.write("|cleanup");
                    stdio.stdout.write("started");
                    block {
                      val g = flag.lock();
                      g.value = 1;
                    }
                    loop {
                      rt cooperate;
                    }
                    return;
                  };
                  let mut int started = 0;
                  while started == 0 {
                    rt cooperate;
                    block {
                      val g = flag.lock();
                      started = g.value;
                    }
                  }
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
                define class Flag as
                  pub let int value = 0;
                end
                pub async fnc main(): void {
                  val flag = SharedMutex.new(new Flag());
                  task scope {
                    let Future<void> child = task {
                      defer stdio.stdout.write("cleanup");
                      block {
                      val g = flag.lock();
                      g.value = 1;
                    }
                      loop {
                        rt cooperate;
                      }
                      return;
                    };
                    let mut int started = 0;
                  while started == 0 {
                    rt cooperate;
                    block {
                      val g = flag.lock();
                      started = g.value;
                    }
                  }
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
                    val lock_guard = mutex.lock();
                    lock_guard.value = 7;
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
                        val lock_guard = mutex.lock();
                        lock_guard.value = 7;
                      }
                      rt cooperate;
                    }
                    return;
                  };
                  let mut int seen = 0;
                  while seen == 0 {
                    rt cooperate;
                    block {
                      val g = mutex.lock();
                      seen = g.value;
                    }
                  }
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

    @Test
    void cancelAfterTimesOutALoopingTaskAndRunsItsDefer() throws Exception {
        assertEquals("true|cleanup|#cancelled", run("""
                pub async fnc main(): void {
                  let Future<void> child = task {
                    defer stdio.stdout.write("|cleanup");
                    loop {
                      rt cooperate;
                    }
                    return;
                  };
                  stdio.stdout.write(child.cancel_after(30));
                  val outcome = await child.get_done();
                  stdio.stdout.write("|");
                  stdio.stdout.write(outcome);
                  return;
                }
                """));
    }

    @Test
    void scopeDeadlineCancelsChildrenThenTheScopeExits() throws Exception {
        assertEquals("cleanup|after", run("""
                pub async fnc main(): void {
                  task scope deadline(30) {
                    let Future<void> child = task {
                      defer stdio.stdout.write("cleanup");
                      loop {
                        rt cooperate;
                      }
                      return;
                    };
                  }
                  stdio.stdout.write("|after");
                  return;
                }
                """));
    }

    @Test
    void scopeDeadlineMustBeAnInt() {
        PolyglotException error = assertThrows(PolyglotException.class, () -> run("""
                pub async fnc main(): void {
                  task scope deadline("soon") {
                  }
                  return;
                }
                """));
        assertTrue(error.getMessage().contains("deadline(ms) requires an int"), error::getMessage);
    }

    @Test
    void failingChildCancelsItsSiblingsByDefault() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PolyglotException error = assertThrows(PolyglotException.class, () -> run("""
                pub async fnc main(): void {
                  task scope {
                    let Future<void> looping = task {
                      defer stdio.stdout.write("sibling-cancelled");
                      loop {
                        rt cooperate;
                      }
                      return;
                    };
                    let Future<int> failing = task {
                      rt cooperate;
                      val Array<int> missing = new Array<int>();
                      return missing.get(0);
                    };
                  }
                  stdio.stdout.write("unreachable");
                  return;
                }
                """, output));
        String printed = output.toString(StandardCharsets.UTF_8);
        assertEquals("sibling-cancelled", printed);
        assertTrue(error.getMessage().toLowerCase(java.util.Locale.ROOT).contains("index"),
                error::getMessage);
    }

    @Test
    void superviseScopeKeepsSiblingsRunningAndReportsTheFailure() throws Exception {
        assertEquals("sibling-done|#failed|after", run("""
                pub async fnc main(): void {
                  task scope supervise {
                    let Future<void> sibling = task {
                      rt cooperate;
                      rt cooperate;
                      rt cooperate;
                      stdio.stdout.write("sibling-done");
                      return;
                    };
                    let Future<int> failing = task {
                      val Array<int> missing = new Array<int>();
                      return missing.get(0);
                    };
                    val outcome = await failing.get_done();
                    await sibling.get_done();
                    stdio.stdout.write("|");
                    stdio.stdout.write(outcome);
                  }
                  stdio.stdout.write("|after");
                  return;
                }
                """));
    }

    @Test
    void superviseScopeDoesNotAbsorbAPanic() {
        PolyglotException error = assertThrows(PolyglotException.class, () -> run("""
                pub async fnc main(): void {
                  task scope supervise {
                    let Future<void> panicking = task {
                      None.unwrap();
                      return;
                    };
                  }
                  stdio.stdout.write("unreachable");
                  return;
                }
                """));
        assertTrue(error.getMessage().contains("Option::unwrap"), error::getMessage);
    }

    /** Loop back-edges are soft-preemption points, so CPU loops are cancellable too. */
    @Test
    void tightCpuLoopWithoutAwaitIsStillCancellable() throws Exception {
        // Handshake through a SharedMutex so the cancel lands after the child
        // is inside its loop (a cancel before its first turn has no frames).
        assertEquals("cleanup|#cancelled", run("""
                define class Flag as
                  pub let int value = 0;
                end
                pub async fnc main(): void {
                  val flag = SharedMutex.new(new Flag());
                  let Future<void> child = task {
                    defer stdio.stdout.write("cleanup");
                    block {
                      val g = flag.lock();
                      g.value = 1;
                    }
                    let mut int n = 0;
                    loop {
                      n = n + 1;
                    }
                    return;
                  };
                  let mut int started = 0;
                  while started == 0 {
                    rt cooperate;
                    block {
                      val g = flag.lock();
                      started = g.value;
                    }
                  }
                  child.cancel();
                  val outcome = await child.get_done();
                  stdio.stdout.write("|");
                  stdio.stdout.write(outcome);
                  return;
                }
                """));
    }

    @Test
    void taskCanObserveItsOwnCancellationRequestAndStopEarly() throws Exception {
        // A task that returns after its cancellation was requested still
        // reports #cancelled (Kotlin semantics): the request won.
        assertEquals("false|stopped-early|#cancelled", run("""
                define class Flag as
                  pub let int value = 0;
                end
                pub async fnc main(): void {
                  val flag = SharedMutex.new(new Flag());
                  stdio.stdout.write(task_cancel_requested());
                  let Future<void> child = task {
                    block {
                      val g = flag.lock();
                      g.value = 1;
                    }
                    loop {
                      if task_cancel_requested() then
                        stdio.stdout.write("|stopped-early");
                        return;
                      fi
                    }
                    return;
                  };
                  let mut int started = 0;
                  while started == 0 {
                    rt cooperate;
                    block {
                      val g = flag.lock();
                      started = g.value;
                    }
                  }
                  child.cancel();
                  val outcome = await child.get_done();
                  stdio.stdout.write("|");
                  stdio.stdout.write(outcome);
                  return;
                }
                """));
    }

    /** Future.first: the winner is returned and losing task handles are cancelled. */
    @Test
    void futureFirstCancelsTheLosingTask() throws Exception {
        // If the loser were not cancelled the scope could never exit. The
        // winner prints after the scope has joined, so output order is fixed.
        assertEquals("|slow-cleanup|winner=1", run("""
                pub async fnc main(): void {
                  let mut int winner = 0;
                  task scope {
                    let Future<int> fast = task {
                      return 1;
                    };
                    let Future<int> slow = task {
                      defer stdio.stdout.write("|slow-cleanup");
                      loop {
                        rt cooperate;
                      }
                      return 2;
                    };
                    winner = await Future.first([fast, slow]);
                  }
                  stdio.stdout.write("|winner=");
                  stdio.stdout.write(winner);
                  return;
                }
                """));
    }

    private static String run(String sourceText) throws Exception {
        return run(sourceText, new ByteArrayOutputStream());
    }

    private static String run(String sourceText, ByteArrayOutputStream output) throws Exception {
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
