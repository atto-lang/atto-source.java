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
        reject("val float value = 3;");
        reject("val decimal value = 3;");
        reject("val complex value = 3.0;");
        reject("val int value = 3.5;");
    }

    @Test
    void staticSourceRejectsMixedArithmeticComparisonAndEquality() {
        reject("val float value = 3 + 1.5;");
        reject("val float value = 3.0 * 2;");
        reject("val bool value = 3.0 < 4;");
        reject("val bool value = 3 == 3.0;");
    }

    @Test
    void staticSourceKeepsHomogeneousArithmeticAndBooleanComparisons() {
        accept("val int value = 3 + 4;");
        accept("val float value = 3.0 + 4.0;");
        accept("val bool value = 3 < 4;");
        accept("val bool value = 3.0 == 4.0;");
    }

    private static void reject(String statement) {
        assertThrows(IllegalArgumentException.class, () ->
                TypeChecker.check(Parser.parse(source(statement))), statement);
    }

    private static void accept(String statement) {
        assertDoesNotThrow(() ->
                TypeChecker.check(Parser.parse(source(statement))), statement);
    }

    private static String source(String statement) {
        return "pub routine main(): void { " + statement + " return; }";
    }
}
