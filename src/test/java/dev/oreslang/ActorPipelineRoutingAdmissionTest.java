package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Never silently interpret sealed pipeline operations as ordinary actor methods. */
final class ActorPipelineRoutingAdmissionTest {
    private static final String[] BUILTINS = {
        "forward", "forward_all", "forward_round_robin", "forward_to",
        "forward_by_key", "backward", "reply"
    };

    @Test
    void sealedSingleDestinationRoutesTypeCheckOnlyAsDirectSelfIntrinsics() {
        String program = """
                define actor Worker as
                  receive(ActorMail<int> mail): void {
                    self.forward(mail.value);
                    self.forward_round_robin(mail.value);
                    self.forward_to("worker-a", mail.value);
                    self.forward_by_key("customer-7", mail.value);
                    self.backward(mail.value);
                    return;
                  }
                end
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(program)));
    }

    @Test
    void isolatedActorCannotAcquireCorrelatedPipelineRouting() {
        String program = """
                define isoactor Worker as
                  receive(ActorMail<int> mail): void {
                    self.forward(mail.value);
                  }
                end
                """;
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(program)));
        assertTrue(error.getMessage().contains("requires a SHARED actor"), error.getMessage());
    }

    @Test
    void fanoutReceiptsAndFullPathReplyRemainFailClosed() {
        for (String method : new String[] {"forward_all", "reply"}) {
            String program = """
                    define actor Worker as
                      receive(ActorMail<int> mail): void {
                        self.%s(mail.value);
                      }
                    end
                    """.formatted(method);
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(program)), method);
            assertTrue(error.getMessage().contains("next typed pipeline routing phase"),
                    method + ": " + error.getMessage());
        }
    }

    @Test
    void routedSelectorSignaturesAreCheckedAtCompileTime() {
        String wrongTarget = """
                define actor Worker as
                  receive(ActorMail<int> mail): void {
                    self.forward_to(42, mail.value);
                  }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(wrongTarget)));
        String missingKey = """
                define actor Worker as
                  receive(ActorMail<int> mail): void {
                    self.forward_by_key(mail.value);
                  }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(missingKey)));
    }

    @Test
    void actorSelfAliasesDoNotEnableUnloweredRoutingIntrinsics() {
        // The compiler owns direct self.* intrinsics. Aliased actor values
        // must never acquire the same authority-bearing dispatch path.
        for (String method : BUILTINS) {
            String program = """
                    define actor Worker as
                      receive(ActorMail<int> mail): void {
                        val alias = self;
                        alias.%s(mail.value);
                        return;
                      }
                    end
                    """.formatted(method);
            assertThrows(IllegalArgumentException.class,
                    () -> TypeChecker.check(Parser.parse(program)), method);
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
