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
                    const int value = point[key];
                    return;
                }
                """);
        rejected("""
                fnc bad(): void {
                    const tuple = tuple(3.0, 4.0);
                    const index = 0;
                    const int value = tuple[index];
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
                    const string value = row[key];
                    return value;
                }
                """);
        rejected("""
                fnc bad(): void {
                    const row = record{x: 3.0, y: "boo"};
                    const key = "y";
                    const int value = row[key];
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
                    const string value = tuple[index];
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
    void indexedExplicitStructAccessParticipatesInDefiniteInitialization() {
        accepted("""
                fnc good(): bool {
                    val state = struct{score: int, ready: bool}{score: 1};
                    state["ready"] = true;
                    return state["ready"];
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
}
