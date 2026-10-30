package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Numeric type names must describe real runtime representations, never aliases
 * that silently erase precision or range guarantees.
 */
final class NumericPrecisionAdmissionTest {
    @Test
    void floatAndF64AreTheImplementedBinary64Surface() {
        assertDoesNotThrow(() -> TypeChecker.check(Parser.parse("""
                fnc identity(float value): float { return value; }
                fnc explicit(f64 value): f64 { return value; }
                """)));
    }

    @Test
    void f32FailsClosedUntilBinary32LoweringExists() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc identity(f32 value): f32 { return value; }
                        """)));
        assertTrue(failure.getMessage() != null
                        && failure.getMessage().contains("binary32 runtime/lowering is not implemented"),
                () -> "unexpected f32 diagnostic: " + failure.getMessage());
    }

    @Test
    void bigintDoesNotAliasFixedWidthInt() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc identity(bigint value): bigint { return value; }
                        """)));
        assertTrue(failure.getMessage() != null
                        && failure.getMessage().contains("refusing to alias it to int"),
                () -> "unexpected bigint diagnostic: " + failure.getMessage());
    }

    @Test
    void decimalDoesNotAliasFloatOrInt() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc identity(decimal value): decimal { return value; }
                        """)));
        assertTrue(failure.getMessage() != null
                        && failure.getMessage().contains("exact base-10 runtime/lowering is not implemented"),
                () -> "unexpected decimal diagnostic: " + failure.getMessage());
    }
}
