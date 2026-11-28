package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class IndexedNominalTransferProofTest {
    private static final String TYPES = """
        define class Leaf as
          pub let int number = 42;
        end
        fnc consume(Leaf item): int { return item.number; }
        """;
    private static void reject(String body) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(TYPES + body)));
        assertTrue(error.getMessage().contains("E_MOVE_UNPROVEN"), error::getMessage);
    }
    @Test void listElementCannotBecomeOwnedReturn() {
        reject("fnc bad(List<Leaf> items): Leaf { return items[0]; }");
    }
    @Test void arrayElementCannotBecomeOwnedLocal() {
        reject("fnc bad(Array<Leaf> items): int { val alias = items[0]; return alias.number; }");
    }
    @Test void listElementCannotBecomeOwnedArgument() {
        reject("fnc bad(List<Leaf> items): int { return consume(items[0]); }");
    }
    @Test void borrowedListElementCannotBecomeOwnedReturn() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(TYPES
                        + "fnc bad(borrow List<Leaf> items): Leaf { return items[0]; }")));
        assertTrue(error.getMessage().contains("E_BORROW"), error::getMessage);
    }
    @Test void scalarReadThroughBorrowRemainsLegal() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(TYPES
                + "fnc good(borrow List<int> counts): int { return counts[0]; }")));
    }
    @Test void scalarAndNonconsumingNestedReadRemainLegal() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(TYPES
                + "fnc good(List<int> counts, List<Leaf> items): int { return counts[0] + items[0].number; }")));
    }
}
