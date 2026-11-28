package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

/** Host-only ODB1 frame/generation/tenant binding, NOT disk durability. */
final class DbWalFrameV1Test {
    private static void u16(byte[] f, int at, int n) {
        f[at] = (byte) n;
        f[at + 1] = (byte) (n >>> 8);
    }

    private static void u32(byte[] f, int at, int n) {
        u16(f, at, n);
        u16(f, at + 2, n >>> 16);
    }

    private static void checksum(byte[] f) {
        int a = 1, b = 0;
        for (int i = 0; i < f.length - 4; ++i) {
            a = (a + (f[i] & 255)) % 65521;
            b = (b + a) % 65521;
        }
        u16(f, f.length - 4, a);
        u16(f, f.length - 2, b);
    }

    private static byte[] frame(int tenant, int generation,
            int seq, int op, int kind, int keyLen, int valLen) {
        byte[] f = new byte[26 + keyLen + valLen + 4];
        f[0] = 79; f[1] = 68; f[2] = 66; f[3] = 49;
        f[4] = 1; f[5] = (byte) kind;
        u32(f, 6, tenant);
        u32(f, 10, generation);
        u32(f, 14, seq);
        u32(f, 18, op);
        u16(f, 22, keyLen);
        u16(f, 24, valLen);
        Arrays.fill(f, 26, f.length - 4, (byte) 0xA5);
        checksum(f);
        return f;
    }

    @Test void boundsAndPositiveContract() {
        assertDoesNotThrow(() ->
                DbWalFrameV1.requireMatching(frame(7, 4, 2, 99, 1, 1, 0), 7, 4, 99));
        assertDoesNotThrow(() ->
                DbWalFrameV1.requireMatching(frame(7, 4, Integer.MAX_VALUE,
                    Integer.MAX_VALUE, 1, 64, 1024), 7, 4, Integer.MAX_VALUE));
        assertDoesNotThrow(() ->
                DbWalFrameV1.requireMatching(frame(1, 1, 1, 1, 2, 1, 0), 1, 1, 1));
        assertThrows(IllegalArgumentException.class,
                () -> DbWalFrameV1.requireMatching(new byte[30], 7, 4, 99));
        assertThrows(IllegalArgumentException.class,
                () -> DbWalFrameV1.requireMatching(new byte[1119], 7, 4, 99));
    }

    @Test void rejectWrongOwnerOrCorruptWire() {
        byte[] original = frame(7, 4, 2, 99, 1, 2, 3);
        assertThrows(SecurityException.class,
                () -> DbWalFrameV1.requireMatching(original, 8, 4, 99));
        assertThrows(SecurityException.class,
                () -> DbWalFrameV1.requireMatching(original, 7, 5, 99));
        assertThrows(SecurityException.class,
                () -> DbWalFrameV1.requireMatching(original, 7, 4, 100));
        for (int i = 0; i < original.length; ++i) {
            byte[] corrupt = original.clone();
            corrupt[i] ^= 1;
            assertThrows(IllegalArgumentException.class,
                    () -> DbWalFrameV1.requireMatching(corrupt, 7, 4, 99),
                    "byte offset " + i);
        }
        byte[] wrongSequence = original.clone();
        u32(wrongSequence, 14, 0);
        checksum(wrongSequence);
        assertThrows(IllegalArgumentException.class,
                () -> DbWalFrameV1.requireMatching(wrongSequence, 7, 4, 99));
        byte[] wrongKind = original.clone();
        wrongKind[5] = 2; // a delete cannot carry a non-empty value
        checksum(wrongKind);
        assertThrows(IllegalArgumentException.class,
                () -> DbWalFrameV1.requireMatching(wrongKind, 7, 4, 99));
        byte[] maliciousHighBit = original.clone();
        maliciousHighBit[9] = (byte) 0x80;
        checksum(maliciousHighBit);
        assertThrows(IllegalArgumentException.class,
                () -> DbWalFrameV1.requireMatching(maliciousHighBit, 7, 4, 99));
    }
}
