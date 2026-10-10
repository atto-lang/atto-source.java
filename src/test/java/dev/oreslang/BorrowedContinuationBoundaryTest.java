package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Deferred operations must never retain a call-site loan after the caller's
 * lexical scope ends. These tests run the ownership checker directly so the
 * borrow boundary, not an unrelated type/arity diagnostic, is the oracle.
 */
final class BorrowedContinuationBoundaryTest {
    private static final String BOX = """
            define class Box as
              pub val int value = 7;
            end
            """;

    private static void reject(String source, String diagnostic) {
        IllegalArgumentException problem = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse(source)));
        assertTrue(problem.getMessage().contains(diagnostic), problem::getMessage);
    }

    @Test
    void synchronousBorrowParameterRemainsValid() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(BOX + """
                fnc read(&Box box): int {
                  return box.value;
                }
                fnc good(): void {
                  let Box box = new Box();
                  val int result = read(rt borrow box);
                  stdio.println(result);
                  return;
                }
                """)));
    }

    @Test
    void asyncCallCannotRetainCallSiteBorrow() {
        reject(BOX + """
                async fnc later(&Box box): int {
                  return box.value;
                }
                fnc bad(): void {
                  let Box box = new Box();
                  val future = later(rt borrow box);
                  return;
                }
                """, "cannot borrow across an async/generator lifetime");
    }

    @Test
    void qualifiedAsyncCallAlsoRejectsBorrow() {
        reject(BOX + """
                define module worker as
                  pub async fnc later(&Box box): int {
                    return box.value;
                  }
                end
                fnc bad(): void {
                  let Box box = new Box();
                  val future = worker.later(rt borrow box);
                  return;
                }
                """, "cannot borrow across an async/generator lifetime");
    }

    @Test
    void generatorCallCannotRetainCallSiteBorrow() {
        reject(BOX + """
                gen* numbers(&Box box): Iterator<int> {
                  yield box.value;
                  return;
                }
                fnc bad(): void {
                  let Box box = new Box();
                  val iter = numbers(rt borrow box);
                  return;
                }
                """, "cannot borrow across an async/generator lifetime");
    }

    @Test
    void generatorCannotYieldNewlyCreatedBorrow() {
        reject(BOX + """
                gen* bad(): Iterator<&Box> {
                  let Box box = new Box();
                  yield rt borrow box;
                  return;
                }
                """, "yield cannot expose a borrowed reference");
    }

    @Test
    void generatorMayYieldOwnedOrCopyableData() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(BOX + """
                gen* good(): Iterator<int> {
                  let Box box = new Box();
                  yield box.value;
                  return;
                }
                """)));
    }

    @Test
    void constructorCannotHideBorrowedArgument() {
        reject(BOX + """
                define class Holder as
                  pub val int status = 0;
                end
                fnc bad(): void {
                  let Box box = new Box();
                  val holder = new Holder(rt borrow box);
                  return;
                }
                """, "constructor cannot retain borrowed values");
    }

    @Test
    void mutexCannotHideBorrowedState() {
        reject(BOX + """
                fnc bad(): void {
                  let Box box = new Box();
                  val lock = Mutex.new(rt borrow box);
                  return;
                }
                """, "requires an owned value");
    }

    @Test
    void unknownDynamicCallCannotRetainTemporaryBorrow() {
        reject(BOX + """
                fnc bad(): void {
                  let Box box = new Box();
                  external_callback(rt borrow box);
                  return;
                }
                """, "unknown call cannot retain borrowed arguments");
    }

    @Test
    void ownedContainerTypeCannotHideNestedBorrow() {
        reject(BOX + """
                fnc bad(): void {
                  val Option<&Box> hidden = None;
                  return;
                }
                """, "owned binding cannot contain a borrowed reference");
    }

    @Test
    void classFieldCannotHideNestedBorrowInOption() {
        reject(BOX + """
                define class UnsafeHolder as
                  pub val Option<&Box> reference = None;
                end
                """, "cannot store a borrowed reference");
    }

    @Test
    void unusedAsyncBorrowSignatureIsStillRejected() {
        reject(BOX + """
                async fnc unsafe(&Box input): int {
                  return input.value;
                }
                """, "cannot borrow across an async/generator lifetime");
    }

    @Test
    void ownedAsyncSignatureRemainsLegal() {
        assertDoesNotThrow(() -> OwnershipChecker.check(Parser.parse(BOX + """
                async fnc safe(Box input): int {
                  return input.value;
                }
                """)));
    }

    @Test
    void asyncResultMustNotExposeBorrowedReference() {
        reject(BOX + """
                async fnc identity(&Box box): &Box {
                  return box;
                }
                fnc bad(): void {
                  let Box box = new Box();
                  val future = identity(rt borrow box);
                  return;
                }
                """, "cannot borrow across an async/generator lifetime");
    }
}
