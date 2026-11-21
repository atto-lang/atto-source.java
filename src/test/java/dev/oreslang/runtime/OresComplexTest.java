package dev.oreslang.runtime;

import java.math.BigInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class OresComplexTest {
    @Test
    void reusableRuntimeValueUsesHardenedKernel() {
        OresComplex quotient = new OresComplex(
                -3.8267306126191597e229, -2.653330234432702e-108)
                .div(new OresComplex(
                        4.996866008987324e199, 5.230549533052834e-126));
        assertEquals(
                Double.doubleToRawLongBits(-7.658261409724479e29),
                Double.doubleToRawLongBits(quotient.real()));
        assertEquals(
                Double.doubleToRawLongBits(8.016407798119527e-296),
                Double.doubleToRawLongBits(quotient.imaginary()));

        OresComplex minSubnormal =
                new OresComplex(1.3e-162, 1.3e-162)
                        .mul(new OresComplex(1.3e-162, 1.3e-162));
        assertEquals(0L, Double.doubleToRawLongBits(minSubnormal.real()));
        assertEquals(1L, Double.doubleToRawLongBits(minSubnormal.imaginary()));
    }

    @Test
    void actorFreezeTreatsComplexAsImmutableScalar() {
        OresComplex value = new OresComplex(3.0, -4.0);
        assertSame(value, ActorRuntime.freeze(value));
        assertEquals(32L, value.logicalBytes());
    }

    @Test
    void exactPromotionBoundaryIsSharedOutsideEvaluator() {
        assertEquals(
                new OresComplex(9_007_199_254_740_992.0, 0.0),
                OresComplex.of(9_007_199_254_740_992L));
        assertThrows(
                IllegalArgumentException.class,
                () -> OresComplex.of(9_007_199_254_740_993L));
        assertThrows(
                IllegalArgumentException.class,
                () -> OresComplex.of(new BigInteger("9007199254740993")));
    }
}
