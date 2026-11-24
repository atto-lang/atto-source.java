package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import dev.oreslang.types.TypeChecker;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Source;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Exact decimal arithmetic must never regress to binary64 or host interop. */
final class HighPrecisionDecimalTest {
    private static String eval(String program) throws Exception {
        TypeChecker.check(Parser.parse(program));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Source source = Source.newBuilder(OresLanguage.ID, program, "decimal-contract.ores")
                .mimeType(OresLanguage.MIME_TYPE).build();
        try (Context context = Context.newBuilder(OresLanguage.ID)
                .allowAllAccess(false).out(out).build()) {
            context.eval(source);
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void decimalAdditionAndMultiplicationAreExact() throws Exception {
        assertEquals("0.3|1|1E-38", eval("""
                pub routine main(): void {
                  stdio.stdout.write(0.1d + 0.2d);
                  stdio.stdout.write("|");
                  stdio.stdout.write(9007199254740993d - 9007199254740992d);
                  stdio.stdout.write("|");
                  stdio.stdout.write(0.0000000000000000001d * 0.0000000000000000001d);
                  return;
                }
                """));
    }

    @Test
    void terminatingDivisionRetainsDigitsBeyondDecimal128() throws Exception {
        assertEquals("61728394506172839450617283945061728394.5", eval("""
                pub routine main(): void {
                  stdio.stdout.write(123456789012345678901234567890123456789d / 2d);
                  return;
                }
                """));
    }

    @Test
    void repeatingQuotientRequiresAnExplicitRoundingContext() {
        Exception error = assertThrows(Exception.class, () -> eval("""
                pub routine main(): void {
                  stdio.stdout.write(1d / 3d);
                  return;
                }
                """));
        assertTrue(error.toString().contains("NONTERMINATING_REQUIRES_CONTEXT"),
                error::toString);
    }

    @Test
    void decimalReturnTypesAndRuntimeTypeTestsUseDistinctDomain() throws Exception {
        assertEquals("true|true", eval("""
                fnc value(): decimal { return 3d; }
                pub routine main(): void {
                  stdio.stdout.write(value() == 3d);
                  stdio.stdout.write("|");
                  stdio.stdout.write(3d == 3);
                  return;
                }
                """));
    }

    @Test
    void integerWideningIntoDecimalLocalsAndParametersPreservesDigits() throws Exception {
        assertEquals("9007199254740993|9007199254740993", eval("""
                fnc identity(decimal x): decimal { return x; }
                pub routine main(): void {
                  val decimal local = 9007199254740993;
                  stdio.stdout.write(local);
                  stdio.stdout.write("|");
                  stdio.stdout.write(identity(9007199254740993));
                  return;
                }
                """));
    }

    @Test
    void integerOverflowDoesNotWrapIntoAValidNumericResult() {
        for (String expression : new String[] {
                "9223372036854775807 + 1",
                "-9223372036854775808 - 1",
                "9223372036854775807 * 2",
                "-(-9223372036854775808)"
        }) {
            Exception error = assertThrows(Exception.class, () -> eval("""
                    pub routine main(): void {
                      stdio.stdout.write(%s);
                      return;
                    }
                    """.formatted(expression)), expression);
            assertTrue(error.toString().contains("overflow"),
                    () -> "expected explicit overflow for " + expression + ", got " + error);
        }
    }

    @Test
    void binaryFloatAndDecimalCannotMixImplicitly() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        fnc invalid(): decimal { return 0.1d + 0.1; }
                        """)));
        assertTrue(error.getMessage().contains("explicit conversion"));
    }

    @Test
    void overflowingBinaryFloatLiteralsFailClosed() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("fnc invalid(): float { return 1e9999; }"));
        assertTrue(error.getMessage().contains("overflows binary64"));
    }

    @Test
    void tinyNonzeroBinaryFloatLiteralsMustNotSilentlyBecomeZero() {
        for (String literal : new String[] { "1e-9999", "-1e-9999", "2e-324" }) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse("fnc invalid(): float { return " + literal + "; }"));
            assertTrue(error.getMessage().contains("underflows binary64"),
                    () -> "expected underflow diagnostic for " + literal + ": " + error);
        }
        // Valid signed zeros and the smallest normal/subnormal finite values
        // must not be rejected by the underflow hardening.
        assertDoesNotThrow(() -> Parser.parse("""
                fnc zeros(): float { return 0e-9999 + -0.0; }
                fnc subnormal(): float { return 5e-324; }
                """));
    }

    @Test
    void relationalNanIsUnorderedAndLargeIntFloatComparisonsAreExact() throws Exception {
        assertEquals("false|false|false|true", eval("""
                pub routine main(): void {
                  val float nan = 0.0 / 0.0;
                  stdio.stdout.write(nan < 1.0);
                  stdio.stdout.write("|");
                  stdio.stdout.write(nan >= 1.0);
                  stdio.stdout.write("|");
                  stdio.stdout.write(nan == nan);
                  stdio.stdout.write("|");
                  stdio.stdout.write(9007199254740993 > 9007199254740992.0);
                  return;
                }
                """));
    }

    @Test
    void infinityAndSignedZeroFollowIeeeEqualityAndOrder() throws Exception {
        assertEquals("true|true|false", eval("""
                pub routine main(): void {
                  val float infinity = 1.0 / 0.0;
                  stdio.stdout.write(infinity == infinity);
                  stdio.stdout.write("|");
                  stdio.stdout.write(-0.0 == 0.0);
                  stdio.stdout.write("|");
                  stdio.stdout.write(-0.0 < 0.0);
                  return;
                }
                """));
    }

    @Test
    void pureSourceDecimalAndMatrixModulesMultiplyWithoutPrecisionLoss() throws Exception {
        Path entry = Files.createTempFile("ores-decimal-matrix-", ".ores");
        try {
            Files.writeString(entry, """
                    import * as decimals from "std/decimal";
                    import module matrix from "std/matrix";

                    fnc left_matrix(): Array<decimal> {
                      let mut Array<decimal> values = new Array<decimal>();
                      values.add(0.1d); values.add(0.2d); values.add(0.3d); values.add(0.4d);
                      return values;
                    }

                    fnc filled(int count, decimal value): Array<decimal> {
                      let mut Array<decimal> values = new Array<decimal>();
                      for (let i = 0; i < count; i = i + 1) {
                        values.add(value);
                      }
                      return values;
                    }

                    pub routine main(): void {
                      let mut Array<decimal> b = new Array<decimal>();
                      b.add(1d); b.add(2d); b.add(3d); b.add(4d);

                      val Result<Array<decimal>, String> product = matrix.multiply(2, 2, 2, left_matrix(), b);
                      val Array<decimal> result = product.expect("matrix multiplication failed");
                      stdio.stdout.write(result.get(0));
                      stdio.stdout.write("|");
                      stdio.stdout.write(result.get(1));
                      stdio.stdout.write("|");
                      stdio.stdout.write(result.get(2));
                      stdio.stdout.write("|");
                      stdio.stdout.write(result.get(3));
                      stdio.stdout.write("|");
                      stdio.stdout.write(decimals.sum(result));
                      stdio.stdout.write("|");
                      stdio.stdout.write(matrix.multiply(0, 2, 2, new Array<decimal>(), new Array<decimal>()).is_err());
                      stdio.stdout.write("|");
                      stdio.stdout.write(decimals.divide(1d, 7d, 50, "HALF_EVEN"));
                      stdio.stdout.write("|");
                      stdio.stdout.write(decimals.round(2.5d, 1, "HALF_EVEN"));
                      stdio.stdout.write("|");
                      stdio.stdout.write(decimals.sqrt(4d, 50, "HALF_EVEN"));
                      stdio.stdout.write("|");
                      stdio.stdout.write(matrix.trace(2, left_matrix()).expect("trace failed"));
                      stdio.stdout.write("|");
                      val Array<decimal> eye = matrix.identity(2).expect("identity failed");
                      stdio.stdout.write(eye.get(0));
                      stdio.stdout.write("|");
                      stdio.stdout.write(matrix.frobenius_norm(1, 1, filled(1, 4d), 50, "HALF_EVEN").expect("norm failed"));
                      stdio.stdout.write("|");
                      stdio.stdout.write(matrix.frobenius_norm(1, 1, filled(1, 4d), 0, "HALF_EVEN").is_err());
                      stdio.stdout.write("|");
                      stdio.stdout.write(matrix.multiply(50, 50, 50, filled(2500, 1d), filled(2500, 1d)).is_err());
                      return;
                    }
                    """);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            var run = LinkedProgramRunner.run(
                    entry, IsolatePolicy.developer(), ExecutionProfile.serverJit(),
                    out, err);
            assertTrue(run.units().containsKey("@std/decimal.ores"));
            assertTrue(run.units().containsKey("@std/matrix.ores"));
            assertEquals("0.7|1.0|1.5|2.2|5.4|true|0.14285714285714285714285714285714285714285714285714|2|2|0.5|1|4|true|true", out.toString(StandardCharsets.UTF_8),
                    () -> "guest stderr: " + err.toString(StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(entry);
        }
    }
}
