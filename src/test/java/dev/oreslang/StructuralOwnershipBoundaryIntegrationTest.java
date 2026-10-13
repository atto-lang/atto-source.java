package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Distinguishes immutable owned record data from actual loan provenance. */
final class StructuralOwnershipBoundaryIntegrationTest {
    @Test
    void ownedReadonlyValueIsNotABorrowedParameter() {
        String source = """
                fnc keep(record{foo: int} value): int { return value.foo; }
                fnc caller(): int {
                    const owned = infer struct{foo: 5, extra: "ok"};
                    return keep(owned);
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void asyncOwnedRecordContractIsAdmittedByBothStaticPasses() {
        String source = """
                async fnc read(record{foo: int} v): int {
                    return v.foo;
                }
                """;
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void borrowedViewStillCannotBeAnAsyncParameter() {
        String source = """
                async fnc bad(structural {foo: int} v): int {
                    return v.foo;
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void borrowingHiddenInsideOwnedShapeIsNotSendable() {
        String source = """
                async fnc bad(record{foo: &int} v): void { return; }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }
}
