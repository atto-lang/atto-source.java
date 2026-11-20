package dev.oreslang;

import dev.oreslang.runtime.LogicalStackOverflowException;
import dev.oreslang.runtime.LogicalStackQuota;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guest-source evidence for spec chapter 25 / chapter 18 "Stacks" on the Java
 * backend: guest stack exhaustion is a deterministic, per-logical-stack
 * failure, never a host StackOverflowError that kills a carrier thread.
 */
@Timeout(60)
final class ActorLogicalStackOverflowTest {
    private static final String CAPS = "--ores-capabilities=ACTOR_SPAWN,SHARED_MEMORY,STDOUT";

    @Test
    void overflowingActorFailsAloneWhileSiblingRepliesAndNoCarrierDies() throws Exception {
        List<Throwable> uncaught = new CopyOnWriteArrayList<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> uncaught.add(failure));
        try {
            Run run = run("""
                    define actor Diver as
                      fnc dive(n: int): int {
                        return self.dive(n + 1) + 1;
                      }
                      receive(ActorMail<int> mail): void {
                        self.send(self.dive(mail.value));
                        self.end();
                        return;
                      }
                    end
                    define actor Healthy as
                      receive(ActorMail<int> mail): void {
                        self.send(mail.value + 1);
                        self.end();
                        return;
                      }
                    end
                    pub async routine main(): void {
                      val a = spawn Diver();
                      val b = spawn Healthy();
                      await a.get_ready();
                      await b.get_ready();
                      a.send(0);
                      b.send(41);
                      val got = (await b.outputs.next()).value.unwrap();
                      stdio.stdout.write("healthy=");
                      stdio.stdout.write(got.value);
                      await b.get_done();
                      await a.get_done();
                      stdio.stdout.write("|unreachable");
                      return;
                    }
                    """);
            assertEquals("healthy=42", run.output);
            assertNotNull(run.failure, "awaiting the failed actor reports its failure");
            assertTrue(run.failure.getMessage().contains(LogicalStackOverflowException.class.getName()),
                    run.failure::getMessage);
            // Observers see only the failure type (#1223 snapshot), not its message, so
            // quota-vs-carrier-ceiling is proven by LogicalStackQuotaTest
            // #segmentQuotaIsIndependentOfHowDeepTheCarrierAlreadyIs (carrierCeiling() == false).
            assertFalse(run.failure.getMessage().contains("StackOverflowError"), run.failure::getMessage);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
        assertTrue(uncaught.isEmpty(), () -> "carrier threads died: " + uncaught);
    }

    @Test
    void guestCatchCannotSwallowStackExhaustion() throws Exception {
        Run run = run("""
                fnc dive(n: int): int { return dive(n + 1) + 1; }
                pub fnc main(): void {
                  try { dive(0); } catch (err) { stdio.stdout.write("caught"); }
                  stdio.stdout.write("after");
                  return;
                }
                """);
        assertEquals("", run.output);
        assertNotNull(run.failure);
        assertTrue(run.failure.getMessage().contains("StackOverflow: logical stack"), run.failure::getMessage);
    }

    @Test
    void trapCannotConvertStackExhaustionToNone() throws Exception {
        Run run = run("""
                fnc dive(n: int): int { return dive(n + 1) + 1; }
                trap fnc guarded(): int { return dive(0); }
                pub fnc main(): void {
                  val result = guarded();
                  stdio.stdout.write("after");
                  return;
                }
                """);
        assertEquals("", run.output);
        assertNotNull(run.failure);
        assertTrue(run.failure.getMessage().contains("StackOverflow: logical stack"), run.failure::getMessage);
    }

    @Test
    void deferCleanupRunsOnEveryFrameUsingTheUnwindReserve() throws Exception {
        // Each frame's defer calls a helper, so cleanup needs one extra frame
        // at the deepest point: only the unwind reserve makes that possible.
        Run run = run("""
                fnc note(): void { stdio.stdout.write("c"); return; }
                fnc dive(n: int): int {
                  defer note();
                  return dive(n + 1) + 1;
                }
                pub fnc main(): void {
                  dive(0);
                  return;
                }
                """);
        assertNotNull(run.failure);
        assertTrue(run.failure.getMessage().contains("StackOverflow: logical stack"), run.failure::getMessage);
        // main + (quota - 1) dive frames each ran their defer exactly once.
        assertEquals(LogicalStackQuota.segmentFrames() - 1, run.output.length(),
                "every live frame's defer must run exactly once during overflow unwind");
        assertTrue(run.output.chars().allMatch(ch -> ch == 'c'));
    }

    @Test
    void properTailCallsDoNotConsumeLogicalFrames() throws Exception {
        int depth = LogicalStackQuota.segmentFrames() * 50;
        Run run = run("""
                fnc count(n: int, acc: int): int {
                  if (n == 0) { return acc; } fi
                  return count(n - 1, acc + 1);
                }
                pub fnc main(): void {
                  stdio.stdout.write(count(%d, 0));
                  return;
                }
                """.formatted(depth));
        assertNull(run.failure, () -> String.valueOf(run.failure));
        assertEquals(Integer.toString(depth), run.output);
    }

    @Test
    void recursionWithinQuotaStillCompletes() throws Exception {
        int depth = LogicalStackQuota.segmentFrames() - 8;
        Run run = run("""
                fnc sum(n: int): int {
                  if (n == 0) { return 0; } fi
                  return sum(n - 1) + 1;
                }
                pub fnc main(): void {
                  stdio.stdout.write(sum(%d));
                  return;
                }
                """.formatted(depth));
        assertNull(run.failure, () -> String.valueOf(run.failure));
        assertEquals(Integer.toString(depth), run.output);
    }

    private record Run(String output, PolyglotException failure) { }

    private static Run run(String sourceText) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PolyglotException failure = null;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .arguments(OresLanguage.ID, new String[] {CAPS})
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, sourceText, "logical-stack-overflow.atto")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        } catch (PolyglotException guestFailure) {
            failure = guestFailure;
        }
        return new Run(output.toString(StandardCharsets.UTF_8), failure);
    }
}
