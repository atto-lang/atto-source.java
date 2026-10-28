package dev.oreslang;

import dev.oreslang.runtime.StrictIntegerArithmetic;
import dev.oreslang.runtime.ExecutionProfile;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.LinkedProgramRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class StrictIntegerArithmeticTest {
    @TempDir Path temp;

    @Test
    void divisionEdgesAndEuclideanModulusAreDefined() {
        assertEquals(-1L, StrictIntegerArithmetic.rem(-9, 4));
        assertEquals(1L, StrictIntegerArithmetic.rem(9, -4));
        assertEquals(3L, StrictIntegerArithmetic.mod(-9, 4));
        assertEquals(3L, StrictIntegerArithmetic.mod(-9, -4));
        assertEquals(1L, StrictIntegerArithmetic.mod(9, -4));
        assertEquals(0L, StrictIntegerArithmetic.rem(Long.MIN_VALUE, -1));
        assertEquals(0L, StrictIntegerArithmetic.mod(Long.MIN_VALUE, -1));
        assertEquals(Long.MAX_VALUE, StrictIntegerArithmetic.mod(-1, Long.MIN_VALUE));
        assertThrows(ArithmeticException.class, () -> StrictIntegerArithmetic.rem(1, 0));
        assertThrows(ArithmeticException.class, () -> StrictIntegerArithmetic.mod(1, 0));
    }

    @Test
    void powerHasCheckedSigned64Semantics() {
        assertEquals(1L, StrictIntegerArithmetic.pow(0, 0));
        assertEquals(0L, StrictIntegerArithmetic.pow(0, 63));
        assertEquals(-1L, StrictIntegerArithmetic.pow(-1, Long.MAX_VALUE));
        assertEquals(-8L, StrictIntegerArithmetic.pow(-2, 3));
        assertEquals(Long.MIN_VALUE, StrictIntegerArithmetic.pow(-2, 63));
        assertThrows(ArithmeticException.class, () -> StrictIntegerArithmetic.pow(2, 63));
        assertThrows(ArithmeticException.class, () -> StrictIntegerArithmetic.pow(Long.MIN_VALUE, 2));
        assertThrows(IllegalArgumentException.class, () -> StrictIntegerArithmetic.pow(2, -1));
    }

    @Test
    void checkedIntToFloatDoesNotMistakeSaturatingLongCastForExactness() {
        assertEquals(0.0d, StrictIntegerArithmetic.toFloatExact(0));
        assertEquals(42.0d, StrictIntegerArithmetic.toFloatExact(42));
        assertEquals((double) Long.MIN_VALUE,
                StrictIntegerArithmetic.toFloatExact(Long.MIN_VALUE));
        assertEquals(9007199254740992.0d,
                StrictIntegerArithmetic.toFloatExact(9007199254740992L));
        assertThrows(ArithmeticException.class,
                () -> StrictIntegerArithmetic.toFloatExact(9007199254740993L));
        assertThrows(ArithmeticException.class,
                () -> StrictIntegerArithmetic.toFloatExact(Long.MAX_VALUE));
        assertThrows(ArithmeticException.class,
                () -> StrictIntegerArithmetic.toFloatExact(Long.MIN_VALUE + 1));
    }

    @Test
    void randomizedRemainderAndModMatchIndependentBigIntegerOracle() {
        Random random = new Random(0x0A51);
        for (int i = 0; i < 10_000; i++) {
            long dividend = random.nextLong();
            long divisor = random.nextLong();
            if (divisor == 0) continue;
            BigInteger expectedRemainder = BigInteger.valueOf(dividend)
                    .remainder(BigInteger.valueOf(divisor));
            BigInteger expectedMod = expectedRemainder.signum() < 0
                    ? expectedRemainder.add(BigInteger.valueOf(divisor).abs())
                    : expectedRemainder;
            assertEquals(expectedRemainder.longValueExact(),
                    StrictIntegerArithmetic.rem(dividend, divisor));
            assertEquals(expectedMod.longValueExact(),
                    StrictIntegerArithmetic.mod(dividend, divisor));
            assertEquals(dividend ^ divisor, StrictIntegerArithmetic.bitXor(dividend, divisor));
        }
    }

    @Test
    void guestCoreLibraryExportsNamedMigrationShims() throws Exception {
        Path entry = temp.resolve("arithmetic.ores");
        Files.writeString(entry, """
                import * as numeric from "ores.core/numeric";
                pub routine main(): void {
                  stdio.println(numeric.rem_int(-9, 4));
                  stdio.println(numeric.mod_int(-9, 4));
                  stdio.println(numeric.mod_int(-9, -4));
                  stdio.println(numeric.bit_xor_int(5, 3));
                  stdio.println(numeric.bit_xor_int(-1, 0));
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out,
                new ByteArrayOutputStream());
        assertEquals("-1\n3\n3\n6\n-1\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void guestIntegerNamedOperationsExecuteWithExactIntTypes() throws Exception {
        Path entry = temp.resolve("integer-named.ores");
        Files.writeString(entry, """
                pub routine main(): void {
                  stdio.println(Integer.rem(-9, 4));
                  stdio.println(Integer.mod(-9, 4));
                  stdio.println(Integer.mod(-9, -4));
                  stdio.println(Integer.pow(-2, 3));
                  stdio.println(Integer.pow(0, 0));
                  stdio.println(Integer.bitXor(5, 3));
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out,
                new ByteArrayOutputStream());
        assertEquals("-1\n3\n3\n-8\n1\n6\n", out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void guestIntegerNamedOperationsRejectBadStaticCallShapes() throws Exception {
        for (String invalidCall : java.util.List.of(
                "Integer.mod(2)",
                "Integer.pow(2.5, 2)",
                "Integer.bitXor(true, 1)",
                "Integer.rem(2, 1, 4)")) {
            Path entry = temp.resolve("reject-" + invalidCall.length() + "-"
                    + Math.abs(invalidCall.hashCode()) + ".ores");
            Files.writeString(entry,
                    "pub routine main(): void { stdio.println("
                            + invalidCall + "); return; }");
            assertThrows(IllegalArgumentException.class,
                    () -> LinkedProgramRunner.validate(entry), invalidCall);
        }
    }

    @Test
    void guestIntToFloatExactIsStaticallyTypedAndChecksPrecision() throws Exception {
        Path entry = temp.resolve("to-float.ores");
        Files.writeString(entry, """
                pub routine main(): void {
                  val float exactly = Integer.toFloatExact(42);
                  val float min = Integer.toFloatExact(-9223372036854775808);
                  stdio.println(exactly);
                  return;
                }
                """);
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(entry));
        Path invalid = temp.resolve("to-float-lossy.ores");
        Files.writeString(invalid, """
                pub routine main(): void {
                  val float wrong = Integer.toFloatExact(9007199254740993);
                  return;
                }
                """);
        assertDoesNotThrow(() -> LinkedProgramRunner.validate(invalid),
                "lossy conversion is a checked runtime error, not static literal narrowing");
        assertThrows(org.graalvm.polyglot.PolyglotException.class, () ->
                LinkedProgramRunner.run(invalid, IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(), Set.of(), Map.of(),
                        new ByteArrayOutputStream(), new ByteArrayOutputStream()));
    }

    @Test
    void guestIntegerNamedOperationsTrapOnInvalidArithmetic() throws Exception {
        for (String invalidCall : java.util.List.of(
                "Integer.mod(2, 0)",
                "Integer.pow(2, 63)",
                "Integer.pow(2, -1)")) {
            Path entry = temp.resolve("trap-" + invalidCall.length() + "-"
                    + Math.abs(invalidCall.hashCode()) + ".ores");
            Files.writeString(entry,
                    "pub routine main(): void { stdio.println("
                            + invalidCall + "); return; }");
            assertThrows(org.graalvm.polyglot.PolyglotException.class, () -> {
                LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                        ExecutionProfile.serverJit(), Set.of(), Map.of(),
                        new ByteArrayOutputStream(), new ByteArrayOutputStream());
            }, invalidCall);
        }
    }
}
