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
    void classesDoNotPermitUncheckedComputedPrivateOrPublicFieldAccess() {
        denied("""
                define class Sample as
                    pub val int x;
                    val string secret;
                end
                fnc bad(Sample sample, string field): int {
                    return sample[field];
                }
                """);
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
