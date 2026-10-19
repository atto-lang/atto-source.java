package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Computed indexing is not arbitrary object reflection. Static/literal keys
 * keep precise field types; genuinely dynamic heterogeneous lookup has a union.
 * No test here claims an unchecked class field/hidden method is indexable.
 */
final class ComputedAggregateIndexBoundaryTest {
    private static void accepted(String source) {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
    }

    private static void denied(String source) {
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
    }

    private static void deniedWithMessage(String source, String expectedFragment) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(failure.getMessage().contains(expectedFragment), failure.getMessage());
    }

    @Test
    void inferredStructWithComputedStringKeyRetainsHomogeneousFloatType() {
        accepted("""
                fnc get(): float {
                    const T d = infer<T> struct{x: 3.0, y: 4.0};
                    const key = 'x';
                    return d[key];
                }
                """);
        denied("""
                fnc bad(): int {
                    const T d = infer<T> struct{x: 3.0, y: 4.0};
                    const key = 'x';
                    return d[key];
                }
                """);
    }

    @Test
    void recordWithKnownKeyCanSelectHeterogeneousFieldWithoutErasure() {
        accepted("""
                fnc get(): string {
                    const T r = record{x: 3.0, y: "boo"};
                    const key = "y";
                    return r[key];
                }
                """);
        accepted("""
                fnc get(): float {
                    const T r = record{x: 3.0, y: "boo"};
                    return r["x"];
                }
                """);
    }

    @Test
    void unknownOrVariableHeterogeneousIndexCannotPretendToBeInt() {
        denied("""
                fnc bad(string key): int {
                    const T r = record{x: 3.0, y: "boo"};
                    return r[key];
                }
                """);
        denied("""
                fnc bad(): float {
                    const T r = record{x: 3.0, y: "boo"};
                    return r["missing"];
                }
                """);
        denied("""
                fnc bad(string key): string {
                    const T r = record{x: 3.0, y: "boo"};
                    return r[key];
                }
                """);
    }

    @Test
    void shortNamedTupleAndPairStyleIndicesUseFiniteSlotRules() {
        accepted("""
                fnc get(): float {
                    const T pair = tuple(3.0, 4.0);
                    const index = 0;
                    return pair[index];
                }
                """);
        accepted("""
                fnc get(): int {
                    const T pair = tuple(3, "second");
                    return pair[0];
                }
                """);
        denied("""
                fnc bad(int index): int {
                    const T pair = tuple(3, "second");
                    return pair[index];
                }
                """);
        denied("""
                fnc bad(): float {
                    const T pair = tuple(3.0, 4.0);
                    return pair[2];
                }
                """);
    }

    @Test
    void constantTupleIndexVariablePreservesHeterogeneousSlotType() {
        accepted("""
                fnc get(): string {
                    const Pair p = tuple(3.0, "second");
                    const index = 1;
                    return p[index];
                }
                """);
    }

    @Test
    void emptyClosedAggregateCannotBecomeUncheckedUnknownValue() {
        denied("""
                fnc bad(string key): int {
                    const T empty = record{};
                    return empty[key];
                }
                """);
        denied("""
                fnc bad(int index): int {
                    const empty = tuple();
                    return empty[index];
                }
                """);
    }

    @Test
    void classesAdmitOnlyVisibleDataFieldsNotPrivateFieldsOrMethods() {
        accepted("""
                define class Point as
                    pub val float x = 3.0;
                    pub val float y = 4.0;
                end
                fnc read(Point point, string key): float {
                    return point[key];
                }
                """);
        denied("""
                define class Secret as
                    private val int hidden = 1;
                    pub val int shown = 2;
                end
                fnc bad(Secret secret, string key): int {
                    return secret[key];
                }
                """);
        denied("""
                define class Point as
                    pub val float x = 3.0;
                    pub fnc move(): float { return self.x; }
                end
                fnc bad(Point point): float {
                    return point["move"];
                }
                """);
    }

    @Test
    void finiteTuplesRejectIndexedWritesEvenThroughMutableBindings() {
        deniedWithMessage("""
                fnc bad(): void {
                    val pair = tuple(3, 4);
                    pair[0] = 8;
                    return;
                }
                """, "indexed assignment is not allowed");
        deniedWithMessage("""
                fnc bad(int index): void {
                    val pair = tuple(3, 4);
                    pair[index] = 8;
                    return;
                }
                """, "indexed assignment is not allowed");
    }

    @Test
    void computedKeysAreReadsNotDynamicFieldCreationOrReadonlyEscape() {
        denied("""
                fnc bad(): void {
                    const T item = record{x: 3.0};
                    const key = "x";
                    item[key] = 5.0;
                    return;
                }
                """);
        denied("""
                fnc bad(): void {
                    const T item = infer<T> struct{x: 3.0};
                    item["new"] = 5.0;
                    return;
                }
                """);
    }
}
