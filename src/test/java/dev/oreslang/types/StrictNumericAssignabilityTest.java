package dev.oreslang.types;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StrictNumericAssignabilityTest {
    @Test
    void implicitNumericWideningIsRejected() {
        Types.Primitive[] numeric = {
                Types.Primitive.INT, Types.Primitive.FLOAT,
                Types.Primitive.DECIMAL, Types.Primitive.COMPLEX
        };
        for (Types.Primitive from : numeric) {
            for (Types.Primitive to : numeric) {
                assertEquals(from == to, Types.isAssignable(from, to),
                        () -> "Unexpected implicit numeric conversion: " + from + " to " + to);
            }
        }
    }
}
