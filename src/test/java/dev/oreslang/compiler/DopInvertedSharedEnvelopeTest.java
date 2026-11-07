package dev.oreslang.compiler;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared Java/LLVM narrow two-i64 SOURCE envelope.
 * Java execution assertions are SoA HOST-REFERENCE ONLY, not Truffle guests.
 */
final class DopInvertedSharedEnvelopeTest {
    private static String source(int capacity, String body) {
        return "define inverted struct Row as let value: int; token: int; end\n"
            + "fnc main(): int {\n"
            + "let rows = InvertedArray<Row>.with_capacity(" + capacity + ");\n"
            + body + "\n}\n";
    }

    private static void denied(int capacity, String body) {
        var error = assertThrows(IllegalArgumentException.class,
            () -> OresCompiler.compileDopInvertedScalarI64(source(capacity,body)));
        assertTrue(error.getMessage().contains("DOP_INVERTED_V1_REJECTED"),
            "unsupported program must fail during typed compilation");
    }

    @Test
    void sharedCapacityBoundariesAreExactlyOneThroughTwoHundredFiftySix() {
        assertEquals(256, DopInvertedScalarCompilerV1.MAX_SHARED_CAPACITY);
        assertEquals(1024, DopInvertedScalarCompilerV1.MAX_SHARED_OPERATIONS);

        denied(0, "return rows.get(0, value);");
        denied(257, "rows.push(1,2); return rows.get(0, value);");
        denied(4096, "rows.push(1,2); return rows.get(0, value);");

        var one = OresCompiler.compileDopInvertedScalarI64(source(1,
            "rows.push(42, 7); return rows.get(0, value);"));
        assertEquals(42L, one.executeHostReference());
        assertEquals(1, one.capacity());

        var large = OresCompiler.compileDopInvertedScalarI64(source(256,
            "rows.push(42, 7); return rows.get(0, value);"));
        assertEquals(256, large.capacity());
        assertEquals(42L, large.executeHostReference());
    }

    private static String operations(int count) {
        if (count < 2) throw new IllegalArgumentException("count must allow a return");
        StringBuilder body = new StringBuilder();
        int current = 0;
        // Every pair is a valid push/swap-erase round trip. The final push
        // leaves exactly one row, and the final return observes 42.
        while (count - current > 2) {
            body.append("rows.push(1, 2);\nrows.erase_swap(0);\n");
            current += 2;
        }
        body.append("rows.push(42, 7);\nreturn rows.get(0, value);\n");
        return body.toString();
    }

    @Test
    void exactlyOneThousandTwentyFourSourceOperationsAreAccepted() {
        String code = operations(1024);
        var plan = OresCompiler.compileDopInvertedScalarI64(source(1,code));
        assertEquals(1024, plan.instructions().size());
        assertEquals(42L, plan.executeHostReference());
        assertEquals(42L, plan.executeHostReference());
    }

    @Test
    void OneThousandTwentyFiveOperationsAreRejectedBeforeExecution() {
        // Use a forged-but-well-formed extra expression rather than relying on
        // lexical errors to trigger the compiler operation-budget guard.
        String body = operations(1024).replace(
            "return rows.get(0, value);",
            "rows.set(0, value, 42); return rows.get(0, value);");
        denied(1,body);
    }

    @Test
    void sharedBoundsRemainFailClosedForDynamicDenseRead() {
        var program = OresCompiler.compileDopInvertedScalarI64(source(1,
            "rows.push(42,7); return rows.get(1,value);"));
        assertThrows(IllegalArgumentException.class,program::executeHostReference);
        denied(1, "rows.push(42,7); rows.erase_swap(1); return rows.get(0,value);");
    }
}
