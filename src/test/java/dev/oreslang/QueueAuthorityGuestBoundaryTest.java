package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The host QueueCommandAuthority must not become a guest callable surface
 * before a compiler-verified, nonforgeable actor capability bridge exists.
 */
final class QueueAuthorityGuestBoundaryTest {
    @Test
    void guestCannotCallHostQueueAuthorityThroughSelf() {
        String source = """
                define serial actor GuardedQueue as
                    receive(ActorMail<String> mail): void {
                        self.queueCommandAuthority();
                        return;
                    }
                end
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)),
                "host ActorContext authority must remain unavailable to source actors");
    }

    @Test
    void sourceCannotForgeHostAuthorityClass() {
        String source = """
                pub routine main(): void {
                    val authority = new QueueCommandAuthority();
                    return;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)),
                "unbound source constructors must not mint runtime authority");
    }
}
