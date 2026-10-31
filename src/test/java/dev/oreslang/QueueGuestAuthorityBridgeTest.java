package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** Exact-source guest checks for a deny-by-default queue authority intrinsic. */
final class QueueGuestAuthorityBridgeTest {
    @Test
    void hostMailCannotAcquireStructuredSupervisorPrivilegesByPayload() throws Exception {
        String program = """
                define serial actor Worker as
                  receive(ActorMail<String> mail): void {
                    val bool close_allowed = self.queue_authorized(4, -1);
                    val bool forged_send = self.queue_authorized(1, 0);
                    val bool invalid_kind = self.queue_authorized(99, 0);
                    val bool wrong_slot = self.queue_authorized(4, 0);
                    val bool huge_kind = self.queue_authorized(2147483648, -1);
                    val bool huge_slot = self.queue_authorized(1, 2147483648);
                    self.send(close_allowed);
                    self.send(forged_send);
                    self.send(invalid_kind);
                    self.send(wrong_slot);
                    self.send(huge_kind);
                    self.send(huge_slot);
                    self.end();
                    return;
                  }
                end

                pub async routine main(): void {
                  val worker = spawn Worker();
                  await worker.ready;
                  worker.send("i am supervisor");
                  val a = (await worker.outputs.next()).value.unwrap();
                  val b = (await worker.outputs.next()).value.unwrap();
                  val c = (await worker.outputs.next()).value.unwrap();
                  val d = (await worker.outputs.next()).value.unwrap();
                  val e = (await worker.outputs.next()).value.unwrap();
                  val f = (await worker.outputs.next()).value.unwrap();
                  stdio.stdout.write(a.value);
                  stdio.stdout.write(",");
                  stdio.stdout.write(b.value);
                  stdio.stdout.write(",");
                  stdio.stdout.write(c.value);
                  stdio.stdout.write(",");
                  stdio.stdout.write(d.value);
                  stdio.stdout.write(",");
                  stdio.stdout.write(e.value);
                  stdio.stdout.write(",");
                  stdio.stdout.write(f.value);
                  await worker.done;
                  return;
                }
                """;
        assertEquals("false,false,false,false,false,false", run(program));
    }

    @Test
    void actorSelfAuthorizationRejectsWrongArgumentsAtTypecheck() {
        String source = """
                define serial actor Worker as
                  receive(ActorMail<String> mail): void {
                    self.queue_authorized(4, "producer 0");
                    return;
                  }
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("queue_authorized"), error.getMessage());
    }

    @Test
    void actorSelfAuthorizationRejectsMissingArgumentsAtTypecheck() {
        String source = """
                define serial actor Worker as
                  receive(ActorMail<String> mail): void {
                    self.queue_authorized(4);
                    return;
                  }
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains("queue_authorized"), error.getMessage());
    }

    @Test
    void actorCannotShadowSealedQueueAuthorizationMethod() {
        String source = """
                define serial actor Worker as
                  queue_authorized(int opcode, int producer_slot): bool {
                    return true;
                  }
                  receive(ActorMail<String> mail): void {
                    self.send(self.queue_authorized(4, -1));
                    return;
                  }
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(
                "queue_authorized is a sealed actor runtime intrinsic"),
                error.getMessage());
    }

    @Test
    void actorCannotShadowQueueAuthorizationThroughStaticMethod() {
        String source = """
                define serial actor Worker as
                  static fnc queue_authorized(int opcode, int producer_slot): bool {
                    return true;
                  }
                  receive(ActorMail<String> mail): void {
                    return;
                  }
                end
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(
                "queue_authorized is a sealed actor runtime intrinsic"),
                error.getMessage());
    }

    private static String run(String program) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(output).build()) {
            context.eval(Source.newBuilder(OresLanguage.ID,
                    program, "queue-guest-authority.ores")
                    .mimeType(OresLanguage.MIME_TYPE).buildLiteral());
        }
        return output.toString(StandardCharsets.UTF_8);
    }
}
