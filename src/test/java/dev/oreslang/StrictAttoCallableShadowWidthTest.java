package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A local Fnc value shadows a free function with the same spelling.
 * The strict checker must not borrow the global declaration's numeric width
 * when reasoning about a call through the local value.
 */
final class StrictAttoCallableShadowWidthTest {

    @Test
    void locallyShadowedCallableCannotLaunderAnotherFunctionsWidth() {
        String source = """
                pub fnc get(i32 value): i32 { return value; }

                pub fnc bad(i64 source, i32 expected): bool {
                  val Fnc<i64, i64> get = |i64 value| -> {
                    return value;
                  };
                  return get(source) eq expected;
                }
                """;
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto(source));
        assertTrue(error.getMessage().contains("ATTO_STRICT_UNRESOLVED_NUMERIC_WIDTH"),
                error::getMessage);
    }

    @Test
    void unshadowedDirectCallCanUseVerifiedFreeFunctionReturnWidth() {
        String source = """
                pub fnc get(i32 value): i32 { return value; }

                pub fnc same(i32 source): bool {
                  return get(source) eq source;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto(source));
    }
}
