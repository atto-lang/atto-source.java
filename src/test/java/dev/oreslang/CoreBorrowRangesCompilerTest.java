package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The backend-owned core/borrow_ranges.atto source exposes ONLY range/slot
 * arithmetic. Its booleans do not mint trusted loan provenance or relax the
 * actual compiler's exclusive borrower checks.
 */
final class CoreBorrowRangesCompilerTest {
    private static final String PRELUDE = """
            define class Cell as
              pub let int value = 21;
            end

            fnc write_pair(borrow mut Cell left, borrow mut Cell right) -> int {
              return left.value + right.value;
            }

            fnc read_write_pair(borrow Cell left, borrow mut Cell right) -> int {
              return left.value + right.value;
            }

            fnc alleged_core_loan_proof(): bool {
              return true;
            }

            """;

    private static void compilerAccepts(String source) {
        var parsed = assertDoesNotThrow(() -> Parser.parse(PRELUDE + source));
        assertDoesNotThrow(() -> TypeChecker.check(parsed),
                "integrated compiler must admit distinct, properly released loans");
        assertDoesNotThrow(() -> OwnershipChecker.check(parsed));
    }

    private static void compilerRejects(String source, String expected) {
        var parsed = assertDoesNotThrow(() -> Parser.parse(PRELUDE + source));
        var integrated = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(parsed),
                "integrated compilation cannot bypass ownership checking");
        assertTrue(integrated.getMessage().toLowerCase(java.util.Locale.ROOT)
                        .contains(expected),
                "expected semantic " + expected + " diagnostic; got " +
                        integrated.getMessage());
        var standalone = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(parsed),
                "standalone ownership analysis must agree with integrated compiler");
        assertTrue(standalone.getMessage().toLowerCase(java.util.Locale.ROOT)
                        .contains(expected), standalone.getMessage());
    }

    @Test
    void distinctCoreOwnedLoansAreAdmittedAndReleasedAfterCall() {
        compilerAccepts("""
                fnc good(): int {
                  val Cell first = new Cell();
                  val Cell second = new Cell();
                  val one = write_pair(rt mut borrow first, rt mut borrow second);
                  val two = write_pair(rt mut borrow first, rt mut borrow second);
                  return one + two;
                }
                """);
    }

    @Test
    void coreRangePredicateCannotAuthorizeDuplicateMutableLoan() {
        compilerRejects("""
                fnc bad(): int {
                  val Cell owner = new Cell();
                  val bool claimed_permission = alleged_core_loan_proof();
                  return write_pair(rt mut borrow owner, rt mut borrow owner);
                }
                """, "borrow");
    }

    @Test
    void coreRangePredicateCannotAuthorizeSharedExclusiveAlias() {
        compilerRejects("""
                fnc bad(): int {
                  val Cell owner = new Cell();
                  val bool claimed_permission = alleged_core_loan_proof();
                  return read_write_pair(rt borrow owner, rt mut borrow owner);
                }
                """, "borrow");
    }

    @Test
    void ownerCannotBeReusedAfterMoveDespiteValueLevelPermission() {
        compilerRejects("""
                fnc bad(): int {
                  val Cell original = new Cell();
                  val bool claimed_permission = alleged_core_loan_proof();
                  let Cell moved = rt take original;
                  return original.value;
                }
                """, "moved");
    }
}
