package dev.oreslang;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiled Oreslang guest regression: STOP while receive owns a registered
 * deferred/finally cleanup. Current interim contract is an explicit failed
 * get_done(), never a false claim that cleanup ran.
 *
 * Follow-on work must replace this incomplete receipt with an authorized
 * cleanup-only CONTROL unwind and then update this expected behavior.
 */
@Timeout(20)
final class SourceActorIncompleteCleanupGuestTest {
    private static String execute(String source) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, source, "actor-cancel-cleanup.ores")
                    .mimeType(OresLanguage.MIME_TYPE).build());
        }
        return out.toString(StandardCharsets.UTF_8).trim().replace("\r", "");
    }

    private static String program(String prefix, String suffix) {
        return """
                define serial actor Worker as
                  suspends receive(ActorMail<String> mail): void {
                    val Channel<int> stuck = Channel.new<int>(0);
                """ + prefix + """
                    self.send("entered");
                    val int ignored = await (nb readch stuck);
                """ + suffix + """
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("go");
                  val entered = (await worker.outputs.next()).value.unwrap();
                  stdio.println(entered);
                  worker.stop();
                  try {
                    await worker.get_done();
                    stdio.println("false-success");
                  } catch (err) {
                    stdio.println("cleanup-incomplete");
                  } finally {
                    stdio.println("root-finished");
                  }
                  return;
                }
                """;
    }

    @Test
    void suspendedSourceDeferNeverYieldsFalseSuccessfulDone() throws Exception {
        assertEquals("entered\ncleanup-incomplete\nroot-finished",
                execute(program("defer stdio.println(\"defer-ran\");\n",
                        "")));
    }

    @Test
    void suspendedSourceFinallyNeverYieldsFalseSuccessfulDone() throws Exception {
        String original = program("try {\n", "} catch (e) { } finally { stdio.println(\"finally-ran\"); }\n");
        assertEquals("entered\ncleanup-incomplete\nroot-finished", execute(original));
    }
}
