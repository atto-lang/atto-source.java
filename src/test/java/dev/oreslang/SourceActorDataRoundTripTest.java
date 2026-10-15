package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Executable baseline for compiled supervisor -> actor DATA -> supervisor
 * output. This deliberately uses the existing typed ActorMail transport.
 * It is NOT proof that the new generic ActorMessageCodec<T> is lowered.
 */
@Timeout(20)
final class SourceActorDataRoundTripTest {
    @Test
    void compiledSupervisorActorEchoWithTypedMail() throws Exception {
        String program = """
                define actor Echo as
                  receive(ActorMail<String> mail): void {
                    self.send(mail.value);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val echo = spawn Echo();
                  await echo.get_ready();
                  echo.send("owned-data");
                  val response = (await echo.outputs.next()).value.unwrap();
                  stdio.stdout.write(response.value);
                  await echo.get_done();
                  return;
                }
                """;
        assertEquals("owned-data", evaluate(program));
    }

    private static String evaluate(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(
                OresLanguage.ID, program, "actor-data-round-trip.ores")
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
