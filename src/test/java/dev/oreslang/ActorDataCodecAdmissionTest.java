package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Prevents source message approval from outrunning the actual guest codec. */
final class ActorDataCodecAdmissionTest {
    @Test
    void positiveSumTypeSyntaxIsNotMailTransportEvidence() {
        for (String payload : new String[] {
                "Option<int>", "Result<int, String>",
                "List<Option<int>>", "Result<List<int>, String>"
        }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse("""
                            define actor Worker as
                              receive(ActorMail<%s> mail): void { return; }
                            end
                            """.formatted(payload))), payload);
            assertTrue(error.getMessage().contains("verified tagged-value")
                            || error.getMessage().contains("actor"),
                    () -> payload + ": " + error.getMessage());
        }
    }

    @Test
    void invalidNonportableScalarNeverBecomesActorPayload() {
        // 'null' is a primitive/value sentinel with no independent typed
        // portable actor shape; it must not be treated as a generic serializer.
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        define actor Worker as
                          receive(ActorMail<null> mail): void { return; }
                        end
                        """)));
    }

    @Test
    void verifiedSimplePayloadStillCompiles() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                define actor Worker as
                  receive(ActorMail<String> mail): void { return; }
                end
                """)));
    }
}
