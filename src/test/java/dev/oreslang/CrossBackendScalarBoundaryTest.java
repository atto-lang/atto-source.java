package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shared scalar source-admission floor used by the LLVM, Cranelift, WASM
 * and .NET contract suites. Compiler/typecheck assertions are NOT runtime
 * parity or native guest execution receipts.
 */
final class CrossBackendScalarBoundaryTest {
    private static void accept(String expression) {
        final String program = "pub fnc main(): int { return " + expression + "; }";
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(program), expression);
    }

    private static void reject(String expression) {
        final String program = "pub fnc main(): int { return " + expression + "; }";
        assertThrows(RuntimeException.class, () -> OresCompiler.parseAndTypeCheck(program),
                expression);
    }

    @Test
    void parenthesizedPrecedenceAndSignedScalarsAreTypeChecked() {
        accept("4 + 6 * (5 - 1)");
        accept("-(7 * 3)");
        accept("9223372036854775807 - 1");
        accept("2 + 3 * 4");
    }

    @Test
    void unsupportedOrUnboundScalarExpressionsFailClosed() {
        reject("undeclared_value");
        reject("9223372036854775808");
        reject("1 @ 3");
    }
}
