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
    void sourceActorRefPayloadFailsClosedUntilLocalCapabilityTransferExists() {
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Supervisor as
                          receive(ActorMail<ActorRef<Supervisor>> mail): void { return; }
                        end
                        """)));
        assertTrue(rejected.getMessage().contains("proven local Sendable capability transport"),
                rejected.getMessage());
    }

    @Test
    void ordinaryOwnedDataRemainsSendableToLocalActor() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Supervisor as
                  receive(ActorMail<Option<String>> mail): void { return; }
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
        assertTrue(nested.getMessage().contains("ActorMail/ActorOutput envelope"), nested.getMessage());
    }

    @Test
    void unprovenNominalTypeIsRejectedRecursively() {
        IllegalArgumentException direct = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<UnresolvedPayload> mail): void { return; }
                        end
                        """)));
        assertTrue(direct.getMessage().contains("no proven Sendable contract"), direct.getMessage());

        IllegalArgumentException nested = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<Option<UnresolvedPayload>> mail): void { return; }
                        end
                        """)));
        assertTrue(nested.getMessage().contains("no proven Sendable contract"), nested.getMessage());
    }

    @Test
    void runtimeOutputEnvelopeAndStreamAreNotOrdinaryMailboxPayloads() {
        IllegalArgumentException output = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<ActorOutput<int>> mail): void { return; }
                        end
                        """)));
        assertTrue(output.getMessage().contains("ActorMail/ActorOutput envelope"), output.getMessage());

        IllegalArgumentException stream = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<ActorOutputStream<int>> mail): void { return; }
                        end
                        """)));
        assertTrue(stream.getMessage().contains("ActorOutputStream runtime capability"),
                stream.getMessage());
    }

    @Test
    void booleanSingletonTypesCanBeTransferredAsImmutableData() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<true> mail): void { return; }
                end
                """)));
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<false> mail): void { return; }
                end
                """)));
    }

    @Test
    void borrowedOrNegativeTypesCannotAssertSendableProof() {
        IllegalArgumentException negative = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<!int> mail): void { return; }
                        end
                        """)));
        assertTrue(negative.getMessage().contains("not actor-boundary sendable")
                        || negative.getMessage().contains("concrete owned/sendable"),
                negative.getMessage());

        IllegalArgumentException borrowed = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<Option<borrow int>> mail): void { return; }
                        end
                        """)));
        assertTrue(borrowed.getMessage().contains("concrete owned/sendable"),
                borrowed.getMessage());
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
        assertTrue(option.getMessage().contains("Option"), option.getMessage());

        IllegalArgumentException result = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Supervisor as
                          receive(ActorMail<Result<int>> mail): void { return; }
                        end
                        """)));
        assertTrue(result.getMessage().contains("Result"), result.getMessage());
    }
}
