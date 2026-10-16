package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source-level proofs for local Sendable boundaries. Wire Serialization is a
 * separate, format-specific contract; these tests do not assert codec parity.
 */
final class SendableActorBoundaryTest {

    @Test
    void validLocalActorReferenceMayAppearInTypedSupervisorMailbox() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Child as
                  receive(ActorMail<String> mail): void { return; }
                end
                define actor Supervisor as
                  receive(ActorMail<ActorRef<Child>> mail): void { return; }
                end
                """)));
    }

    @Test
    void actorRefCannotClaimPrimitiveOrUnknownTarget() {
        IllegalArgumentException primitive = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Supervisor as
                          receive(ActorMail<ActorRef<int>> mail): void { return; }
                        end
                        """)));
        assertTrue(primitive.getMessage().contains("ActorRef target"), primitive.getMessage());

        IllegalArgumentException unknown = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Supervisor as
                          receive(ActorMail<ActorRef<NeverDeclaredActor>> mail): void { return; }
                        end
                        """)));
        assertTrue(unknown.getMessage().contains("not a known actor"), unknown.getMessage());
    }

    @Test
    void runtimeOwnedMailEnvelopeCannotBeSentAsUserPayload() {
        IllegalArgumentException nested = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Supervisor as
                          receive(ActorMail<ActorMail<String>> mail): void { return; }
                        end
                        """)));
        assertTrue(nested.getMessage().contains("ActorMail envelope"), nested.getMessage());
    }

    @Test
    void malformedBuiltinSumArityCannotBypassSendability() {
        IllegalArgumentException option = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Supervisor as
                          receive(ActorMail<Option> mail): void { return; }
                        end
                        """)));
        assertTrue(option.getMessage().contains("Option<T>"), option.getMessage());

        IllegalArgumentException result = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Supervisor as
                          receive(ActorMail<Result<int>> mail): void { return; }
                        end
                        """)));
        assertTrue(result.getMessage().contains("Result<T, E>"), result.getMessage());
    }
}
