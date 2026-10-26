package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source-level, compile-time rejection of loan capabilities without a
 * representable lifetime. No guest interpreter is needed for these proofs.
 */
final class StaticBorrowEscapeBoundaryTest {
    private static void rejects(String source, String expected) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(expected), error::getMessage);
    }

    @Test
    void classAndActorStorageCannotHideNestedBorrowCapabilities() {
        rejects("""
                define class Unsafe as
                  pub let Option<borrow int> hidden;
                end
                """, "nested borrow");
        rejects("""
                define class Unsafe as
                  pub let List<Option<borrow int>> hidden;
                end
                """, "nested borrow");
    }

    @Test
    void generatorCannotProduceLoanCreatedAtYieldExpression() {
        rejects("""
                fnc gen* leaked(): Iterator<int> {
                  let int value = 7;
                  yield rt borrow value;
                  return;
                }
                """, "cannot yield borrowed references");
    }

    @Test
    void generatorCannotDelegateBorrowBearingElements() {
        rejects("""
                fnc gen* leaked(Iterator<borrow int> items): Iterator<int> {
                  yield* items;
                  return;
                }
                """, "cannot yield borrowed references");
    }

    @Test
    void generatorCannotSuspendWithBorrowHiddenInsideParameter() {
        rejects("""
                fnc gen* leaked(Option<borrow int> wrapped): Iterator<int> {
                  yield 1;
                  return;
                }
                """, "cannot yield while a borrow is live");
    }

    @Test
    void nestedBorrowedReturnCannotEscapeAsOwnedWrapper() {
        rejects("""
                fnc leaked(Option<borrow int> borrowed): Option<borrow int> {
                  return borrowed;
                }
                """, "cannot return a nested borrowed reference");
    }

    @Test
    void suspensionCannotHideBorrowedParameterInsideOwnedWrapper() {
        rejects("""
                fnc leaked(Option<borrow int> borrowed): void {
                  rt cooperate;
                  return;
                }
                """, "ordinary borrow is live");
    }

    @Test
    void ownedValuesRemainLegalThroughEachStaticBoundary() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse("""
                define class Safe as
                  pub let Option<int> value;
                end
                fnc gen* values(): Iterator<int> {
                  yield 1;
                  return;
                }
                fnc pass(Option<int> value): Option<int> {
                  return value;
                }
                fnc suspendOwned(Option<int> value): void {
                  rt cooperate;
                  return;
                }
                """)));
    }

    @Test
    void compilerEntrypointRejectsFreshLoanYieldBeforeExecution() {
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.parseAndTypeCheck("""
                fnc gen* leaked(): Iterator<int> {
                  let int value = 7;
                  yield rt borrow value;
                  return;
                }
                """));
    }
}
