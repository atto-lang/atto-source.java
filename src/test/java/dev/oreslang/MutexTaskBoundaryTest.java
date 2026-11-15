package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Mutex<T> is confined to its creating actor/execution domain. The runtime
 * rejects moving one into an async task, so the checker must reject it first
 * instead of admitting a program that can only fail when it runs.
 */
final class MutexTaskBoundaryTest {
    private static void rejected(String source) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertFalse(error.getMessage().contains("parse error"), error.getMessage());
    }

    @Test
    void mutexCannotCrossAnAsyncBoundaryInAnyWrapper() {
        rejected("async fnc take(Mutex<int> m): int { return 1; }");
        rejected("async fnc take(Box<Mutex<int>> m): int { return 1; }");
        rejected("async fnc take(Arc<Mutex<int>> m): int { return 1; }");
        rejected("async fnc take(Option<Mutex<int>> m): int { return 1; }");
        rejected("""
                define class Holder as
                  pub let Mutex<int> lock = Mutex.new(1);
                end
                async fnc take(Holder holder): int { return 1; }
                """);
    }

    @Test
    void mutexCannotBeCapturedIntoATask() {
        String counter = """
                define class Counter as
                  pub let mut int count = 41;
                end
                """;
        for (String task : new String[] {"task", "cpu task"}) {
            rejected(counter + """
                    pub routine main(): int {
                      val Mutex<Counter> m = Mutex.new(new Counter());
                      val Future<int> t = %s {
                        val g = m.lock();
                        val int v = g.count;
                        g.release();
                        return v;
                      };
                      return await t;
                    }
                    """.formatted(task));
        }
    }

    @Test
    void mutexCannotEscapeThroughIndirectRoutes() {
        String counter = """
                define class Counter as
                  pub let mut int count = 41;
                end
                """;
        // A channel of Mutex may exist inside its actor but cannot reach a task.
        rejected(counter + """
                pub async fnc main(): void {
                  val Channel<Mutex<Counter>> ch = Channel.new<Mutex<Counter>>(1);
                  val Future<int> t = task { val m = await (nb readch ch); return 1; };
                  return;
                }
                """);
        rejected(counter + "async fnc take(Channel<Mutex<Counter>> c): int { return 1; }");
        // A closure that captured a Mutex is itself actor-local.
        rejected(counter + """
                pub async fnc main(): void {
                  val Mutex<Counter> m = Mutex.new(new Counter());
                  val f = || -> { val g = m.lock(); g.release(); return 1; };
                  val Future<int> t = task { return f(); };
                  return;
                }
                """);
        // Results cannot carry one out of a task, and SharedMutex cannot wrap one.
        rejected(counter + "async fnc make(): Mutex<Counter> { return Mutex.new(new Counter()); }");
        rejected(counter + """
                pub async fnc main(): void {
                  val Future<Mutex<Counter>> t = task { return Mutex.new(new Counter()); };
                  return;
                }
                """);
        rejected(counter + """
                pub fnc main(): void {
                  val SharedMutex<Mutex<Counter>> s = SharedMutex.new(Mutex.new(new Counter()));
                  return;
                }
                """);
        rejected("actor fnc take(Mutex<int> m): int { return 1; }");
    }

    @Test
    void mutexStillWorksInsideItsOwnDomain() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define class Counter as
                  pub let mut int count = 41;
                end
                pub routine main(): int {
                  val Mutex<Counter> m = Mutex.new(new Counter());
                  val g = m.lock();
                  val int v = g.count;
                  g.release();
                  return v + 1;
                }
                """)));
    }
}
