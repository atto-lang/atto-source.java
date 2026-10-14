package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class BorrowFormalBoundaryRegressionTest {
    private static final String BOX = """
            define class Box as
              pub let int value = 7;
            end
            """;

    private static void reject(String source, String diagnostic) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertTrue(error.getMessage().contains(diagnostic), error::getMessage);
    }

    @Test
    void directYieldCannotCreateAndExportBorrowAfterPreYieldCheck() {
        reject(BOX + """
                gen* fnc bad(): Iterator<borrow Box> {
                  val Box box = new Box();
                  yield rt borrow box;
                  return;
                }
                """, "cannot yield a borrowed value");
    }

    @Test
    void delegatedYieldCannotHideBorrowInNestedElement() {
        reject(BOX + """
                gen* fnc bad(Iterator<Option<borrow Box>> source): Iterator<Option<borrow Box>> {
                  yield* source;
                  return;
                }
                """, "cannot yield borrowed elements");
    }

    @Test
    void constructorCannotRetainTemporaryBorrowWithoutLifetimeContract() {
        reject(BOX + """
                fnc bad(): void {
                  val Box box = new Box();
                  val result = new Box(rt borrow box);
                  return;
                }
                """, "constructor cannot retain a borrowed argument");
    }

    @Test
    void nestedBorrowCannotBeStoredInClassField() {
        reject(BOX + """
                define class Holder as
                  pub let Option<borrow Box> view = None;
                end
                """, "cannot store a borrowed reference");
    }

    @Test
    void ownedScalarGeneratorStillAdmitted() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse("""
                gen* fnc good(): Iterator<int> {
                  val int value = 7;
                  yield value;
                  return;
                }
                """)));
    }

    @Test
    void returnedOwnedOptionCannotConcealNestedBorrow() {
        reject(BOX + """
                fnc bad(Option<borrow Box> wrapped): Option<borrow Box> {
                  return wrapped;
                }
                """, "cannot return an owned value containing nested borrowed references");
    }

    @Test
    void localOwnedOptionCannotConcealNestedBorrow() {
        reject(BOX + """
                fnc bad(Option<borrow Box> wrapped): void {
                  val Option<borrow Box> saved = wrapped;
                  return;
                }
                """, "cannot bind an owned container containing nested borrowed references");
    }

    @Test
    void trapCannotPackageNestedBorrow() {
        reject(BOX + """
                fnc bad(Option<borrow Box> wrapped): void {
                  val result = trap wrapped;
                  return;
                }
                """, "trap cannot package a borrow or MutexGuard");
    }

    @Test
    void ownedTupleCannotConcealNestedBorrow() {
        reject(BOX + """
                fnc bad(Option<borrow Box> wrapped): void {
                  val pair = (wrapped, 7);
                  return;
                }
                """, "borrowed references cannot be stored in an owned tuple");
    }

    @Test
    void channelPayloadCannotTransportNestedBorrow() {
        reject(BOX + """
                fnc bad(Option<borrow Box> wrapped): void {
                  val Channel<Any> ch = Channel.new<Any>(1);
                  nb writech ch, wrapped;
                  return;
                }
                """, "writech cannot transport a borrowed reference");
    }

    @Test
    void listCannotConcealNestedBorrow() {
        reject(BOX + """
                fnc bad(Option<borrow Box> wrapped): void {
                  val values = [wrapped];
                  return;
                }
                """, "borrowed references cannot be stored in an owned array/list");
    }

    @Test
    void mutexCannotConcealNestedBorrowInState() {
        reject(BOX + """
                fnc bad(Option<borrow Box> wrapped): void {
                  val cell = Mutex.new(wrapped);
                  return;
                }
                """, "Mutex.new requires an owned value");
    }

    @Test
    void sharedMutexCannotConcealNestedBorrowInState() {
        reject(BOX + """
                fnc bad(Option<borrow Box> wrapped): void {
                  val cell = SharedMutex.new(wrapped);
                  return;
                }
                """, "SharedMutex.new requires an owned value");
    }

    @Test
    void lexicalBorrowMustNotBeErasedToAny() {
        reject(BOX + """
                fnc bad(): Any {
                  val Box box = new Box();
                  val Any erased = rt borrow box;
                  return erased;
                }
                """, "borrowed reference cannot be erased to an owned type");
    }

    @Test
    void ownedValueMustNotBeForgedIntoBorrowByAnnotation() {
        reject(BOX + """
                fnc bad(): void {
                  val &Box forged = new Box();
                  return;
                }
                """, "borrow-typed binding requires a proven borrowed initializer");
    }

    @Test
    void sharedBorrowMustNotBeStrengthenedToMutableType() {
        reject(BOX + """
                fnc bad(): void {
                  let Box box = new Box();
                  val &mut Box forged = rt borrow box;
                  return;
                }
                """, "shared borrow cannot be promoted to a mutable borrow");
    }

    @Test
    void ordinaryTypedLexicalBorrowRemainsSupported() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(BOX + """
                fnc good(): void {
                  let Box box = new Box();
                  val &Box view = rt borrow box;
                  stdio.println(view.value);
                  return;
                }
                """)));
    }

    @Test
    void ownedAnyCallMustNotConsumeEphemeralBorrow() {
        reject(BOX + """
                fnc sink(Any value): void { return; }
                fnc bad(): void {
                  val Box box = new Box();
                  sink(rt borrow box);
                  return;
                }
                """, "cannot consume a borrowed value through an owned parameter");
    }

    @Test
    void ownedAnyCallMustNotConsumeNestedBorrow() {
        reject(BOX + """
                fnc sink(Any value): void { return; }
                fnc bad(Option<borrow Box> wrapped): void {
                  sink(wrapped);
                  return;
                }
                """, "cannot consume a borrowed value through an owned parameter");
    }

    @Test
    void someCannotPackageNestedBorrow() {
        reject(BOX + """
                fnc bad(Option<borrow Box> wrapped): void {
                  val result = Some(wrapped);
                  return;
                }
                """, "Some cannot store a borrow in an owned sum value");
    }

    @Test
    void explicitBorrowParameterStillAcceptsValidBorrow() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(BOX + """
                fnc peek(borrow Box value): int { return value.value; }
                fnc good(): void {
                  val Box box = new Box();
                  val int number = peek(rt borrow box);
                  return;
                }
                """)));
    }
}
