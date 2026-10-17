package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Raw-bit regressions for the finite complex128 scalar kernel.
 *
 * <p>The multiplication vectors below were independently generated with
 * exact rational arithmetic from decoded binary64 inputs. They specifically
 * cover cases where the former rounded-product/rounded-sum implementation
 * missed the once-rounded result by one or more ULPs.
 */
final class Complex128MathTest {
    @Test
    void multiplicationUsesOnceRoundedExactFiniteComponents() {
        long[][] cases = {
            {
                0xe3f29bc5bcfde58cL, 0x4abd911ee437aa55L,
                0x4221412dc53aee31L, 0x5bded97a82579a35L,
                0xe6ac950f914ba9f1L, 0xffe1f080f3c545f6L
            },
            {
                0xfeb81f310c0a5547L, 0x7491a13847d9c383L,
                0x14200ffb293adf44L, 0x8b594b606ac02fc6L,
                0xd2e83748f17fbb33L, 0x4a2311306132f144L
            },
            {
                0x470ffd47fbe15b87L, 0xe6bab7b474b49e79L,
                0x13a0e41b0048613aL, 0x32ceb57495c6b679L,
                0x5999a3b99918e286L, 0xba6c15e87e05e35cL
            },
            {
                0xb58119fb4a4c83f7L, 0x486781c11ed1f010L,
                0x5f57ba1a416745f4L, 0xf0726634c215eaf1L,
                0x78eb0821f4a9c5a6L, 0x67d16df84abfb2eeL
            },
            {
                0x15f4dd602821e38aL, 0x3b50fbabb3ff4dd4L,
                0xd4190c97c038cddaL, 0xacb1814f6bcbb9f6L,
                0xaa20552915534417L, 0xcf7a969a1d32b398L
            }
        };

        for (long[] c : cases) {
            Complex128Math.Value actual = Complex128Math.multiply(
                    Double.longBitsToDouble(c[0]),
                    Double.longBitsToDouble(c[1]),
                    Double.longBitsToDouble(c[2]),
                    Double.longBitsToDouble(c[3]));
            assertRawBits(c[4], actual.real());
            assertRawBits(c[5], actual.imaginary());
        }
    }

    @Test
    void divisionMatchesExtremeExponentIssue1042Bits() {
        Complex128Math.Value actual = Complex128Math.divide(
                -3.8267306126191597e229,
                -2.653330234432702e-108,
                4.996866008987324e199,
                5.230549533052834e-126);

        assertRawBits(0xc623550912a2da11L, actual.real());
        assertRawBits(0x02aa36a9fdaa506aL, actual.imaginary());
    }

    @Test
    void divisionRoundsBalancedAndSubnormalReferenceCasesExactly() {
        Complex128Math.Value balanced = Complex128Math.divide(
                -1.9297622926115045e138,
                1.5512392579051227e150,
                -1.4976784258940757e292,
                -1.8366476025309775e288);
        assertRawBits(0xa1a44d4536bbf436L, balanced.real());
        assertRawBits(0xa274356ca64e27d2L, balanced.imaginary());

        Complex128Math.Value subnormal = Complex128Math.divide(
                -4.273780448709542e-254,
                -2.0788903192019146e-253,
                -1.1323026279913343e49,
                -1.2990151610525855e55);
        assertRawBits(0x000b82005cde5227L, subnormal.real());
        assertRawBits(0x80025da2cf24c378L, subnormal.imaginary());
    }

    @Test
    void multiplicationPreservesMinimumSubnormalAndSignedZeroHint() {
        Complex128Math.Value tiny = Complex128Math.multiply(
                1.3e-162, 1.3e-162, 1.3e-162, 1.3e-162);
        assertRawBits(0x0000000000000000L, tiny.real());
        assertRawBits(0x0000000000000001L, tiny.imaginary());

        Complex128Math.Value zero = Complex128Math.multiply(-0.0, 0.0, 1.0, 0.0);
        assertRawBits(0x8000000000000000L, zero.real());
        assertRawBits(0x0000000000000000L, zero.imaginary());
    }

    private static void assertRawBits(long expected, double actual) {
        assertEquals(
                Long.toUnsignedString(expected, 16),
                Long.toUnsignedString(Double.doubleToRawLongBits(actual), 16));
    }
}
