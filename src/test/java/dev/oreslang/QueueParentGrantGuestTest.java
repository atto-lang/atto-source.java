package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Positive source-guest test for structured-parent producer grant/revoke.
 *
 * This proves the source syntax reaches the runtime bridge from the actual
 * parent actor turn. It does not prove queue authorization+mutation atomicity,
 * output reservation or successful queue message processing.
 */
final class QueueParentGrantGuestTest {
    private static final String PROGRAM = """
            define serial actor GrantQueue as
              receive(ActorMail<String> mail): void {
                return;
              }
            end

            define serial actor GrantProducer as
              receive(ActorMail<String> mail): void {
                return;
              }
            end

            define serial actor GrantSupervisor as
              receive(ActorMail<String> mail): void {
                val queue = spawn GrantQueue();
                val producer = spawn GrantProducer();

                queue.grant_producer(0, producer);
                self.send("grant-ok");

                queue.revoke_producer(0);
                self.send("revoke-ok");

                self.end();
                return;
              }
            end

            pub async routine main(): void {
              val supervisor = spawn GrantSupervisor();
              await supervisor.ready;
              supervisor.send("run");

              val granted = (await supervisor.outputs.next()).value.unwrap();
              val revoked = (await supervisor.outputs.next()).value.unwrap();
              stdio.stdout.write(granted.value);
              stdio.stdout.write(",");
              stdio.stdout.write(revoked.value);
              await supervisor.done;
              return;
            }
            """;

    @Test
    void directStructuredParentCanGrantAndRevokeProducerFromGuestCode()
            throws Exception {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(PROGRAM)));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false)
                .out(output)
                .build()) {
            context.eval(Source.newBuilder(OresLanguage.ID, PROGRAM,
                    "queue-parent-grant-guest.atto")
                    .mimeType(OresLanguage.MIME_TYPE)
                    .buildLiteral());
        }
        assertEquals("grant-ok,revoke-ok", output.toString(StandardCharsets.UTF_8));
    }
}
