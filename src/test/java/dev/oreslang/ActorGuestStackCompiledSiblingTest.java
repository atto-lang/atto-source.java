package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-backed two-actor logical stack-fault regression.
 * Unlike a host-only ActorRuntime model this executes actual Atto source.
 * This is NOT JVM/native stack recovery, a supervisor restart, or proof of
 * separate physical actor frames.
 */
@Timeout(30)
final class ActorGuestStackCompiledSiblingTest {
    @Test
    void recursiveActorFailsWithControlledDepthBudgetAfterSiblingReplies()
            throws Exception {
        String program = """
                define actor Overflow as
                  pub descend(int n): int {
                    if n == 0; do
                      return 0;
                    fi
                    return 1 + self.descend(n - 1);
                  }

                  receive(ActorMail<String> mail): void {
                    self.send("entered");
                    val int ignored = self.descend(1000);
                    stdio.stdout.write("UNEXPECTED");
                    self.end();
                    return;
                  }
                end

                define actor Witness as
                  receive(ActorMail<String> mail): void {
                    self.send(42);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val doomed = spawn Overflow();
                  val sibling = spawn Witness();
                  await doomed.get_ready();
                  await sibling.get_ready();
                  doomed.send("start");
                  val entered = (await doomed.outputs.next()).value.unwrap();
                  stdio.stdout.write(entered.value);
                  stdio.stdout.write("|");
                  sibling.send("alive");
                  val surviving = (await sibling.outputs.next()).value.unwrap();
                  stdio.stdout.write(surviving.value);
                  await sibling.get_done();
                  stdio.stdout.write("|SIBLING_DONE");
                  await doomed.get_done();
                  stdio.stdout.write("|UNEXPECTED_DONE");
                  return;
                }
                """;
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                        OresLanguage.ID, program, "actor-depth-sibling.atto")
                .mimeType(OresLanguage.MIME_TYPE)
                .build();
        RuntimeException failed;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(stdout)
                .err(stderr)
                .build()) {
            failed = assertThrows(RuntimeException.class, () -> context.eval(source),
                    "the failed actor's get_done must not complete successfully");
        }
        assertEquals("entered|42|SIBLING_DONE",
                stdout.toString(StandardCharsets.UTF_8),
                "the sibling must complete before the recursive actor's failure propagates");
        String details = failed.toString() + " " + stderr.toString(StandardCharsets.UTF_8);
        for (Throwable cause = failed.getCause(); cause != null; cause = cause.getCause()) {
            details += " " + cause;
        }
        assertTrue(details.contains("guest call depth exceeded")
                        || details.contains("GuestCallDepthExceededException"),
                "a bounded guest-frame failure must propagate, not a generic exception: " + details);
        assertFalse(details.contains("StackOverflowError"),
                "do not treat native JVM stack exhaustion as actor-recoverable");
    }
}
