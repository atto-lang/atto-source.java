package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorTaskBlockGuestExecutionTest {

    @Test
    void actorTaskBlockWithOwnedScalarCaptureCanAwaitAndReply() throws Exception {
        String program = """
                define actor Worker as
                  receive(ActorMail<String> mail): void {
                    const int base = 40;
                    let Future<int> child = task {
                      return base + 2;
                    };
                    self.send(await child);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.get_ready();
                  worker.send("go");
                  val reply = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(reply.value);
                  await worker.get_done();
                  return;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program));
        assertEquals("42", run(program));
    }

    @Test
    void actorTaskCannotCaptureReceiverOrSealedActorState() {
        String program = """
                define actor Worker as
                  let int value = 42;
                  receive(ActorMail<String> mail): void {
                    let Future<int> child = task {
                      return self.value;
                    };
                    return;
                  }
                end
                pub async routine main(): void {
                  return;
                }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("actor task")
                        || error.getMessage().contains("actor self")
                        || error.getMessage().contains("independent task"),
                error::getMessage);
    }

    @Test
    void untrustedTaskBlockFailsClosedUntilPhysicalQuotaAccounting() {
        String program = """
                define untrusted actor Worker as
                  receive(ActorMail<int> mail): void {
                    let Future<int> child = task {
                      return 42;
                    };
                    return;
                  }
                end
                pub async routine main(): void {
                  return;
                }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(program));
        assertTrue(error.getMessage().contains("quota-aware tracked-child"),
                error::getMessage);
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, program, "actor-task-block.atto")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral());
        } catch (PolyglotException failure) {
            throw failure;
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
