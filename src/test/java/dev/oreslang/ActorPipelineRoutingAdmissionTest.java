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
    void unsupportedSourceRoutesFailWithSpecificCompilerDiagnostic() {
        for (String method : BUILTINS) {
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
            assertTrue(error.getMessage().contains("self." + method
                    + " requires compiled typed pipeline actor routing"),
                    method + ": " + error.getMessage());
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
