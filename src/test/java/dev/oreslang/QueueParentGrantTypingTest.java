package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

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
