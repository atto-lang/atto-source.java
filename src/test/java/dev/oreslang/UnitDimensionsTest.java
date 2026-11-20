package dev.oreslang;

import dev.oreslang.types.UnitDimensions;
import dev.oreslang.types.UnitDimensions.Dimension;
import dev.oreslang.types.UnitDimensions.MeasureType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class UnitDimensionsTest {
    private static MeasureType f64(Dimension dimension) {
        return new MeasureType("f64", dimension);
    }

    @Test void dimensionAlgebraIsExactAndCanonical() {
        Dimension meters = UnitDimensions.base("Meter");
        Dimension seconds = UnitDimensions.base("Second");
        Dimension speed = meters.divide(seconds);
        assertEquals("Meter^1*Second^-1", speed.canonical());
        assertEquals(meters, speed.multiply(seconds));
        assertEquals(UnitDimensions.dimensionless(), meters.divide(meters));
        assertEquals(UnitDimensions.dimensionless(), meters.pow(0));
        assertEquals(speed, new Dimension(Map.of("Second", -1, "Meter", 1)));
        assertEquals(speed.hashCode(),
                new Dimension(Map.of("Meter", 1, "Second", -1)).hashCode());
    }

    @Test void incompatibleAdditionAndSubtractionAreTypeErrors() {
        MeasureType meters = f64(UnitDimensions.base("Meter"));
        MeasureType seconds = f64(UnitDimensions.base("Second"));
        assertSame(meters, meters.add(f64(UnitDimensions.base("Meter"))));
        assertThrows(IllegalArgumentException.class, () -> meters.add(seconds));
        assertThrows(IllegalArgumentException.class, () -> seconds.subtract(meters));
    }

    @Test void multiplicationDivisionAndPowersCarryOnlyStaticDimensions() {
        var distance = f64(UnitDimensions.base("Meter"));
        var time = f64(UnitDimensions.base("Second"));
        var speed = distance.divide(time);
        assertEquals("Meter^1*Second^-1", speed.dimension().canonical());
        assertEquals(distance, speed.multiply(time));
        assertEquals("Meter^2*Second^-2", speed.power(2).dimension().canonical());
    }

    @Test void numericPromotionsAreNotImplicitEvidence() {
        var meters = f64(UnitDimensions.base("Meter"));
        var sameDimensionDifferentScalar =
                new MeasureType("i64", UnitDimensions.base("Meter"));
        assertThrows(IllegalArgumentException.class,
                () -> meters.add(sameDimensionDifferentScalar));
        assertThrows(IllegalArgumentException.class,
                () -> meters.multiply(sameDimensionDifferentScalar));
        assertThrows(IllegalArgumentException.class,
                () -> new MeasureType("String", UnitDimensions.dimensionless()));
    }

    @Test void dimensionMetadataIsImmutable() {
        var input = new HashMap<String, Integer>();
        input.put("Meter", 1);
        var dimension = new Dimension(input);
        input.put("Meter", 2);
        assertEquals("Meter^1", dimension.canonical());
        assertThrows(UnsupportedOperationException.class,
                () -> dimension.powers().put("Second", 1));
    }

    @Test void malformedOrOverBudgetDimensionsFailClosed() {
        for (String invalid : new String[] {"", "a.b", "_Meter", "Meter/Second",
                "x".repeat(65), "a" + ((char) 0) + "b"}) {
            assertThrows(IllegalArgumentException.class, () -> UnitDimensions.base(invalid));
        }
        assertThrows(IllegalArgumentException.class,
                () -> new Dimension(Map.of("Meter", 33)));
        assertThrows(IllegalArgumentException.class,
                () -> UnitDimensions.base("Meter").pow(33));
        assertThrows(IllegalArgumentException.class,
                () -> UnitDimensions.base("Meter").pow(Integer.MIN_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> UnitDimensions.base("Meter").pow(32)
                        .multiply(UnitDimensions.base("Meter")));
        var many = new HashMap<String, Integer>();
        for (int i = 0; i < UnitDimensions.MAX_BASE_UNITS + 1; ++i) {
            many.put("Unit" + i, 1);
        }
        assertThrows(IllegalArgumentException.class, () -> new Dimension(many));
    }

    @Test void zeroPowersCanonicalizeBeforeDimensionBudget() {
        var manyZeros = new HashMap<String, Integer>();
        for (int i = 0; i < 100; ++i) manyZeros.put("Unit" + i, 0);
        assertTrue(new Dimension(manyZeros).dimensionless());
    }
}
