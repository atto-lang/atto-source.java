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
                """, "borrowed references cannot be yielded");
    }

    @Test
    void generatorCannotDelegateBorrowBearingElements() {
        // An Iterator<borrow T> parameter is a live borrowed aggregate, so
        // generator-frame admission rejects it before yield* is evaluated.
        // The yielded-element guard separately protects expression-produced
        // iterators that were not already visible as live locals.
        rejects("""
                fnc gen* leaked(Iterator<borrow int> items): Iterator<int> {
                  yield* items;
                  return;
                }
                """, "cannot yield while a borrow is live");
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
    void nestedLoansCannotBecomeOwnedLocalBindings() {
        rejects("""
                fnc bad(Option<borrow int> borrowed): void {
                  val alias = borrowed;
                  return;
                }
                """, "cannot retain a nested borrowed reference");
    }

    @Test
    void ownedCallsCannotConsumeNestedBorrowCapabilities() {
        rejects("""
                fnc consume(Option<borrow int> value): void { return; }
                fnc bad(Option<borrow int> borrowed): void {
                  consume(borrowed);
                  return;
                }
                """, "cannot consume a borrowed value as an owned parameter");
    }

    @Test
    void constructedObjectsCannotStoreBorrowedArguments() {
        rejects("""
                define class Box as
                  pub val int number;
                end
                fnc bad(): void {
                  let int original = 1;
                  val box = new Box(rt borrow original);
                  return;
                }
                """, "borrowed constructor arguments");
    }

    @Test
    void constructedGenericInstancesCannotHideBorrowedTypeArguments() {
        rejects("""
                define class Box<T> as
                  pub val T value;
                end
                fnc bad(): void {
                  val box = new Box<borrow int>();
                  return;
                }
                """, "cannot contain borrowed generic arguments");
    }

    @Test
    void sumConstructorsCannotWrapNestedBorrowCapabilities() {
        rejects("""
                fnc bad(Option<borrow int> borrowed): void {
                  Some(borrowed);
                  return;
                }
                """, "cannot store a borrow in an owned sum value");
    }

    @Test
    void ordinaryConstructorAndByValueCallsRemainAllowed() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse("""
                define class Box as
                  pub val int value;
                end
                fnc consume(int value): void { return; }
                fnc valid(): void {
                  val box = new Box(7);
                  consume(42);
                  return;
                }
                """)));
    }


    @Test
    void escapingClosureCannotCaptureTransitiveBorrowFromParameter() {
        rejects("""
                fnc bad(Option<borrow int> wrapped): void {
                  val callback = || -> { return wrapped; };
                  return;
                }
                """, "closure cannot capture borrowed value");
    }

    @Test
    void expressionBodyLambdaCannotReturnBorrowOfCapturedLocal() {
        rejects("""
                fnc bad(): void {
                  let int owner = 7;
                  val callback = || -> rt borrow owner;
                  return;
                }
                """, "cannot return borrow rooted in");
    }

    @Test
    void explicitTakeCopyAndShareCannotLaunderNestedBorrow() {
        rejects("""
                fnc bad(Option<borrow int> wrapped): void {
                  rt take wrapped;
                  return;
                }
                """, "rt take requires owned data");
        rejects("""
                fnc bad(Option<borrow int> wrapped): void {
                  rt copy wrapped;
                  return;
                }
                """, "rt copy cannot duplicate a borrowed capability");
        rejects("""
                fnc bad(Option<borrow int> wrapped): void {
                  rt share wrapped;
                  return;
                }
                """, "rt share cannot publish a borrowed capability");
    }

    @Test
    void ownedClosureCaptureAndExpressionReturnRemainLegal() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse("""
                fnc valid(Option<int> wrapped): void {
                  val callback = || -> { return wrapped; };
                  val scalar = || -> 7;
                  return;
                }
                """)));
    }


    @Test
    void deferredTaskCannotCaptureBorrowedWrapper() {
        rejects("""
                pub async fnc bad(Option<borrow int> wrapped): void {
                  let Future<int> child = task {
                    return wrapped;
                  };
                  await child;
                  return;
                }
                """, "cannot retain nested borrowed references");
    }

    @Test
    void genericDestructuringCannotLaunderBorrowedAggregate() {
        rejects("""
                fnc bad(Option<borrow int> wrapped): void {
                  const {value} = wrapped;
                  return;
                }
                """, "cannot destructure a borrowed aggregate");
    }

    @Test
    void OwnedScalarTaskCaptureStillAdmitted() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse("""
                pub async fnc valid(): void {
                  const int value = 41;
                  let Future<int> child = task {
                    return value + 1;
                  };
                  await child;
                  return;
                }
                """)));
    }

    @Test
    void nominalAndChainedAliasesCannotHideBorrowedClassFields() {
        rejects("""
                type Loan = Option<borrow int>;
                define class Unsafe as
                  pub let Loan member;
                end
                """, "nested borrow");
        rejects("""
                type Loan = Option<borrow int>;
                type Indirect = Loan;
                define class Unsafe as
                  pub let Indirect member;
                end
                """, "nested borrow");
    }

    @Test
    void genericAliasSubstitutionCannotHideBorrowedFields() {
        rejects("""
                type Holder<T> = Option<borrow T>;
                define class Unsafe as
                  pub let Holder<int> member;
                end
                """, "nested borrow");
    }

    @Test
    void ownedAliasDoesNotGetRejectedByBorrowChecker() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse("""
                type Holder<T> = Option<T>;
                define class Safe as
                  pub let Holder<int> member;
                end
                """)));
    }


    @Test
    void fixedTupleSpreadCannotLaunderBorrowedArgumentSlots() {
        rejects("""
                fnc consume(int first, int second): int {
                  return first + second;
                }
                fnc bad(borrow int first, int second): int {
                  return consume(...arguments);
                }
                """, "contains borrowed parameter");
    }

    @Test
    void genericBorrowBearingTupleSpreadCannotBecomeOwnedArguments() {
        rejects("""
                fnc consume(Option<int> first, int second): int {
                  return second;
                }
                fnc bad(Option<borrow int> first, int second): int {
                  return consume(...arguments);
                }
                """, "spread cannot consume a borrowed tuple");
    }

    @Test
    void ownedFixedTupleSpreadStillPassesOwnershipAdmission() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse("""
                fnc consume(int first, int second): int {
                  return first + second;
                }
                fnc valid(int first, int second): int {
                  return consume(...arguments);
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
