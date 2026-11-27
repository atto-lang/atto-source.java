package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class BorrowedProjectionOwnedEscapeTest {
    private static final String TYPES = """
            define class Leaf as
              pub let int number = 42;
            end
            define class Parcel as
              pub let Leaf child = new Leaf();
            end
            define class Wrapper as
              pub let Parcel parcel = new Parcel();
            end
            """;
    private static void reject(String body) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(TYPES + body)));
        assertTrue(failure.getMessage().contains("E_BORROW"), failure::getMessage);
    }
    @Test void borrowedFieldCannotBecomeOwnedReturn() {
        reject("fnc bad(borrow Parcel input): Leaf { return input.child; }");
    }
    @Test void nestedBorrowedFieldCannotBecomeOwnedBinding() {
        reject("fnc bad(borrow Wrapper input): Leaf { val alias = input.parcel.child; return alias; }");
    }
    @Test void copyScalarReadThroughBorrowRemainsLegal() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(TYPES
                + "fnc valid(borrow Leaf input): int { return input.number; }")));
    }
    @Test void freshOwnedValueRemainsLegal() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(TYPES
                + "fnc valid(): Leaf { return new Leaf(); }")));
    }
}
