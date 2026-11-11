package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Return and generic equality boundaries must retain strict scalar identity. */
final class StrictAttoNumericReturnTest {

    private static void rejects(String source, String code) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto(source));
        assertTrue(failure.getMessage().contains(code), failure::getMessage);
    }

    @Test
    void rejectsImplicitNarrowingOrWideningOfReturnedInteger() {
        rejects("""
                pub fnc bad(i64 source): i32 {
                  return source;
                }
                """, "ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES");
        rejects("""
                pub fnc bad(i32 source): i64 {
                  return source;
                }
                """, "ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto("""
                pub fnc good(i32 source): i32 {
                  return source;
                }
                """));
    }

    @Test
    void rejectsDifferentFloatReturnPrecision() {
        rejects("""
                pub fnc bad(f64 source): f32 {
                  return source;
                }
                """, "ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto("""
                pub fnc good(f32 source): f32 {
                  return source;
                }
                """));
    }

    @Test
    void allowsLiteralSpecializationInTypedReturnAndRejectsOverflow() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto("""
                pub fnc good(): i8 { return 127; }
                """));
        rejects("""
                pub fnc bad(): i8 { return 128; }
                """, "ATTO_STRICT_NUMERIC_LITERAL_OUT_OF_RANGE");
    }

    @Test
    void legacyReturnWideningRemainsAvailableOnlyOutsideStrictProfile() {
        String source = """
                pub fnc f(i64 value): i32 { return value; }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        rejects(source, "ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES");
    }

    @Test
    void genericEqualityNeedsMonomorphizationOrStaticallyProvenContract() {
        rejects("""
                pub fnc compare<T>(T left, T right): bool {
                  return left eq right;
                }
                """, "ATTO_STRICT_UNRESOLVED_EQUALITY_TYPE");
    }

    @Test
    void typedLocalAliasPreservesReturnWidth() {
        rejects("""
                pub fnc bad(i32 value): i64 {
                  val i32 alias = value;
                  return alias;
                }
                """, "ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES");
    }
}
