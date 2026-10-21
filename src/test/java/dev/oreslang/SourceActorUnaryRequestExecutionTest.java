package dev.oreslang;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Real Oreslang-source unary request/response, not a Java host-only wrapper. */
final class SourceActorUnaryRequestExecutionTest {
    @Test
    void sourceActorRequestsTypedUnaryRunAndAwaitsOnItsOwnSchedulerLane() throws Exception {
        String program = """
                define actor PingServer as
                  run(String request): String {
                    return "pong";
                  }
                end

                define actor Client as
                  receive(ActorMail<ActorRef<PingServer>> mail): void {
                    val server = mail.value;
                    val answer = server.request("ping");
                    val response = await answer;
                    self.send(response);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val server = spawn PingServer();
                  val client = spawn Client();
                  await server.get_ready();
                  await client.get_ready();
                  client.send(server);
                  val result = (await client.outputs.next()).value.unwrap();
                  stdio.stdout.write(result.value);
                  await client.get_done();
                  return;
                }
                """;
        assertEquals("pong", run(program, "source-actor-unary-request.ores"));
    }

    @Test
    void incorrectRequestTypeIsRejectedByClosedWorldTypeChecker() {
        String program = """
                define actor PingServer as
                  run(String request): String { return "pong"; }
                end
                define actor Client as
                  receive(ActorMail<ActorRef<PingServer>> mail): void {
                    val server = mail.value;
                    val invalid = server.request(42);
                    self.end();
                    return;
                  }
                end
                pub async routine main(): void {
                  val s = spawn PingServer();
                  val c = spawn Client();
                  c.send(s);
                  await c.get_done();
                  return;
                }
                """;
        assertThrows(PolyglotException.class,
                () -> run(program, "source-actor-unary-wrong-type.ores"));
    }

    private static String run(String sourceText, String name) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, sourceText, name)
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(source);
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
