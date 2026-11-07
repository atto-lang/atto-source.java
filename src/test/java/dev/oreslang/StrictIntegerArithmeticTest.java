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
    void guestNamedRemainderAndModulusMatchSigned64EdgeContract() throws Exception {
        // Real guest source, not a host arithmetic oracle: share the edge
        // expectations with the Cranelift narrow signed64 JIT profile.
        Path entry = temp.resolve("named-rem-mod-edges.ores");
        Files.writeString(entry, """
                pub routine main(): void {
                  stdio.println(Integer.rem(-9, 4));
                  stdio.println(Integer.rem(9, -4));
                  stdio.println(Integer.rem(-9223372036854775808, -1));
                  stdio.println(Integer.rem(-9223372036854775808, 9223372036854775807));
                  stdio.println(Integer.mod(-9, 4));
                  stdio.println(Integer.mod(9, -4));
                  stdio.println(Integer.mod(-9, -4));
                  stdio.println(Integer.mod(-9223372036854775808, -1));
                  stdio.println(Integer.mod(-1, -9223372036854775808));
                  stdio.println(Integer.mod(-9223372036854775808, 9223372036854775807));
                  stdio.println(Integer.bitXor(-9223372036854775808, 9223372036854775807));
                  return;
                }
                """);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        LinkedProgramRunner.run(entry, IsolatePolicy.developer(),
                ExecutionProfile.serverJit(), Set.of(), Map.of(), out,
                new ByteArrayOutputStream());
        assertEquals("-1\n1\n0\n-1\n3\n1\n3\n0\n"
                        + "9223372036854775807\n9223372036854775806\n-1\n",
                out.toString(StandardCharsets.UTF_8));
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
