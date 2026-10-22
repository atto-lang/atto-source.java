package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class RepeatingSequenceShapeSafetyTest {
    private static void accepts(String src) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(src)));
    }
    private static void rejects(String src) {
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(Parser.parse(src)));
    }
    private static void rejectsAtTypecheck(String src) {
        var parsed = assertDoesNotThrow(() -> Parser.parse(src),
                "regression must exercise the checker, not just fail parsing");
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(parsed));
    }

    @Test
    void repeatingTypeRetainsOrderAndCannotBeSilentlyErased() {
        var i = Types.Primitive.INT;
        var s = Types.Primitive.STRING;
        var b = Types.Primitive.BOOL;
        var union = Types.unionOf(List.of(i, s, b));
        var shape = new Types.ListType(union, List.of(i, s, b));
        var reversed = new Types.ListType(union, List.of(s, i, b));
        var erased = new Types.ListType(union);
        assertEquals(List.of(i, s, b), shape.repeatUnit());
        assertTrue(Types.isAssignable(shape, shape));
        assertFalse(Types.isAssignable(shape, erased));
        assertFalse(Types.isAssignable(erased, shape));
        assertFalse(Types.isAssignable(shape, reversed));
        assertFalse(Types.isAssignable(reversed, shape));
        assertFalse(Types.isAssignable(Types.Unknown.INSTANCE, shape));
        assertFalse(Types.isAssignable(new Types.Generic("Unbound"), shape));
    }

    @Test
    void preservesShapeAcrossBindingCallAndReturn() {
        accepts("""
                pub fnc identity(Array[...(int, string, bool)] xs): Array[...(int, string, bool)] { return xs; }
                pub fnc make(): Array[...(int, string, bool)] { return [1, "one", true, 2, "two", false]; }
                pub fnc main(): void {
                  val xs: Array[...(int, string, bool)] = [1, "one", true, 2, "two", false];
                  val ys: Array[...(int, string, bool)] = identity(xs);
                  val zs: Array[...(int, string, bool)] = make();
                  return;
                }
                """);
    }

    @Test
    void rejectsShapeForgingAndMutableErasure() {
        rejects("""
                pub fnc main(): void {
                  val source: Array[int | string | bool] = [1, "one", true];
                  val target: Array[...(int, string, bool)] = source;
                  return;
                }
                """);
        rejects("""
                pub fnc main(): void {
                  val source: Array[...(int, string, bool)] = [1, "one", true, 2, "two", false];
                  val target: Array[int | string | bool] = source;
                  return;
                }
                """);
        rejects("""
                pub fnc make(): Array[int | string | bool] { return [1, "one", true]; }
                pub fnc main(): void {
                  val target: Array[...(int, string, bool)] = make();
                  return;
                }
                """);
        rejects("""
                pub fnc main(): void {
                  val source: Array[...(string, int, bool)] = ["one", 1, true];
                  val target: Array[...(int, string, bool)] = source;
                  return;
                }
                """);
    }

    @Test
    void checksCompleteGroupsOnFunctionReturn() {
        rejects("""
                pub fnc bad(): Array[...(int, string, bool)] { return [1, "one"]; }
                """);
        rejects("""
                pub fnc bad(): Array[...(int, string, bool)] { return [true, 1, "one"]; }
                """);
        accepts("""
                pub fnc empty(): Array[...(int, string, bool)] { return []; }
                """);
    }

    @Test
    void rejectsUnprovenPatternMutationsButKeepsOrdinaryListApi() {
        rejects("""
                pub fnc main(): void {
                  let mut xs: Array[...(int, string, bool)] = [1, "one", true, 2, "two", false];
                  xs.add(true);
                  return;
                }
                """);
        rejects("""
                pub fnc main(): void {
                  let mut xs: Array[...(int, string, bool)] = [1, "one", true, 2, "two", false];
                  xs.set(0, true);
                  return;
                }
                """);
        rejects("""
                pub fnc main(): void {
                  let mut xs: Array[...(int, string, bool)] = [1, "one", true, 2, "two", false];
                  xs[0] = true;
                  return;
                }
                """);
        accepts("""
                pub fnc main(): void {
                  let mut xs: Array[int] = [1, 2];
                  xs.add(3);
                  xs.set(0, 4);
                  return;
                }
                """);
    }

    @Test
    void countedRepeatedUnitRemainsNinePositionTypedAcrossCalls() {
        accepts("""
                pub fnc preserve(
                    Array[...(3 of int, 3 of string, 3 of bool)] input
                ): Array[...(3 of int, 3 of string, 3 of bool)] {
                  return input;
                }
                pub fnc main(): void {
                  val xs: Array[...(3 of int, 3 of string, 3 of bool)] =
                      [1, 2, 3, "a", "b", "c", true, false, true];
                  val ys: Array[...(3 of int, 3 of string, 3 of bool)] =
                      preserve(xs);
                  return;
                }
                """);
        rejects("""
                pub fnc bad(): Array[...(3 of int, 3 of string, 3 of bool)] {
                  return [1, 2, 3, "a", "b", "c", true, false];
                }
                """);
        rejects("""
                pub fnc bad(): Array[...(3 of int, 3 of string, 3 of bool)] {
                  return [1, 2, 3, "a", "b", "c", true, false, 99];
                }
                """);
    }

    @Test
    void refusesForgedCompilerMetadataThatNarrowedTheElementJoin() {
        assertThrows(IllegalArgumentException.class, () ->
                new Types.ListType(Types.Primitive.INT,
                        List.of(Types.Primitive.INT, Types.Primitive.STRING)));
        assertThrows(IllegalArgumentException.class, () ->
                new Types.ListType(Types.Unknown.INSTANCE,
                        List.of(Types.Primitive.VOID)));
        assertDoesNotThrow(() ->
                new Types.ListType(Types.unionOf(List.of(
                        Types.Primitive.INT, Types.Primitive.STRING)),
                        List.of(Types.Primitive.INT, Types.Primitive.STRING)));
    }

    @Test
    void contextualCallArgumentsValidateTheirCompleteSequenceShape() {
        accepts("""
                pub fnc consume(Array[...(int, string, bool)] xs): void { return; }
                pub fnc main(): void {
                  consume([1, "one", true, 2, "two", false]);
                  return;
                }
                """);
        rejects("""
                pub fnc consume(Array[...(int, string, bool)] xs): void { return; }
                pub fnc main(): void {
                  consume([1, "one", true, false]);
                  return;
                }
                """);
    }

    @Test
    void readFromRuntimeIndexDoesNotForgeAPositionSpecificType() {
        rejects("""
                pub fnc bad(): void {
                  val xs: Array[...(int, string, bool)] = [1, "one", true];
                  val first: int = xs.get(0);
                  return;
                }
                """);
    }

    @Test
    void runtimeTypeAliasCannotForgePatternedArrayProof() {
        rejectsAtTypecheck("""
                type Repeated = Array[...(int, string, bool)];
                pub fnc main(): void {
                  val arbitrary: Array[int | string | bool] = [true, 1, "x"];
                  val forged: Repeated = arbitrary as Repeated;
                  return;
                }
                """);
        rejectsAtTypecheck("""
                type Repeated = Array[...(int, string, bool)];
                type Hidden = Repeated;
                pub fnc main(): void {
                  val arbitrary: Array[int | string | bool] = [true, 1, "x"];
                  val forged: Hidden = arbitrary as Hidden;
                  return;
                }
                """);
        // Existing primitive aliases must remain usable by runtime type tests.
        accepts("""
                type Flag = bool;
                pub fnc main(): void {
                  val yes: bool = true as Flag;
                  return;
                }
                """);
    }

    @Test
    void nonEmptyPatternedArrayConstructorCannotInventASequence() {
        rejectsAtTypecheck("""
                pub fnc main(): void {
                  val xs: Array[...(int, string, bool)] =
                      new Array[...(int, string, bool)](3);
                  return;
                }
                """);
        accepts("""
                pub fnc main(): void {
                  val xs: Array[...(int, string, bool)] =
                      new Array[...(int, string, bool)]();
                  return;
                }
                """);
    }

    @Test
    void unknownElementsCannotForgePrecisePatternMetadata() {
        assertThrows(IllegalArgumentException.class, () ->
                new Types.ListType(Types.Unknown.INSTANCE,
                        List.of(Types.Primitive.INT, Types.Primitive.STRING)));
        assertThrows(IllegalArgumentException.class, () ->
                new Types.ListType(Types.Primitive.INT,
                        List.of(Types.Unknown.INSTANCE)));
        assertThrows(IllegalArgumentException.class, () ->
                new Types.ListType(Types.Primitive.INT,
                        List.of(new Types.Generic("T"))));
        assertThrows(IllegalArgumentException.class, () ->
                new Types.ListType(new Types.Generic("T"),
                        List.of(Types.Primitive.INT)));
        assertThrows(IllegalArgumentException.class, () ->
                new Types.ListType(
                        Types.unionOf(List.of(new Types.Generic("T"), Types.Primitive.INT)),
                        List.of(new Types.Generic("S"))));
        // A compile-time generic slot remains a typed placeholder, not Unknown.
        assertDoesNotThrow(() ->
                new Types.ListType(Types.unionOf(List.of(
                        new Types.Generic("T"), Types.Primitive.INT)),
                        List.of(new Types.Generic("T"), Types.Primitive.INT)));
    }

    @Test
    void sharedMutexAdmissionRetainsThePatternedPayloadSafetyContract() {
        accepts("""
                fnc good(SharedMutex<Array[...(int, string, bool)]> values): void {
                  return;
                }
                """);
        rejectsAtTypecheck("""
                fnc bad(SharedMutex<Array[...(int, Mutex<int>, bool)]> values): void {
                  return;
                }
                """);
    }

    @Test
    void nestedUnresolvedAndMismatchedGenericSlotsCannotForgeShapeProofs() {
        var unknownBox = new Types.Named("Box", List.of(Types.Unknown.INSTANCE));
        var intBox = new Types.Named("Box", List.of(Types.Primitive.INT));
        assertThrows(IllegalArgumentException.class,
                () -> new Types.ListType(unknownBox, List.of(unknownBox)));
        assertThrows(IllegalArgumentException.class,
                () -> new Types.ListType(new Types.Borrow(unknownBox, false),
                        List.of(new Types.Borrow(unknownBox, false))));
        assertThrows(IllegalArgumentException.class,
                () -> new Types.ListType(
                        new Types.Named("Box", List.of(new Types.Generic("T"))),
                        List.of(new Types.Named("Box", List.of(new Types.Generic("S"))))));
        var sameGeneric = new Types.Named("Box", List.of(new Types.Generic("T")));
        assertDoesNotThrow(() -> new Types.ListType(sameGeneric, List.of(sameGeneric)));
        assertDoesNotThrow(() -> new Types.ListType(intBox, List.of(intBox)));
    }

    @Test
    void unknownCannotForgePatternProofThroughWrapperOrUnion() {
        var joined = Types.unionOf(List.of(Types.Primitive.INT, Types.Primitive.STRING));
        var shape = new Types.ListType(joined,
                List.of(Types.Primitive.INT, Types.Primitive.STRING));
        var optional = new Types.Named("Option", List.of(shape));
        var result = new Types.Named("Result", List.of(shape, Types.Primitive.BOOL));
        var tuple = new Types.Tuple(List.of(Types.Primitive.INT, shape));
        var record = new Types.Record(java.util.Map.of("items", shape));
        var union = Types.unionOf(List.of(shape, Types.Primitive.BOOL));
        for (Types.Type wrapped : List.of(optional, result, tuple, record, union)) {
            assertTrue(Types.containsPatternedList(wrapped));
            assertFalse(Types.isAssignable(Types.Unknown.INSTANCE, wrapped));
            assertFalse(Types.isAssignable(new Types.Generic("Unbound"), wrapped));
            assertTrue(Types.isAssignable(wrapped, wrapped));
        }
        assertFalse(Types.containsPatternedList(new Types.ListType(joined)));
    }

    @Test
    void genericTypeInferenceUsesRepeatedSlotTypesInsteadOfTheElementUnion() {
        accepts("""
                fnc preserve<T>(Array[...(T, string, bool)] input):
                    Array[...(T, string, bool)] {
                  return input;
                }
                pub fnc main(): void {
                  val input: Array[...(int, string, bool)] = [1, "one", true];
                  val output: Array[...(int, string, bool)] = preserve(input);
                  return;
                }
                """);
        rejectsAtTypecheck("""
                fnc preserve<T>(Array[...(T, string, bool)] input):
                    Array[...(T, string, bool)] {
                  return input;
                }
                pub fnc main(): void {
                  val input: Array[...(int, string, int)] = [1, "one", 2];
                  val output: Array[...(int, string, bool)] = preserve(input);
                  return;
                }
                """);
    }

    @Test
    void entryComponentReificationRetainsTypeAliasCycleDetection() {
        rejectsAtTypecheck("""
                type Recursive = Entry<int, Recursive>;
                pub fnc main(): void {
                  val x = 1 as Recursive;
                  return;
                }
                """);
        rejectsAtTypecheck("""
                type A = Entry<int, B>;
                type B = A;
                pub fnc main(): void {
                  val x = 1 as A;
                  return;
                }
                """);
    }

    @Test
    void nestedAliasesAndBorrowWrappersCannotEraseOrInventShape() {
        var element = Types.unionOf(List.of(
                Types.Primitive.INT, Types.Primitive.STRING, Types.Primitive.BOOL));
        var shaped = new Types.ListType(element, List.of(
                Types.Primitive.INT, Types.Primitive.STRING, Types.Primitive.BOOL));
        var plain = new Types.ListType(element);
        var shapedOption = new Types.Named("Option", List.of(shaped));
        var plainOption = new Types.Named("Option", List.of(plain));
        assertFalse(Types.isAssignable(shapedOption, plainOption));
        assertFalse(Types.isAssignable(plainOption, shapedOption));
        assertFalse(Types.isAssignable(new Types.Borrow(shaped, true),
                new Types.Borrow(plain, true)));
        assertFalse(Types.isAssignable(new Types.Borrow(plain, true),
                new Types.Borrow(shaped, true)));
        assertTrue(Types.isAssignable(new Types.Borrow(shaped, true),
                new Types.Borrow(shaped, true)));
    }

}
