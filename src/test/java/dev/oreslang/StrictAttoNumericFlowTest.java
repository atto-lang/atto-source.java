package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Strict numeric equality must not be laundered through permissive legacy
 * assignment or invocation typing. Compile-negative only; native guest proof
 * is a separate gate.
 */
final class StrictAttoNumericFlowTest {

    private static void rejects(String code, String diagnostic) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto(code));
        assertTrue(failure.getMessage().contains(diagnostic), failure::getMessage);
    }

    @Test
    void rejectsCrossWidthSourceInTypedLocalBeforeEquality() {
        rejects("""
                pub fnc bad(i64 source): bool {
                  val i32 narrower = source;
                  return narrower eq narrower;
                }
                """, "ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto("""
                pub fnc good(i32 source): bool {
                  val i32 copy = source;
                  return copy eq source;
                }
                """));
    }

    @Test
    void rejectsCrossWidthDirectCallArgumentEvenWhenReturnSeemsExact() {
        rejects("""
                pub fnc consume(i32 value): i32 { return value; }
                pub fnc bad(i64 source): bool {
                  return consume(source) eq consume(source);
                }
                """, "ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto("""
                pub fnc consume(i32 value): i32 { return value; }
                pub fnc good(i32 source): bool {
                  return consume(source) eq consume(source);
                }
                """));
    }

    @Test
    void rejectsReassignmentAcrossErasedNumericWidths() {
        rejects("""
                pub fnc bad(i64 source): bool {
                  let i32 target = 7;
                  target = source;
                  return target eq target;
                }
                """, "ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES");
    }

    @Test
    void boundsSignedAndUnsignedIntegerLiteralsBeforeAdmission() {
        rejects("""
                pub fnc bad(): bool {
                  val i8 value = 128;
                  return value eq value;
                }
                """, "ATTO_STRICT_NUMERIC_LITERAL_OUT_OF_RANGE");
        rejects("""
                pub fnc bad(): bool {
                  val u8 value = 256;
                  return value eq value;
                }
                """, "ATTO_STRICT_NUMERIC_LITERAL_OUT_OF_RANGE");
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto("""
                pub fnc good(): bool {
                  val u8 value = 255;
                  return value eq value;
                }
                """));
    }

    @Test
    void floatingLiteralsAllowIeeeRoundingButRejectOverflow() {
        // Untyped 0.1 becomes the nearest representable f32; it is not
        // required to be exactly representable as a binary float.
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto("""
                pub fnc good(): bool {
                  val f32 value = 0.1;
                  return value eq value;
                }
                """));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto("""
                pub fnc good(): bool {
                  val f32 value = 0.5;
                  return value eq value;
                }
                """));
        rejects("""
                pub fnc bad(): bool {
                  val f32 value = 1.0e40;
                  return value eq value;
                }
                """, "ATTO_STRICT_NUMERIC_LITERAL_OUT_OF_RANGE");
    }

    @Test
    void legacyFlowsRemainUnchangedWhileStrictRejectsImplicitNarrowing() {
        String source = """
                pub fnc legacy(i64 source): bool {
                  val i32 target = source;
                  return target == target;
                }
                """;
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        rejects(source.replace("==", "eq"), "ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES");
    }
}
