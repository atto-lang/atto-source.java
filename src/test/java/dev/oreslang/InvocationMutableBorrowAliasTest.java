package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Borrowed arguments remain simultaneously live until the call returns. */
final class InvocationMutableBorrowAliasTest {
    private static final String BOX = """
            define class Box as
              pub let int value = 0;
            end

            """;

    private static void rejects(String code) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(BOX + code)));
        assertTrue(error.getMessage().contains("overlapping mutable borrow arguments"),
                error.getMessage());
    }

    private static void accepts(String code) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(BOX + code)));
    }

    @Test
    void twoMutableArgumentsCannotBorrowTheSameOwner() {
        rejects("""
                fnc touch(borrow mut Box a, borrow mut Box b): void { return; }
                pub fnc main(): void {
                  val Box box = new Box();
                  touch(rt borrow mut box, rt borrow mut box);
                  return;
                }
                """);
    }

    @Test
    void sharedThenMutableLoanCannotUseSameOwner() {
        rejects("""
                fnc touch(borrow Box a, borrow mut Box b): void { return; }
                pub fnc main(): void {
                  val Box box = new Box();
                  touch(rt borrow box, rt borrow mut box);
                  return;
                }
                """);
    }

    @Test
    void mutableThenSharedLoanCannotUseSameOwner() {
        rejects("""
                fnc touch(borrow mut Box a, borrow Box b): void { return; }
                pub fnc main(): void {
                  val Box box = new Box();
                  touch(rt borrow mut box, rt borrow box);
                  return;
                }
                """);
    }

    @Test
    void twoReadOnlyArgumentsMayShareAnOwner() {
        accepts("""
                fnc look(borrow Box a, borrow Box b): void { return; }
                pub fnc main(): void {
                  val Box box = new Box();
                  look(rt borrow box, rt borrow box);
                  return;
                }
                """);
    }

    @Test
    void twoIndependentMutableOwnersRemainAdmitted() {
        accepts("""
                fnc touch(borrow mut Box a, borrow mut Box b): void { return; }
                pub fnc main(): void {
                  val Box left = new Box();
                  val Box right = new Box();
                  touch(rt borrow mut left, rt borrow mut right);
                  return;
                }
                """);
    }

    @Test
    void byValueMethodUnrelatedToBorrowArgumentsUnaffected() {
        accepts("""
                fnc add(int a, int b): int { return a + b; }
                pub fnc main(): void {
                  val int total = add(1, 2);
                  return;
                }
                """);
    }
}
