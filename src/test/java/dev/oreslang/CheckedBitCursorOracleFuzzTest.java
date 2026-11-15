package dev.oreslang;

import dev.oreslang.runtime.CheckedBitCursor;
import dev.oreslang.runtime.CheckedBitCursor.Endian;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Independent BigInteger oracle for the exact statically linkable host decoder.
 * This is NOT AttoLang guest binary lowering or Native Image execution.
 */
final class CheckedBitCursorOracleFuzzTest {
    private static BigInteger reference(byte[] bytes, int offset, int width, Endian endian) {
        BigInteger bits = BigInteger.ZERO;
        if (endian == Endian.BIG) {
            for (int i = 0; i < width; i++) {
                int index = offset + i;
                int digit = ((bytes[index >>> 3] & 0xff) >>> (7 - (index & 7))) & 1;
                bits = bits.shiftLeft(1).or(BigInteger.valueOf(digit));
            }
        } else {
            for (int i = 0; i < width / 8; i++) {
                bits = bits.or(
                        BigInteger.valueOf(bytes[(offset >>> 3) + i] & 0xffL)
                                .shiftLeft(8 * i));
            }
        }
        return bits;
    }

    @Test
    void reproducibleSignedUnsignedEndianCursorOracleAcrossFourThousandCases() {
        Random random = new Random(0x5eedfa017L);
        for (int sample = 0; sample < 4000; sample++) {
            byte[] bytes = new byte[16];
            random.nextBytes(bytes);
            Endian endian = sample % 4 == 0 ? Endian.LITTLE : Endian.BIG;
            int width = endian == Endian.LITTLE
                    ? (1 + random.nextInt(8)) * 8
                    : 1 + random.nextInt(64);
            int remainingOffsetRange = bytes.length * 8 - width;
            int offset = endian == Endian.LITTLE
                    ? random.nextInt(remainingOffsetRange / 8 + 1) * 8
                    : random.nextInt(remainingOffsetRange + 1);
            BigInteger oracle = reference(bytes, offset, width, endian);
            BigInteger signedOracle = oracle.testBit(width - 1)
                    ? oracle.subtract(BigInteger.ONE.shiftLeft(width))
                    : oracle;

            byte[] external = bytes.clone();
            CheckedBitCursor unsigned = new CheckedBitCursor(external);
            unsigned.skipBits(offset);
            Arrays.fill(external, (byte) 0x5a);
            assertEquals(oracle.longValue(), unsigned.readUnsigned(width, endian),
                    "unsigned sample " + sample);
            assertEquals(offset + width, unsigned.positionBits(),
                    "unsigned position sample " + sample);

            CheckedBitCursor signed = new CheckedBitCursor(bytes);
            signed.skipBits(offset);
            assertEquals(signedOracle.longValueExact(), signed.readSigned(width, endian),
                    "signed sample " + sample);
            assertEquals(bytes.length * 8L - offset - width, signed.remainingBits(),
                    "remaining sample " + sample);
            long before = signed.positionBits();
            int invalidWidth = (int) signed.remainingBits() + 1;
            assertThrows(RuntimeException.class,
                    () -> signed.readUnsigned(invalidWidth, endian),
                    "read beyond remaining bits, sample " + sample);
            assertEquals(before, signed.positionBits(),
                    "rejected read must not advance cursor, sample " + sample);
        }
    }
}
