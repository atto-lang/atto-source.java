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
                define actor Worker as
                  run(String request): String {
                    return "pong";
                  }

                  receive(ActorMail<ActorRef<Worker>> mail): void {
                    val answer = mail.value.request("ping");
                    val response = await answer;
                    self.send(response);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val server = spawn Worker();
                  val client = spawn Worker();
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
                define actor Worker as
                  run(String request): String { return "pong"; }

                  receive(ActorMail<ActorRef<Worker>> mail): void {
                    val invalid = mail.value.request(42);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val server = spawn Worker();
                  val client = spawn Worker();
                  client.send(server);
                  await client.get_done();
                  return;
                }
                """;
        PolyglotException failure = assertThrows(PolyglotException.class,
                () -> run(program, "source-actor-unary-wrong-type.ores"));
        assertTrue(failure.getMessage().contains("ActorRef.request payload"),
                failure.getMessage());
    }

    @Test
    void nestedFutureResponseIsRejectedBeforeGuestExecution() {
        String program = """
                define actor Worker as
                  run(String request): Future<String> {
                    return Future.completed("unreachable");
                  }
                  receive(ActorMail<ActorRef<Worker>> mail): void {
                    val pending = mail.value.request("ping");
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val server = spawn Worker();
                  val client = spawn Worker();
                  client.send(server);
                  await client.get_done();
                  return;
                }
                """;
        PolyglotException failure = assertThrows(PolyglotException.class,
                () -> run(program, "source-actor-unary-nested-future.ores"));
        assertTrue(failure.getMessage().contains("concrete response, not Future<T>"),
                failure.getMessage());
    }

    @Test
    void suspendingUnaryHandlerFailsClosed() {
        String program = """
                define actor Worker as
                  run(String request): String {
                    rt cooperate;
                    return "pong";
                  }
                  receive(ActorMail<ActorRef<Worker>> mail): void {
                    val response = await mail.value.request("ping");
                    self.send(response);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val server = spawn Worker();
                  val client = spawn Worker();
                  client.send(server);
                  val result = (await client.outputs.next()).value.unwrap();
                  stdio.stdout.write(result.value);
                  await client.get_done();
                  return;
                }
                """;
        PolyglotException failure = assertThrows(PolyglotException.class,
                () -> run(program, "source-actor-unary-suspending-handler.ores"));
        assertTrue(failure.getMessage().contains("unary run(Request) cannot suspend"),
                failure.getMessage());
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
