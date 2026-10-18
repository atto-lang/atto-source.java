package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NestedFutureAffineReceiverTest {

    @Test
    void cannotFlattenBorrowedFutureOwner() {
        String source = """
                fnc bad(borrow Future<Future<int>> pending): void {
                  val result = pending.flatten();
                  return;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("borrowed Future"),
                failure.getMessage());
    }

    @Test
    void cannotFlattenProjectedFutureOwner() {
        String source = """
                define class Holder as
                  pub let mut Future<Future<int>> pending;
                end

                fnc bad(Holder holder): void {
                  val result = holder.pending.flatten();
                  return;
                }
                """;
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains("projection"),
                failure.getMessage());
    }
}
