package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ACT-BORROW-V1 compiler ownership admission, using canonical rt borrow,
 * rt mut borrow and rt take source. These are source/compiler checks only.
 *
 * They are NOT real actor isolation, generation rollover, AOT or guest proof.
 * The separate Graal/Truffle guest canary executes the currently admitted
 * actor-critical subset and must not be used to certify these semantics.
 */
final class ActorBorrowCompilerAdmissionTest {
    private static String fn(String statements) {
        return "pub fnc work(): void {\n"
                + "  let mut Array<int> entries = new Array<int>();\n"
                + statements + "\n"
                + "  return;\n"
                + "}\n";
    }

    private static void accept(String source) {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(source)));
    }

    private static void deny(String source, String diagnostic) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(diagnostic), error::toString);
    }

    @Test void twoSharedBorrowsCanCoexistWithoutExclusiveWriter() {
        accept(fn("""
                  val first = rt borrow entries;
                  val second = rt borrow entries;
                """));
    }

    @Test void independentExclusiveBorrowIsAllowedWhenNoReadLoanExists() {
        accept(fn("  val writer = rt mut borrow entries;"));
    }

    @Test void exclusiveWriterIsRejectedWhenTwoReadBorrowsAreLive() {
        deny(fn("""
                  val first = rt borrow entries;
                  val second = rt borrow entries;
                  val writer = rt mut borrow entries;
                """), "while another borrow is active");
    }

    @Test void ownershipTransferFailsWhileSharedBorrowIsLive() {
        deny(fn("""
                  val reader = rt borrow entries;
                  val transferred = rt take entries;
                """), "while it is borrowed");
    }

    @Test void sourceOwnerIsUnusableAfterRtTakeMove() {
        deny(fn("""
                  val transferred = rt take entries;
                  val stale = rt borrow entries;
                """), "use of moved value");
    }
}
