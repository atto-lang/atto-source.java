package dev.oreslang;

import dev.oreslang.runtime.CheckedI64OverflowException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact shared/LLVM typed-effect identity. These are Java host-side signals,
 * not yet source-language guest Error allocation or actor CONTROL delivery.
 */
final class ExperimentalHirV1CheckedEffectIdentityTest {
    @Test
    void everyCheckedOperationHasTheStableNativeAbiTag() {
        var operations = new CheckedI64OverflowException[] {
                CheckedI64OverflowException.forOperator('+'),
                CheckedI64OverflowException.forOperator('-'),
                CheckedI64OverflowException.forOperator('*'),
                CheckedI64OverflowException.forNegation()
        };
        var names = new String[] {"add", "subtract", "multiply", "negate"};
        var tags = new int[] {1, 2, 3, 4};
        for (int i = 0; i < operations.length; ++i) {
            var failure = operations[i];
            assertEquals("ORES_I64_OVERFLOW", failure.errorCode());
            assertEquals("ArithmeticOverflowError", failure.guestErrorName());
            assertEquals("Throw", failure.effectKind());
            assertEquals(0x494F0001, failure.effectCode());
            assertEquals(names[i], failure.operation());
            assertEquals(tags[i], failure.operationTag());
            assertTrue(failure.getMessage().startsWith("ORES_I64_OVERFLOW: "));
            assertNull(failure.getCause(),
                    "host ArithmeticException and source operands must never be retained");
        }
    }

    @Test
    void invalidCheckedOperationCannotForgeAnOverflowIdentity() {
        for (char op : new char[] {'/', '%', '^', '&', '?'}) {
            assertThrows(IllegalArgumentException.class,
                    () -> CheckedI64OverflowException.forOperator(op));
        }
        assertEquals(CheckedI64OverflowException.OP_ADD, 1);
        assertEquals(CheckedI64OverflowException.OP_SUBTRACT, 2);
        assertEquals(CheckedI64OverflowException.OP_MULTIPLY, 3);
        assertEquals(CheckedI64OverflowException.OP_NEGATE, 4);
        assertEquals(CheckedI64OverflowException.EFFECT_CODE, 0x494F0001);
    }
}
