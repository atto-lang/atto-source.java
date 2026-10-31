package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source boundary for parent-only ActorRef queue grants.
 * Passing TypeChecker does NOT establish runtime parent authority.
 */
final class QueueParentGrantTypingTest {
    private static final String ACTORS = """
            define actor Queue as
              receive(ActorMail<String> mail): void { return; }
            end
            define actor Producer as
              receive(ActorMail<String> mail): void { return; }
            end
            """;

    @Test
    void grantedProducerMustBeAnActualTypedActorRef() {
        String source = ACTORS + """
                pub routine main(): void {
                  val queue = spawn Queue();
                  val producer = spawn Producer();
                  queue.grant_producer(0, producer);
                  queue.revoke_producer(0);
                  return;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)),
                "syntactic validity is distinct from runtime direct-parent authorization");
    }

    @Test
    void hostMainCannotIssueAQueueProducerGrant() throws Exception {
        String program = ACTORS + """
                pub routine main(): void {
                  val queue = spawn Queue();
                  val producer = spawn Producer();
                  queue.grant_producer(0, producer);
                  return;
                }
                """;
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).build()) {
            PolyglotException failure = assertThrows(PolyglotException.class,
                    () -> context.eval(Source.newBuilder(
                            OresLanguage.ID, program, "queue-parent-grant-denied.ores")
                            .mimeType(OresLanguage.MIME_TYPE).buildLiteral()));
            assertTrue(failure.getMessage().contains(
                    "queue delegation requires a live direct structured child"),
                    failure.getMessage());
        }
    }

    @Test
    void forgedProducerStringDoesNotTypecheck() {
        String source = ACTORS + """
                pub routine main(): void {
                  val queue = spawn Queue();
                  queue.grant_producer(0, "claimed-producer");
                  return;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("grant_producer"),
                failure.getMessage());
    }

    @Test
    void slotMustBeAnInteger() {
        String source = ACTORS + """
                pub routine main(): void {
                  val queue = spawn Queue();
                  val producer = spawn Producer();
                  queue.grant_producer("zero", producer);
                  return;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("grant_producer"),
                failure.getMessage());
    }

    @Test
    void revocationMustReceiveExactlyOneIntegerSlot() {
        String source = ACTORS + """
                pub routine main(): void {
                  val queue = spawn Queue();
                  queue.revoke_producer("zero");
                  return;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("revoke_producer"),
                failure.getMessage());
    }
}
