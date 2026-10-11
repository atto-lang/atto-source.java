package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ProjectedBorrowOwnershipTest {
    private static final String BOX = """
            define class Inner as
              pub let int value = 7;
            end
            define class Outer as
              pub let Inner child = new Inner();
            end
            """;

    @Test
    void nestedFieldBorrowCanBeReadWithoutMovingOwner() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(BOX + """
                fnc read(): int {
                  val Outer parent = new Outer();
                  val view = rt borrow parent.child;
                  return view.value;
                }
                """)));
    }

    @Test
    void projectedLoanBlocksOwnerMutation() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BOX + """
                        fnc bad(): void {
                          let Outer parent = new Outer();
                          val view = rt borrow parent.child;
                          parent.child = new Inner();
                          stdio.println(view.value);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().toLowerCase().contains("borrow"), failure.getMessage());
    }

    @Test
    void temporaryProjectionCannotBeBorrowed() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BOX + """
                        fnc bad(): void {
                          val view = rt borrow (new Outer());
                          stdio.println(view.value);
                          return;
                        }
                        """)));
        assertTrue(failure.getMessage().contains("temporary"), failure.getMessage());
    }
    @Test
    void indexedElementBorrowRetainsElementType() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(BOX + """
                fnc read(): int {
                  val Array<Inner> items = new Array<Inner>();
                  items.add(new Inner());
                  val view = rt borrow items[0];
                  return view.value;
                }
                """)));
    }

    @Test
    void indexedElementBorrowBlocksContainerMutation() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BOX + """
                        fnc bad(): int {
                          val Array<Inner> items = new Array<Inner>();
                          items.add(new Inner());
                          val view = rt borrow items[0];
                          items.add(new Inner());
                          return view.value;
                        }
                        """)));
        assertTrue(failure.getMessage().toLowerCase().contains("borrow"), failure.getMessage());
    }

    @Test
    void projectedBorrowMustNotCrossCooperation() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BOX + """
                        fnc bad(): int {
                          val Outer parent = new Outer();
                          val view = rt borrow parent.child;
                          rt cooperate;
                          return view.value;
                        }
                        """)));
        assertTrue(failure.getMessage().toLowerCase().contains("borrow"), failure.getMessage());
    }

    @Test
    void projectedBorrowOfLocalCannotEscapeViaReturn() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BOX + """
                        fnc bad(): borrow Inner {
                          val Outer parent = new Outer();
                          return rt borrow parent.child;
                        }
                        """)));
        assertTrue(failure.getMessage().toLowerCase().contains("borrow"), failure.getMessage());
    }

}
