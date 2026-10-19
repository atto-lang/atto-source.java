package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Semantic boundary between structural records and concrete inferred structs.
 *
 * <p>The infer keyword chooses the origin of a struct type. Mutation authority
 * remains a binding/borrow property; record readonly capability is independent.
 */
final class InferredStructBoundaryTest {
    private static void accepted(String source) {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse(source)));
    }

    private static void rejected(String source) {
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse(source)));
    }

    @Test
    void shortAndExplicitStructTypeCaptureNameTheExactGeneratedShape() {
        accepted("""
                fnc short_capture(): float {
                    const Point seed = infer struct{x: 3.0, y: 4.0};
                    const Point other = infer struct{y: 8.0, x: 7.0};
                    return other.x;
                }

                fnc explicit_capture(): float {
                    const Point seed = infer<Point> struct{x: 3.0, y: 4.0};
                    const Point other = infer struct{x: 7.0, y: 8.0};
                    return other.y;
                }
                """);
    }

    @Test
    void inferredStructIsNotFrozenByInferKeyword() {
        accepted("""
                fnc update(): float {
                    const Point seed = infer<Point> struct{x: 3.0, y: 4.0};
                    val Point mutable = infer struct{x: 5.0, y: 6.0};
                    mutable.x = 9.0;
                    return mutable.x;
                }
                """);
    }

    @Test
    void constBindingStillRejectsStructMutation() {
        rejected("""
                fnc bad(): void {
                    const Point value = infer<Point> struct{x: 3.0, y: 4.0};
                    value.x = 9.0;
                    return;
                }
                """);
    }

    @Test
    void explicitAnonymousStructUsesTheSameConcreteStructCategory() {
        accepted("""
                fnc update(): float {
                    val value = struct{x: float, y: float}{x: 3.0, y: 4.0};
                    value.y = 8.0;
                    return value.y;
                }
                """);
    }

    @Test
    void concreteStructShapeIsExactRatherThanRowWidthCompatible() {
        rejected("""
                fnc bad(): void {
                    const Point seed = infer<Point> struct{x: 1, y: 2};
                    const Point wider = infer struct{x: 1, y: 2, z: 3};
                    return;
                }
                """);
    }

    @Test
    void recordAndStructDoNotSilentlyEraseIntoEachOther() {
        rejected("""
                fnc bad(): void {
                    const Row row = infer<Row> record{x: 1, y: 2};
                    const Point point = infer<Point> struct{x: 1, y: 2};
                    const Row erased = point;
                    return;
                }
                """);
        rejected("""
                fnc bad(): void {
                    const Row row = infer<Row> record{x: 1, y: 2};
                    const Point point = infer<Point> struct{x: 1, y: 2};
                    const Point erased = row;
                    return;
                }
                """);
    }

    @Test
    void recordAndStructValueDomainsAreDisjointForEqualityChecks() {
        rejected("""
                fnc bad(): bool {
                    const Row row = infer<Row> record{x: 1, y: 2};
                    const Point point = infer<Point> struct{x: 1, y: 2};
                    return row == point;
                }
                """);
    }

    @Test
    void canonicalRecordDoesNotImplicitlyCreateANamedTypeAlias() {
        accepted("""
                fnc read(): int {
                    const row = record{x: 1, y: 2};
                    return row.x;
                }
                """);
        rejected("""
                fnc bad(): void {
                    const Row row = record{x: 1, y: 2};
                    return;
                }
                """);
    }

    @Test
    void recordsRemainStructuralAndCanonicalRecordBracesParse() {
        accepted("""
                fnc read(): int {
                    const Row seed = infer<Row> record{x: 1};
                    const Row wider = record{x: 2, y: 3};
                    return wider.x;
                }
                """);
    }
}
