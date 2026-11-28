package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class NominalFieldTransferProofTest {
    private static final String TYPES = """
        define class Leaf as
          pub let int number = 42;
        end
        define class Parcel as
          pub let Leaf child = new Leaf();
        end
        fnc consume(Leaf item): int { return item.number; }
        """;
    private static void reject(String body) {
        var error = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(TYPES + body)));
        assertTrue(error.getMessage().contains("E_MOVE_UNPROVEN"), error::getMessage);
    }
    @Test void localFieldCannotManufactureAnOwner() {
        reject("fnc bad(): int { let original = new Parcel(); val alias = original.child; return alias.number; }");
    }
    @Test void returnedFieldCannotManufactureAnOwner() {
        reject("fnc bad(Parcel original): Leaf { return original.child; }");
    }
    @Test void ownedArgumentCannotManufactureAnOwner() {
        reject("fnc bad(Parcel original): int { return consume(original.child); }");
    }
    @Test void scalarReadAndWholeOwnerTransferRemainLegal() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(TYPES
                + "fnc valid(): int { let original = new Parcel(); val moved = take! original; return moved.child.number; }")));
    }
}
