package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** An entire argument list holds its loans until the call is complete. */
final class CallArgumentBorrowExclusivityTest {
    private static final String PRELUDE = """
            define class Cell as
              pub let int value = 1;
            end

            fnc writePair(borrow mut Cell a, borrow mut Cell b) -> int {
              return a.value + b.value;
            }

            fnc readPair(borrow Cell a, borrow Cell b) -> int {
              return a.value + b.value;
            }

            fnc readWritePair(borrow Cell a, borrow mut Cell b) -> int {
              return a.value + b.value;
            }

            """;

    private static void accepts(String function) {
        var parsed = assertDoesNotThrow(() -> Parser.parse(PRELUDE + function));
        assertDoesNotThrow(() -> TypeChecker.check(parsed), "typecheck");
        assertDoesNotThrow(() -> OwnershipChecker.check(parsed), "ownership");
    }

    private static void rejectsAsConflictingLoan(String function) {
        var parsed = assertDoesNotThrow(() -> Parser.parse(PRELUDE + function));
        // TypeChecker invokes ownership checking on the integrated compiler
        // path. Both entrypoints must reject; a typecheck "success" here
        // would indicate that the source compilation path can bypass loans.
        IllegalArgumentException integrated = assertThrows(
                IllegalArgumentException.class, () -> TypeChecker.check(parsed));
        assertBorrowConflict(integrated);
        IllegalArgumentException standalone = assertThrows(
                IllegalArgumentException.class, () -> OwnershipChecker.check(parsed));
        assertBorrowConflict(standalone);
    }

    private static void assertBorrowConflict(IllegalArgumentException error) {
        String message = error.getMessage().toLowerCase(java.util.Locale.ROOT);
        assertTrue(message.contains("borrow")
                        && (message.contains("mutably borrowed")
                            || message.contains("another borrow is active")
                            || message.contains("overlapping mutable borrow arguments")),
                error.getMessage());
    }

    @Test
    void rejectsDuplicateExclusiveBorrowWithinOneCall() {
        rejectsAsConflictingLoan("""
                fnc bad(): int {
                  val Cell cell = new Cell();
                  return writePair(rt borrow mut cell, rt borrow mut cell);
                }
                """);
    }

    @Test
    void rejectsSharedThenExclusiveBorrowWithinOneCall() {
        rejectsAsConflictingLoan("""
                fnc bad(): int {
                  val Cell cell = new Cell();
                  return readWritePair(rt borrow cell, rt borrow mut cell);
                }
                """);
    }

    @Test
    void rejectsDuplicateMutableParameterAliasWithinOneCall() {
        rejectsAsConflictingLoan("""
                fnc forward(borrow mut Cell ref): int {
                  return writePair(ref, ref);
                }
                """);
    }

    @Test
    void permitsDistinctExclusiveOwnersAndReleasesOnReturn() {
        accepts("""
                fnc good(): int {
                  val Cell left = new Cell();
                  val Cell right = new Cell();
                  val first = writePair(rt borrow mut left, rt borrow mut right);
                  val second = writePair(rt borrow mut left, rt borrow mut right);
                  return first + second;
                }
                """);
    }

    @Test
    void permitsMultipleSharedLoansAndSubsequentExclusiveBorrow() {
        accepts("""
                fnc good(): int {
                  val Cell cell = new Cell();
                  val result = readPair(rt borrow cell, rt borrow cell);
                  val other = new Cell();
                  return writePair(rt borrow mut cell, rt borrow mut other) + result;
                }
                """);
    }
}
