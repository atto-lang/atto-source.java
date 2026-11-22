package dev.oreslang;

import dev.oreslang.runtime.NumericErrorAbi;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

final class NumericErrorAbiTest {
    @Test
    void arithmeticOverflowIdentityMatchesPinnedRegistry() throws Exception {
        assertEquals(0x0001_0001, NumericErrorAbi.ARITHMETIC_OVERFLOW);
        assertEquals(0x0001_0002, NumericErrorAbi.DIVISION_BY_ZERO);
        assertNotEquals(NumericErrorAbi.ARITHMETIC_OVERFLOW, NumericErrorAbi.DIVISION_BY_ZERO);

        String registry = Files.readString(
                Path.of("contracts", "v1", "numeric-error-abi.json"),
                StandardCharsets.UTF_8);
        assertTrue(registry.contains("\"schema\": \"oreslang.numeric-error-abi.v1\""));
        assertTrue(registry.contains("\"type\": \"ArithmeticOverflowError\""));
        assertTrue(registry.contains("\"type\": \"DivisionByZeroError\""));
        assertTrue(registry.contains("\"code\": " + NumericErrorAbi.DIVISION_BY_ZERO));
        assertTrue(registry.contains("\"code\": " + NumericErrorAbi.ARITHMETIC_OVERFLOW));
        assertTrue(registry.contains("\"parent\": \"Error\""));
        assertTrue(registry.contains("\"trap_or_abort_alone_does_not_satisfy_typed_catch\": true"));
    }
}
