package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Static Serializable actor-boundary admission. These tests cover the closed
 * built-in schema subset only. User-authored @Serializable codec derivation,
 * enum/pattern domains and portable wire encoding are separate pending work.
 */
final class ActorMessageSerializableContractTest {
    @Test
    void acceptsPrimitiveStringAndRecursiveBuiltInCollectionMessages() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<String> mail): void { return; }
                end
                pub routine main(): void {
                  val worker = spawn Worker();
                  worker.send("foo");
                  return;
                }
                """)));

        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<List<string>> mail): void { return; }
                end
                pub routine main(): void {
                  val worker = spawn Worker();
                  worker.send(["foo", "bar"]);
                  return;
                }
                """)));
    }

    @Test
    void rejectsRuntimeCapabilityPayloadEvenWhenFormerlySendable() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<ActorId> mail): void { return; }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("Serializable"), failure.getMessage());
    }

    @Test
    void rejectsCapabilitiesNestedInsideCollectionPayload() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<List<ActorId>> mail): void { return; }
                        end
                        """)));
        assertTrue(failure.getMessage().contains("Serializable"), failure.getMessage());
    }

    @Test
    void rejectsClassInstancesWithoutGeneratedPortableCodecs() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define class Payload as
                          value: string
                        end
                        define actor Worker as
                          receive(ActorMail<Payload> mail): void { return; }
                        end
                        """)));
        // Existing actor lexical isolation rules may reject a same-file class
        // even earlier than the required serialization gate.
        assertTrue(failure.getMessage().contains("Serializable")
                        || failure.getMessage().contains("actor")
                        || failure.getMessage().contains("data-only"),
                failure.getMessage());
    }

    @Test
    void rejectsUnknownOpaqueOrExecutablePayloadTypes() {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<Fnc<void>> mail): void { return; }
                end
                """)));
    }
}
