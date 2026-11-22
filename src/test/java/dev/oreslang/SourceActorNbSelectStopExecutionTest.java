package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end source proof that detached static select is actor-owned:
 * STOP must revoke the pending select before DONE, and no detached arm may
 * execute after the actor has been stopped.
 */
final class SourceActorNbSelectStopExecutionTest {
    @Test
    @Timeout(15)
    void nbSelectOverIsRevokedBeforeActorDoneAndNeverRunsAfterStop()
            throws Exception {
        String program = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    val Channel<int> gate = Channel.new<int>(0);

                    self.send("armed");
                    nb select over
                      when readch gate: int value -> {
                        self.send("selected");
                      }
                    end
                    self.send("returned");
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("go");

                  val armed = (await worker.outputs.next()).value.unwrap();
                  val returned = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(armed.value);
                  stdio.stdout.write("|");
                  stdio.stdout.write(returned.value);

                  worker.stop();
                  await worker.get_done();

                  val terminal = await worker.outputs.next();
                  stdio.stdout.write("|done:");
                  stdio.stdout.write(terminal.done);
                  return;
                }
                """;

        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals(
                "armed|returned|done:true",
                run(program),
                "pending nb select over must not survive STOP or emit its arm after DONE");
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(
                            OresLanguage.ID,
                            program,
                            "source-actor-nb-select-over-stop.ores")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
