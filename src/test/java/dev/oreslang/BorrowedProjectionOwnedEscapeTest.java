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
    @Test void nestedCopyScalarReadThroughBorrowRemainsLegal() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(TYPES
                + "fnc valid(borrow Wrapper input): int { return input.parcel.child.number; }")));
    }
    @Test void borrowedProjectionCannotEscapeThroughOwnedArgument() {
        reject("fnc consume(Leaf value): int { return value.number; } "
                + "fnc bad(borrow Wrapper input): int { return consume(input.parcel.child); }");
    }
    @Test void scalarSnapshotCanBeUsedToConstructFreshOwnedObject() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(TYPES
                + "define class Snapshot as pub val int number; end "
                + "fnc valid(borrow Wrapper input): Snapshot { "
                + "return new Snapshot(input.parcel.child.number); }")));
    }
    @Test void borrowedArrayElementCannotBecomeOwnedReturn() {
        reject("fnc bad(borrow Array<Leaf> input): Leaf { return input[0]; }");
    }
    @Test void scalarArrayElementReadThroughBorrowRemainsLegal() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(TYPES
                + "fnc valid(borrow Array<int> input): int { return input[0]; }")));
    }

    @Test void structuralScalarIndexRetainsClosedFieldDomain() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(TYPES
                + "define interface PointLike x: int; y: int; end "
                + "fnc read(structural PointLike input, string key): int { return input[key]; }")));
    }
    @Test void structuralMixedIndexCannotBecomeOwned() {
        reject("define interface Mixed x: int; child: Leaf; end "
                + "fnc bad(structural Mixed input, string key): Leaf { return input[key]; }");
    }
    @Test void structuralKnownScalarIndexDoesNotRequireAllFieldsCopy() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(TYPES
                + "define interface Mixed x: int; child: Leaf; end "
                + "fnc read(structural Mixed input): int { return input[\"x\"]; }")));
    }
    @Test void structuralKnownNonCopyIndexCannotBecomeOwned() {
        reject("define interface Mixed x: int; child: Leaf; end "
                + "fnc bad(structural Mixed input): Leaf { return input[\"child\"]; }");
    }
    @Test void structuralUnknownKeyDoesNotBecomeCopy() {
        reject("define interface PointLike x: int; y: int; end "
                + "fnc bad(structural PointLike input): int { return input[\"extra\"]; }");
    }
}
