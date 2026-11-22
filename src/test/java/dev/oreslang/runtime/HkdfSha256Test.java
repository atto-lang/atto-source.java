package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

final class HkdfSha256Test {
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void matchesRfc5869TestCaseOne() {
        byte[] ikm = new byte[22];
        java.util.Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = HEX.parseHex("000102030405060708090a0b0c");
        byte[] info = HEX.parseHex("f0f1f2f3f4f5f6f7f8f9");
        assertEquals("3cb25f25faacd57a90434f64d0362f2a"
                        + "2d2d0a90cf1a5a4c5db02d56ecc4c5bf"
                        + "34007208d5b887185865",
                HEX.formatHex(HkdfSha256.derive(ikm, salt, info, 42)));
    }

    @Test
    void rejectsOutOfBudgetWork() {
        assertThrows(IllegalArgumentException.class,
                () -> HkdfSha256.derive(new byte[1], new byte[0], new byte[0], 8161));
        assertThrows(IllegalArgumentException.class,
                () -> HkdfSha256.derive(new byte[0], new byte[0], new byte[0], 32));
        assertThrows(IllegalArgumentException.class,
                () -> HkdfSha256.derive(new byte[1], new byte[0], new byte[65537], 32));
    }
}
