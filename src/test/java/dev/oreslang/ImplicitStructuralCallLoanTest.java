package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * An anonymous structural argument is an implicit readonly loan and must stay
 * live until *all* later invocation arguments have been checked.
 */
final class ImplicitStructuralCallLoanTest {
    private static final String PRELUDE = """
            define class Widget as
              pub val int id = 7;
            end
            """;

    private static void assertOwnershipRejects(String source) {
        var parsed = assertDoesNotThrow(() -> Parser.parse(PRELUDE + source));
        // TypeChecker's integrated path also invokes ownership validation.
        // Both entrypoints must reject the unsafe argument list.
        IllegalArgumentException integrated = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(parsed), source);
        IllegalArgumentException standalone = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(parsed), source);
        assertTrue(integrated.getMessage().toLowerCase(java.util.Locale.ROOT).contains("borrow"),
                integrated.getMessage());
        assertTrue(standalone.getMessage().toLowerCase(java.util.Locale.ROOT).contains("borrow"),
                standalone.getMessage());
    }

    private static void assertOwnershipAccepts(String source) {
        var parsed = assertDoesNotThrow(() -> Parser.parse(PRELUDE + source));
        assertDoesNotThrow(() -> TypeChecker.check(parsed));
        assertDoesNotThrow(() -> OwnershipChecker.check(parsed));
    }

    @Test
    void implicitShapePreventsMoveInLaterArgument() {
        assertOwnershipRejects("""
                fnc inspect(struct{id: int} view, Widget consumed): void { return; }
                fnc bad(): void {
                  val Widget owner = new Widget();
                  inspect(owner, rt take owner);
                }
                """);
    }

    @Test
    void redundantKeywordAlsoPreventsLaterMove() {
        assertOwnershipRejects("""
                fnc inspect(structural struct{id: int} view, Widget consumed): void { return; }
                fnc bad(): void {
                  val Widget owner = new Widget();
                  inspect(owner, rt take owner);
                }
                """);
    }

    @Test
    void nameFirstImplicitShapeAlsoPreventsLaterMove() {
        assertOwnershipRejects("""
                fnc inspect(view: struct{id: int}, Widget consumed): void { return; }
                fnc bad(): void {
                  val Widget owner = new Widget();
                  inspect(owner, rt take owner);
                }
                """);
    }

    @Test
    void explicitReadLoanThenMutableReborrowIsRejected() {
        assertOwnershipRejects("""
                fnc inspect(struct{id: int} view, borrow mut Widget owner): void { return; }
                fnc bad(): void {
                  let Widget owner = new Widget();
                  inspect(owner, rt mut borrow owner);
                }
                """);
    }

    @Test
    void mutableBorrowBeforeStructuralReadIsRejected() {
        assertOwnershipRejects("""
                fnc inspect(borrow mut Widget owner, struct{id: int} view): void { return; }
                fnc bad(): void {
                  let Widget owner = new Widget();
                  inspect(rt mut borrow owner, owner);
                }
                """);
    }

    @Test
    void multipleSharedStructuralArgumentsCanBorrowSameObject() {
        assertOwnershipAccepts("""
                fnc inspect(struct{id: int} a, structural struct{id: int} b): int {
                  return a.id + b.id;
                }
                fnc good(): int {
                  val Widget owner = new Widget();
                  return inspect(owner, owner);
                }
                """);
    }

    @Test
    void ReadLoanIsReleasedAfterInvocation() {
        assertOwnershipAccepts("""
                fnc inspect(struct{id: int} view): int { return view.id; }
                fnc good(): int {
                  val Widget owner = new Widget();
                  val int observed = inspect(owner);
                  return observed;
                }
                """);
    }

    @Test
    void structuralReadOfOneOwnerAllowsTransferOfADifferentOwner() {
        assertOwnershipAccepts("""
                fnc readAndConsume(struct{id: int} view, Widget owned): int {
                  return view.id + owned.id;
                }
                fnc good(): int {
                  val Widget reader = new Widget();
                  val Widget transferred = new Widget();
                  return readAndConsume(reader, rt take transferred);
                }
                """);
    }

}
