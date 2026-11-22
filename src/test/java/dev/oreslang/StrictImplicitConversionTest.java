package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.Types;
import dev.oreslang.types.Types.Primitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0 strict implicit-conversion tests: source assignment and arithmetic may
 * not silently change numeric representation or precision.
 */
final class StrictImplicitConversionTest {
    @Test
    void coreAssignmentRelationNeverWidensNumbers() {
        for (Primitive from : new Primitive[]{
                Primitive.INT, Primitive.FLOAT, Primitive.DECIMAL, Primitive.COMPLEX}) {
            for (Primitive to : new Primitive[]{
                    Primitive.INT, Primitive.FLOAT, Primitive.DECIMAL, Primitive.COMPLEX}) {
                assertEquals(from == to, Types.isAssignable(from, to),
                        () -> from + " -> " + to);
                assertEquals(from == to ? from : Types.Unknown.INSTANCE,
                        Types.numericJoin(from, to));
            }
        }
    }

    @Test
    void staticSourceRejectsImplicitNumericAssignment() {
        reject("val float value = 3;", "initializer for value has type");
        reject("val decimal value = 3;", "initializer for value has type");
        reject("val complex value = 3.0;", "initializer for value has type");
        reject("val int value = 3.5;", "initializer for value has type");
    }

    @Test
    void staticSourceRejectsMixedArithmeticComparisonAndEquality() {
        reject("val float value = 3 + 1.5;", "identical numeric operand types");
        reject("val float value = 3.0 * 2;", "identical numeric operand types");
        reject("val bool value = 3.0 < 4;", "comparison requires the same numeric type");
        // Main keeps mixed numeric equality legal in the legacy profile and
        // rejects it in the strict AttoLang profile.
        rejectStrictAtto("val bool value = 3 eq 3.0;", "ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES");
    }

    @Test
    void staticSourceKeepsHomogeneousArithmeticAndBooleanComparisons() {
        accept("val int value = 3 + 4;");
        accept("val float value = 3.0 + 4.0;");
        accept("val bool value = 3 < 4;");
        accept("val bool value = 3.0 == 4.0;");
    }

    private static void reject(String statement, String expectedDiagnostic) {
        // Parsing is a prerequisite, not proof that the numeric type checker rejected
        // the program. A malformed fixture must never count as a successful negative.
        var parsed = assertDoesNotThrow(() -> Parser.parse(source(statement)),
                "negative fixture must parse: " + statement);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(parsed),
                "numeric checker must reject: " + statement);
        assertNotNull(failure.getMessage(), "rejection must carry a diagnostic");
        assertTrue(failure.getMessage().contains(expectedDiagnostic),
                () -> "rejected for the wrong reason: " + failure.getMessage()
                        + " (expected: " + expectedDiagnostic + ")");
    }

    private static void rejectStrictAtto(String statement, String expectedDiagnostic) {
        var parsed = assertDoesNotThrow(() -> Parser.parseStrictAtto(source(statement)),
                "negative fixture must parse: " + statement);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypesWithDiagnostics(parsed, true),
                "strict numeric checker must reject: " + statement);
        assertTrue(String.valueOf(failure.getMessage()).contains(expectedDiagnostic),
                () -> "rejected for the wrong reason: " + failure.getMessage()
                        + " (expected: " + expectedDiagnostic + ")");
    }

    private static void accept(String statement) {
        assertDoesNotThrow(() ->
                TypeChecker.check(Parser.parse(source(statement))), statement);
    }

    private static String source(String statement) {
        return "pub routine main(): void { " + statement + " return; }";
    }
}
