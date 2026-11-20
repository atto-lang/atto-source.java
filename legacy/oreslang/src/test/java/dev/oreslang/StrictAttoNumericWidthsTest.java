package dev.oreslang;

import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.SourceUnitDiagnosticException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact source scalar widths must survive the legacy Primitive.INT/FLOAT
 * erasure boundary in the strict source profile. These are compiler-negative
 * tests, not native guest AOT execution.
 */
final class StrictAttoNumericWidthsTest {

    private static String compare(String left, String right, String operator) {
        return "pub fnc same(" + left + " a, " + right
                + " b): bool { return a " + operator + " b; }";
    }

    @Test
    void rejectsMixedSignedIntegerWidthsForEveryCanonicalEqualityAlias() {
        for (String operator : new String[]{"eq", "neq", "!eq"}) {
            for (String[] pair : new String[][]{
                    {"i8", "i16"}, {"i16", "i32"}, {"i32", "i64"},
                    {"u8", "u16"}, {"u16", "u32"}, {"u32", "u64"},
                    {"i32", "u32"}, {"u32", "i32"},
                    {"int", "i32"}, {"uint", "u32"}, {"int", "uint"}}) {
                String src = compare(pair[0], pair[1], operator);
                IllegalArgumentException problem = assertThrows(
                        IllegalArgumentException.class,
                        () -> OresCompiler.parseAndTypeCheckStrictAtto(src),
                        operator + " / " + pair[0] + " / " + pair[1]);
                assertTrue(problem.getMessage().contains(
                        "ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES"), problem::getMessage);
            }
        }
    }

    @Test
    void rejectsMixedFloatingPrecisionButAllowsExactMatchingPrecision() {
        for (String operator : new String[]{"eq", "neq", "!eq"}) {
            var mismatch = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheckStrictAtto(
                            compare("f32", "f64", operator)));
            assertTrue(mismatch.getMessage().contains(
                    "ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES"), mismatch::getMessage);
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto(
                    compare("f32", "f32", operator)));
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto(
                    compare("i64", "i64", operator)));
            assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto(
                    compare("u32", "u32", operator)));
        }
    }

    @Test
    void detectsMixedWidthsInDeclaredLocalAliasesAndDirectCallReturns() {
        for (String src : new String[]{
                """
                pub fnc bad(i32 a, i64 b): bool {
                    val i32 first = a;
                    val i64 second = b;
                    return first eq second;
                }
                """,
                """
                type Small = i32;
                pub fnc bad(Small a, i64 b): bool {
                    return a neq b;
                }
                """,
                """
                pub fnc get(i32 x): i32 { return x; }
                pub fnc bad(i64 y): bool { return get(1) eq y; }
                """
        }) {
            var error = assertThrows(IllegalArgumentException.class,
                    () -> OresCompiler.parseAndTypeCheckStrictAtto(src));
            assertTrue(error.getMessage().contains(
                    "ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES"), error::getMessage);
        }
    }

    @Test
    void detectsImportedFieldWidthsAfterCrossUnitClassMetadataLinking() {
        Map<String, String> fields = Map.of(
                "src/model.atto", """
                        pub define class Sized as
                          pub val i32 count;
                        end
                        """,
                "src/app.atto", """
                        import class Sized from "./model.atto";
                        pub fnc compare(Sized options, i64 other): bool {
                          return options.count eq other;
                        }
                        """);
        var mismatch = assertThrows(SourceUnitDiagnosticException.class,
                () -> new IncrementalCompiler().compileStrictAtto(fields));
        assertEquals("src/app.atto", mismatch.unitId());
        assertTrue(mismatch.detail().contains(
                "ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES"), mismatch::getMessage);

        Map<String, String> valid = Map.of(
                "src/model.atto", fields.get("src/model.atto"),
                "src/app.atto", fields.get("src/app.atto").replace("i64 other", "i32 other"));
        assertEquals(2, new IncrementalCompiler().compileStrictAtto(valid).units().size());
    }

    @Test
    void ambiguousErasedNumericValueDoesNotBecomeExactWidthProof() {
        // No inferred width may be invented for a generic numeric return path.
        // A future generic exact-width instantiation must carry provenance
        // before strict numeric equality admission is widened.
        String src = """
                pub fnc same(i32 x): bool {
                  return (x / 2) eq x;
                }
                """;
        var error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheckStrictAtto(src));
        assertTrue(error.getMessage().contains(
                "ATTO_STRICT_UNRESOLVED_NUMERIC_WIDTH")
                || error.getMessage().contains("ATTO_STRICT_INCOMPATIBLE_EQUALITY_TYPES"),
                error::getMessage);
    }

    @Test
    void legacyComparisonAndMixedArithmeticBehaviorRemainOptInCompatible() {
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(
                compare("i32", "i64", "==")));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto(
                compare("i32", "i32", "eq")));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheckStrictAtto(
                "pub fnc same(): bool { return 7 eq 8; }"));
    }
}
