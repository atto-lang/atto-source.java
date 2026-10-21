package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An already-retained read borrow forwarded as an implicit structural view
 * remains rooted in its original owner, rather than acquiring a false second
 * ownership identity.
 */
final class StructuralRetainedBorrowForwardingTest {
    private static final String PRELUDE = """
            define class Point as
              pub val int id = 7;
            end
            fnc read(struct{id: int} row): int { return row.id; }
            fnc readKeyword(structural struct{id: int} row): int { return row.id; }
            """;

    @Test
    void forwardingRetainedReadBorrowIsAllowed() {
        String source = PRELUDE + """
                fnc good(): int {
                  val Point point = new Point();
                  val borrow Point view = rt borrow point;
                  return read(view) + readKeyword(view);
                }
                """;
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(source)));
    }

    @Test
    void forwardingBorrowDoesNotPermitMovingUnderlyingOwner() {
        String source = PRELUDE + """
                fnc use(struct{id: int} view, Point taken): int {
                  return view.id + taken.id;
                }
                fnc bad(): int {
                  val Point point = new Point();
                  val borrow Point view = rt borrow point;
                  return use(view, rt take point);
                }
                """;
        assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
    }
}
