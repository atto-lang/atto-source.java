package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Sealed pipeline operations are compiler intrinsics, never ordinary actor methods. */
final class ActorPipelineRoutingAdmissionTest {
    private static final String[] BUILTINS = {
        "forward", "forward_all", "forward_round_robin", "forward_to",
        "forward_by_key", "backward", "reply"
    };

    @Test
    void authenticatedSingleRouteIntrinsicsTypeCheckOnSharedActors() {
        String[] calls = {
            "self.forward(mail.value);",
            "self.forward_round_robin(mail.value);",
            "self.forward_to(\"worker\", mail.value);",
            "self.forward_by_key(\"customer-7\", mail.value);",
            "self.backward(mail.value);",
            "self.reply(mail.value);"
        };
        for (String call : calls) {
            String program = """
                    define actor Worker as
                      receive(ActorMail<int> mail): void {
                        %s
                        return;
                      }
                    end
                    """.formatted(call);
            assertDoesNotThrow(
                    () -> TypeChecker.check(Parser.parse(program)), call);
        }
    }

    @Test
    void fanoutReceiptsRemainFailClosed() {
        String[] calls = {
            "self.forward_all(mail.value);"
        };
        for (String call : calls) {
            String program = """
                    define actor Worker as
                      receive(ActorMail<int> mail): void {
                        %s
                        return;
                      }
                    end
                    """.formatted(call);
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(program)), call);
            assertTrue(error.getMessage().contains("source lowering remains fail-closed"),
                    call + ": " + error.getMessage());
        }
    }

    @Test
    void pipelineSelectorsRequireStringsAndPrivateActorsRemainDenied() {
        String badSelector = """
                define actor Worker as
                  receive(ActorMail<int> mail): void {
                    self.forward_to(42, mail.value);
                    return;
                  }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(badSelector)));

        String privateRoute = """
                define isoactor Worker as
                  receive(ActorMail<int> mail): void {
                    self.forward(mail.value);
                    return;
                  }
                end
                """;
        IllegalArgumentException denied = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(privateRoute)));
        assertTrue(denied.getMessage().contains("requires a SHARED actor routed receive"));
    }

    @Test
    void actorSelfAliasesNeverBecomeRoutingAuthority() {
        String[] calls = {
            "alias.forward(mail.value);",
            "alias.forward_all(mail.value);",
            "alias.forward_round_robin(mail.value);",
            "alias.forward_to(\"worker\", mail.value);",
            "alias.forward_by_key(\"key\", mail.value);",
            "alias.backward(mail.value);",
            "alias.reply(mail.value);"
        };
        for (String call : calls) {
            String program = """
                    define actor Worker as
                      receive(ActorMail<int> mail): void {
                        val alias = self;
                        %s
                        return;
                      }
                    end
                    """.formatted(call);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(program)), call);
        }
    }

    @Test
    void sealedPipelineIntrinsicsCannotBeOverriddenByUserActorMethods() {
        for (String name : BUILTINS) {
            String program = """
                    define actor Worker as
                      %s(int value): void {
                        return;
                      }
                    end
                    """.formatted(name);
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(program)), name);
            assertTrue(error.getMessage().contains(
                    "reserved by the non-overridable actor transport/lifecycle API"),
                    name + ": " + error.getMessage());
        }
    }
}
