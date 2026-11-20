package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Static soundness gates for computed property/index access on fixed products. */
final class DynamicPropertyAccessBoundaryTest {
    private static void accepted(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static void rejected(String source) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void inferredStructSupportsStringKeyReadsAndMutableIndexedWrites() {
        accepted("""
                fnc update(string key): float {
                    const Point seed = infer<Point> struct{x: 3.0, y: 4.0};
                    val Point point = infer struct{x: 5.0, y: 6.0};
                    point[key] = 9.0;
                    return point["x"];
                }
                """);
        accepted("""
                fnc read(): float {
                    const Point point = infer<Point> struct{x: 3.0, y: 4.0};
                    const key = 'x';
                    const float value = point[key];
                    return value;
                }
                """);
    }

    @Test
    void computedAccessNeverReinterpretsTheSelectedValueType() {
        rejected("""
                fnc bad(): void {
                    const Point point = infer<Point> struct{x: 3.0, y: 4.0};
                    const key = "x";
                    val int value = point[key];
                    return;
                }
                """);
        rejected("""
                fnc bad(): void {
                    const tuple = tuple(3.0, 4.0);
                    const index = 0;
                    val int value = tuple[index];
                    return;
                }
                """);
    }

    @Test
    void heterogeneousRecordLookupPreservesTheSelectedOrUnionType() {
        accepted("""
                fnc read(): string {
                    const row = record{x: 3.0, y: "boo"};
                    const key = "y";
                    val string value = row[key];
                    return value;
                }
                """);
        rejected("""
                fnc bad(): void {
                    const row = record{x: 3.0, y: "boo"};
                    const key = "y";
                    val int value = row[key];
                    return;
                }
                """);
        rejected("""
                fnc bad(string key): void {
                    const row = record{x: 3.0, y: "boo"};
                    const float value = row[key];
                    return;
                }
                """);
    }

    @Test
    void homogeneousDynamicRecordLookupHasOneStableResultType() {
        accepted("""
                fnc read(string key): float {
                    const row = record{x: 3.0, y: 4.0};
                    return row[key];
                }
                """);
    }

    @Test
    void tupleDynamicIndexUsesSlotUnionAndTupleWritesRemainForbidden() {
        accepted("""
                fnc read(int index): float {
                    const tuple = tuple(3.0, 4.0);
                    return tuple[index];
                }
                """);
        accepted("""
                fnc exact_const_index(): string {
                    const tuple = tuple(3.0, "boo");
                    const index = 1;
                    val string value = tuple[index];
                    return value;
                }
                """);
        rejected("""
                fnc bad(int index): void {
                    const tuple = tuple(3.0, "boo");
                    const float value = tuple[index];
                    return;
                }
                """);
        rejected("""
                fnc bad(): void {
                    val tuple = tuple(1, 2);
                    tuple[0] = 3;
                    return;
                }
                """);
        rejected("""
                fnc bad(int index): void {
                    const empty = tuple();
                    val int value = empty[index];
                    return;
                }
                """);
    }


    @Test
    void namedConstSelectorsKeepOwnershipPrecisionWithMoveOnlySiblings() {
        accepted("""
                define class Box as
                    pub val int payload = 1;
                end

                fnc tuple_copy(): int {
                    const pair = tuple(7, new Box());
                    const index = 0;
                    return pair[index];
                }

                fnc record_copy(): int {
                    const row = record{x: 7, box: new Box()};
                    const string key = "x";
                    return row[key];
                }
                """);
    }

    @Test
    void constSelectorAliasesKeepOwnershipPrecision() {
        accepted("""
                define class Box as
                    pub val int payload = 1;
                end

                fnc record_alias(): int {
                    const row = record{x: 7, box: new Box()};
                    const string first = "x";
                    const string second = first;
                    return row[second];
                }

                fnc tuple_alias(): int {
                    const pair = tuple(7, new Box());
                    const int first = 0;
                    const int second = first;
                    return pair[second];
                }
                """);
    }

    @Test
    void stringSplitResultsRetainCopyableElementOwnershipForIndexing() {
        accepted("""
                fnc parse(String line): String {
                    const parts = line.split_literal("=");
                    val String left = parts[0];
                    val String right = parts[1];
                    return left + right;
                }
                """);
    }

    @Test
    void mutableSelectorBindingsRemainDynamic() {
        rejected("""
                define class Box as
                    pub val int payload = 1;
                end

                fnc reject_mutable_selector(): int {
                    const row = record{x: 7, box: new Box()};
                    let string field = "x";
                    field = "box";
                    return row[field];
                }
                """);
    }

    @Test
    void capturedNamedConstSelectorKeepsOwnershipPrecision() {
        accepted("""
                define class Box as
                    pub val int payload = 1;
                end

                fnc build_getter(): void {
                    const row = record{x: 7, box: new Box()};
                    const key = "x";
                    const getter = || -> row[key];
                    return;
                }
                """);
    }

    @Test
    void classFieldsSupportStringIndexingButMethodsAreNotProperties() {
        accepted("""
                define class Point as
                    pub val float x = 3.0;
                    pub val float y = 4.0;
                end

                fnc read(string key): float {
                    const Point point = new Point();
                    return point[key];
                }
                """);
        rejected("""
                define class Point as
                    pub val float x = 3.0;
                    pub fnc move(): float { return self.x; }
                end

                fnc bad(): void {
                    const Point point = new Point();
                    const method = point["move"];
                    return;
                }
                """);
    }

    @Test
    void structuralClassComputedAccessExposesDataFieldsNotMethodContracts() {
        accepted("""
                define class Product as
                    pub val float x = 3.0;
                    pub fnc label(): string { return "product"; }
                end

                fnc read(structural Product value, string key): float {
                    return value[key];
                }
                """);
    }

    @Test
    void dynamicClassIndexCannotBypassPrivateOrImmutableFields() {
        rejected("""
                define class Secret as
                    private val int hidden = 1;
                    pub val int shown = 2;
                end

                fnc bad(string key): void {
                    const Secret secret = new Secret();
                    const value = secret[key];
                    return;
                }
                """);
        accepted("""
                define class Secret as
                    private val int hidden = 1;
                    pub val int shown = 2;
                end

                fnc good(): int {
                    const Secret secret = new Secret();
                    const string key = "shown";
                    return secret[key];
                }
                """);
        rejected("""
                define class Mixed as
                    pub let int mutable = 1;
                    pub val int fixed = 2;
                end

                fnc bad(string key): void {
                    val Mixed item = new Mixed();
                    item[key] = 3;
                    return;
                }
                """);
        accepted("""
                define class MutablePoint as
                    pub let int x = 1;
                    pub let int y = 2;
                end

                fnc update(string key): int {
                    val MutablePoint point = new MutablePoint();
                    point[key] = 7;
                    return point[key];
                }
                """);
    }

    @Test
    void inheritedClassFieldsParticipateInDynamicAccessSafety() {
        accepted("""
                define class Base as
                    pub val int inherited = 1;
                end

                define class Child extends Base as
                    pub val int own = 2;
                end

                fnc read(Child value, string key): int {
                    return value[key];
                }
                """);

        rejected("""
                define class Base as
                    private val int hidden = 1;
                end

                define class Child extends Base as
                    pub val int shown = 2;
                end

                fnc bad(Child value, string key): int {
                    return value[key];
                }
                """);

        rejected("""
                define class Base as
                    pub val int fixed = 1;
                end

                define class Child extends Base as
                    pub let int mutable = 2;
                end

                fnc bad(string key): void {
                    val Child value = new Child();
                    value[key] = 3;
                    return;
                }
                """);
    }

    @Test
    void inferredClassFieldsKeepOwnershipPrecisionAcrossDestructuringAndIndexing() {
        accepted("""
                define class Payload as
                    pub val int value = 1;
                end

                define class Holder as
                    pub val id = 7;
                    pub val Payload payload = new Payload();
                end

                fnc read(Holder holder): int {
                    const {id} = holder;
                    const key = "id";
                    val int indexed = holder[key];
                    return id + indexed;
                }
                """);
    }

    @Test
    void genericInheritedFieldsPreserveComputedAccessSubstitution() {
        accepted("""
                define class Parent<T> as
                    pub val T inherited;
                end

                define class Child<U> extends Parent<U> as
                    pub val U own;
                end

                fnc exact(Child<string> value): string {
                    return value["inherited"];
                }

                fnc dynamic(Child<int> value, string key): int {
                    return value[key];
                }

                define class MutableParent<T> as
                    pub let T inherited;
                end

                define class MutableChild<U> extends MutableParent<U> as
                    pub let U own;
                end

                fnc update(string key): int {
                    val MutableChild<int> value = new MutableChild<int>(1, 2);
                    value[key] = 3;
                    return value["inherited"];
                }
                """);

        rejected("""
                define class Parent<T> as
                    pub val T inherited;
                end

                define class Child<U> extends Parent<U> as
                    pub val string own = "x";
                end

                fnc bad(Child<int> value, string key): int {
                    return value[key];
                }
                """);
    }

    @Test
    void heterogeneousDynamicReadsReturnUnionsAndWritesMustFitEveryDestination() {
        accepted("""
                fnc struct_read(Mixed value, string key): int | string {
                    return value[key];
                }

                const Mixed seed = infer<Mixed> struct{
                    count: 1,
                    label: "one"
                };

                define class MixedClass as
                    pub let int count = 1;
                    pub let string label = "one";
                end

                fnc class_read(MixedClass value, string key): int | string {
                    return value[key];
                }
                """);

        rejected("""
                fnc bad_struct(string key): void {
                    const Mixed seed = infer<Mixed> struct{
                        count: 1,
                        label: "one"
                    };
                    val Mixed value = infer struct{
                        count: 2,
                        label: "two"
                    };
                    value[key] = 7;
                    return;
                }
                """);

        rejected("""
                define class MixedClass as
                    pub let int count = 1;
                    pub let string label = "one";
                end

                fnc bad_class(string key): void {
                    val MixedClass value = new MixedClass();
                    value[key] = 7;
                    return;
                }
                """);

        accepted("""
                const Pair seed = infer<Pair> struct{
                    left: 1,
                    right: 2
                };

                fnc good_struct(string key): int {
                    val Pair value = infer struct{
                        left: 3,
                        right: 4
                    };
                    value[key] = 9;
                    return value[key];
                }

                define class PairClass as
                    pub let int left = 1;
                    pub let int right = 2;
                end

                fnc good_class(string key): int {
                    val PairClass value = new PairClass();
                    value[key] = 9;
                    return value[key];
                }
                """);
    }

    @Test
    void computedClassWritesSupportInitializerInferredFieldTypes() {
        accepted("""
                define class InferredPoint as
                    pub let x = 1;
                    pub let y = 2;
                end

                fnc update(string key): int {
                    val InferredPoint point = new InferredPoint();
                    point["x"] = 3;
                    point[key] = 4;
                    point.x = 5;
                    return point["x"];
                }
                """);
    }

    @Test
    void indexedExplicitStructAccessParticipatesInDefiniteInitialization() {
        accepted("""
                fnc good(): bool {
                    val state = struct{score: int, ready: bool}{score: 1};
                    const string key = "ready";
                    state[key] = true;
                    return state[key];
                }
                """);
        rejected("""
                fnc bad(string key): void {
                    val state = struct{score: int, ready: bool}{score: 1};
                    const value = state[key];
                    return;
                }
                """);
        rejected("""
                fnc bad(): void {
                    val state = struct{score: int, ready: bool}{score: 1};
                    const value = state["ready"];
                    return;
                }
                """);
    }

    @Test
    void computedProductBorrowsRemainFailClosedWithoutPathExclusiveLoans() {
        rejected("""
                fnc bad(string key): void {
                    val point = struct{x: int, y: int}{x: 1, y: 2};
                    const field = rt borrow point[key];
                    return;
                }
                """);
        rejected("""
                fnc bad(int index): void {
                    const pair = tuple(1, 2);
                    const slot = rt borrow pair[index];
                    return;
                }
                """);
    }

    @Test
    void structuralRecordsRemainReadonlyThroughIndexedAccess() {
        rejected("""
                fnc bad(): void {
                    const row = record{x: 1};
                    row["x"] = 2;
                    return;
                }
                """);
    }

    @Test
    void entryIsNamedKeyValueProductAndPairShapeUsesTupleIndexing() {
        accepted("""
                type Entry<K,V> = {key: K, value: V};

                fnc entry_key(Entry<string,int> entry): string {
                    const key = "key";
                    return entry[key];
                }

                fnc entry_dynamic(Entry<string,int> entry, string key): string | int {
                    return entry[key];
                }

                fnc pair_slot(Tuple[string,int] pair, int index): string | int {
                    return pair[index];
                }
                """);
    }

    @Test
    void namedConstKeysRemainExactAcrossOwnershipWithMoveOnlySiblingFields() {
        accepted("""
                define class Payload as
                    pub val int value = 1;
                end

                fnc record_read(): int {
                    const row = record{id: 1, payload: new Payload()};
                    const key = "id";
                    val int value = row[key];
                    return value;
                }

                fnc tuple_read(): int {
                    const row = tuple(1, new Payload());
                    const index = 0;
                    val int value = row[index];
                    return value;
                }

                define class Holder as
                    pub val int id = 1;
                    pub val Payload payload = new Payload();
                end

                fnc class_read(): int {
                    const Holder holder = new Holder();
                    const key = "id";
                    val int value = holder[key];
                    return value;
                }
                """);
    }

    @Test
    void computedWritesCannotEscalateReadOnlyBindingAuthority() {
        rejected("""
                fnc bad(): void {
                    const Point point = infer<Point> struct{x: 1, y: 2};
                    point["x"] = 3;
                    return;
                }
                """);
        rejected("""
                define class MutablePoint as
                    pub let int x = 1;
                end

                fnc bad(): void {
                    const MutablePoint point = new MutablePoint();
                    point["x"] = 3;
                    return;
                }
                """);
    }

    @Test
    void exactKeysDoNotInheritDynamicVisibilityOrDomainAmbiguity() {
        accepted("""
                define class Secret as
                    private val int hidden = 1;
                    pub val int shown = 2;
                end

                fnc good(): int {
                    const Secret secret = new Secret();
                    return secret["shown"];
                }
                """);
        rejected("""
                define class Secret as
                    private val int hidden = 1;
                    pub val int shown = 2;
                end

                fnc bad(): int {
                    const Secret secret = new Secret();
                    return secret["hidden"];
                }
                """);
        rejected("""
                fnc bad(): void {
                    const row = record{x: 1};
                    const value = row[0];
                    return;
                }
                """);
        rejected("""
                fnc bad(): void {
                    const pair = tuple(1, 2);
                    const value = pair["0"];
                    return;
                }
                """);
        rejected("""
                fnc bad(): void {
                    const row = record{x: 1};
                    const value = row["missing"];
                    return;
                }
                """);
        rejected("""
                fnc bad(): void {
                    const pair = tuple(1, 2);
                    const value = pair[-1];
                    return;
                }
                """);
        rejected("""
                fnc bad(): void {
                    const pair = tuple(1, 2);
                    const index = 2;
                    const value = pair[index];
                    return;
                }
                """);
    }

    @Test
    void rhsMoveCannotInvalidateComputedAssignmentReceiver() {
        rejected("""
                define class Point as
                    pub let int x = 1;
                end

                fnc consume(Point point): int {
                    return 9;
                }

                fnc bad(): void {
                    val Point point = new Point();
                    const key = "x";
                    point[key] = consume(point);
                    return;
                }
                """);

        rejected("""
                define class Point as
                    pub let int x = 1;
                end

                fnc consume(Point point): int {
                    return 9;
                }

                fnc bad(): void {
                    val Point point = new Point();
                    point.x = consume(point);
                    return;
                }
                """);
    }

}
