package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reusable select descriptors retain operands beyond the constructor call.
 * Their ownership must remain valid over repeated select invocations.
 */
final class SelectPlanMainlineBorrowSafetyTest {
    private static void accept(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static void reject(String source, String diagnostic) {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
        assertTrue(failure.getMessage().contains(diagnostic), failure::getMessage);
    }

    @Test
    void reusableReadCaseIsLinearEvenWhenItsChannelHandleIsCopyable() {
        reject("""
                fnc bad(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val SelectCase first = SelectCase.read(ch);
                  val SelectCase moved = first;
                  val SelectCase second = first;
                  return;
                }
                """, "moved value");
    }

    @Test
    void aReusedSetCannotBeCopiedByValue() {
        reject("""
                fnc bad(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val SelectSet cases = SelectSet.new([SelectCase.read(ch)]);
                  val SelectSet moved = cases;
                  val Option<SelectResult> again = try select from cases;
                  return;
                }
                """, "moved value");
    }

    @Test
    void staticDynamicReadCasesRemainReusableWithoutConsumingTheSet() {
        accept("""
                fnc good(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val SelectSet cases = SelectSet.new([SelectCase.read(ch)]);
                  val Option<SelectResult> first = try select from cases;
                  val Option<SelectResult> second = try select from cases;
                  return;
                }
                """);
    }

    @Test
    void persistentWriteCaseMayReuseOnlyCopyPayloads() {
        accept("""
                fnc good(): void {
                  val Channel<int> output = Channel.new<int>(2);
                  val SelectSet cases = SelectSet.new([SelectCase.write(output, 42)]);
                  val Option<SelectResult> first = try select from cases;
                  val Option<SelectResult> second = try select from cases;
                  return;
                }
                """);
    }

    @Test
    void persistentWriteCaseRejectsMovedPayloadThatWouldBeSentTwice() {
        reject("""
                fnc bad(): void {
                  val Channel<Array<int>> output = Channel.new<Array<int>>(2);
                  val Array<int> payload = [1, 2];
                  val SelectCase write = SelectCase.write(output, payload);
                  return;
                }
                """, "reusable SelectCase.write requires a Copy payload");
    }

    @Test
    void borrowCheckerAloneRejectsCapturedWriteBorrow() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse("""
                        fnc bad(): void {
                          val Channel<Array<int>> output = Channel.new<Array<int>>(1);
                          val Array<int> owned = [1, 2];
                          val &Array<int> borrowed = rt borrow owned;
                          val SelectCase write = SelectCase.write(output, borrowed);
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("cannot retain a borrowed payload"),
                error::getMessage);
    }

    @Test
    void persistentReadCaseCannotRetainBorrowedChannelHandle() {
        reject("""
                fnc bad(): void {
                  val Channel<int> input = Channel.new<int>(1);
                  val &Channel<int> borrowed = rt borrow input;
                  val SelectCase read = SelectCase.read(borrowed);
                  return;
                }
                """, "cannot retain a borrowed channel");
    }

    @Test
    void setConstructorCannotRetainBorrowedCaseCollection() {
        reject("""
                fnc bad(): void {
                  val Channel<int> input = Channel.new<int>(1);
                  val Array<SelectCase> cases = [SelectCase.read(input)];
                  val &Array<SelectCase> borrowed = rt borrow cases;
                  val SelectSet set = SelectSet.new(borrowed);
                  return;
                }
                """, "cannot retain a borrowed case collection");
    }

    @Test
    void selectPlanCapabilitiesCannotCrossActorBoundaries() {
        reject("""
                actor fnc bad(SelectPlan plan): void {
                  return;
                }
                """, "cannot use SelectPlan across an actor boundary");
        reject("""
                actor fnc bad(DynamicSelectPlan plan): void {
                  return;
                }
                """, "cannot use DynamicSelectPlan across an actor boundary");
    }

    @Test
    void consumingDynamicPayloadCannotExtractSameResultTwice() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OwnershipChecker.check(Parser.parse("""
                        fnc bad(SelectResult result): void {
                          val first = result.value;
                          val second = result.value;
                          return;
                        }
                        """)));
        assertTrue(error.getMessage().contains("moved value"), error::getMessage);
    }

    @Test
    void readonlyBorrowCannotMutateDynamicSelectPlan() {
        reject("""
                fnc bad(): void {
                  val Channel<int> ch = Channel.new<int>(1);
                  val DynamicSelectPlan plan = DynamicSelectPlan.new();
                  val &DynamicSelectPlan borrowed = rt borrow plan;
                  borrowed.put("read", SelectCase.read(ch));
                  return;
                }
                """, "cannot mutate DynamicSelectPlan.put through");
        reject("""
                fnc bad(): void {
                  val DynamicSelectPlan plan = DynamicSelectPlan.new();
                  val &DynamicSelectPlan borrowed = rt borrow plan;
                  borrowed.remove("read");
                  return;
                }
                """, "cannot mutate DynamicSelectPlan.remove through");
    }

    @Test
    void readonlyBorrowCanReadDynamicPlanMetadata() {
        accept("""
                fnc good(): void {
                  val DynamicSelectPlan plan = DynamicSelectPlan.new();
                  val &DynamicSelectPlan borrowed = rt borrow plan;
                  val int n = borrowed.size();
                  val int v = borrowed.version();
                  return;
                }
                """);
    }

    @Test
    void borrowedSelectResultCannotTransferOwnedValue() {
        reject("""
                fnc bad(SelectResult result): void {
                  val &SelectResult borrowed = rt borrow result;
                  val payload = borrowed.value;
                  return;
                }
                """, "SelectResult.value cannot be moved out of a borrowed result");
    }

    @Test
    void nestedOptionCannotDuplicateOwnedSelectCase() {
        reject("""
                fnc bad(): void {
                  val Channel<int> input = Channel.new<int>(1);
                  val SelectCase read = SelectCase.read(input);
                  val Option<SelectCase> wrapped = Some(read);
                  val Option<SelectCase> duplicate = wrapped;
                  val Option<SelectCase> another = wrapped;
                  return;
                }
                """, "moved value");
    }
}
