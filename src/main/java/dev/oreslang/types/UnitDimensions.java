package dev.oreslang.types;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compile-time dimensional algebra for a future units-of-measure frontend.
 *
 * These are type-checker facts, not guest numeric objects or runtime unit
 * registries. A frontend must resolve unit declarations, check provenance and
 * attach a dimension to a concrete numeric type before calling this helper.
 * Backends may erase an admitted dimension after static verification.
 */
public final class UnitDimensions {
    private UnitDimensions() { }

    public static final int MAX_BASE_UNITS = 16;
    public static final int MAX_EXPONENT = 32;
    public static final int MAX_UNIT_NAME_LENGTH = 64;

    private static final Set<String> SCALARS = Set.of(
            "i8", "i16", "i32", "i64", "int",
            "u8", "u16", "u32", "u64", "uint",
            "f32", "f64", "float", "decimal");

    private static String checkedUnitName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_UNIT_NAME_LENGTH
                || !name.matches("[A-Za-z][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("invalid compile-time unit identifier");
        }
        return name;
    }

    /** An immutable canonical exponent vector. Zero powers are erased. */
    public record Dimension(Map<String, Integer> powers) {
        public Dimension {
            Objects.requireNonNull(powers, "dimension powers");
            var normalized = new TreeMap<String, Integer>();
            for (var entry : powers.entrySet()) {
                final String unit = checkedUnitName(entry.getKey());
                final Integer boxedPower = Objects.requireNonNull(
                        entry.getValue(), "dimension exponent");
                final int power = boxedPower;
                if (power < -MAX_EXPONENT || power > MAX_EXPONENT) {
                    throw new IllegalArgumentException("dimension exponent out of range");
                }
                if (power != 0) normalized.put(unit, power);
            }
            if (normalized.size() > MAX_BASE_UNITS) {
                throw new IllegalArgumentException("dimension basis exceeds compilation budget");
            }
            powers = Collections.unmodifiableMap(normalized);
        }

        public boolean dimensionless() {
            return powers.isEmpty();
        }

        public Dimension multiply(Dimension rhs) {
            Objects.requireNonNull(rhs, "right dimension");
            var merged = new TreeMap<>(powers);
            for (var entry : rhs.powers.entrySet()) {
                int combined = Math.addExact(merged.getOrDefault(entry.getKey(), 0),
                        entry.getValue());
                if (combined == 0) merged.remove(entry.getKey());
                else merged.put(entry.getKey(), combined);
            }
            return new Dimension(merged);
        }

        public Dimension divide(Dimension rhs) {
            return multiply(Objects.requireNonNull(rhs, "right dimension").pow(-1));
        }

        public Dimension pow(int exponent) {
            if (exponent < -MAX_EXPONENT || exponent > MAX_EXPONENT) {
                throw new IllegalArgumentException("unit power outside compilation budget");
            }
            var result = new TreeMap<String, Integer>();
            for (var entry : powers.entrySet()) {
                final int power = Math.multiplyExact(entry.getValue(), exponent);
                if (power != 0) result.put(entry.getKey(), power);
            }
            return new Dimension(result);
        }

        /** Stable diagnostic/IR identity, not a serialized authority token. */
        public String canonical() {
            if (powers.isEmpty()) return "1";
            var result = new StringBuilder();
            for (var entry : powers.entrySet()) {
                if (!result.isEmpty()) result.append('*');
                result.append(entry.getKey()).append('^').append(entry.getValue());
            }
            return result.toString();
        }
    }

    public static Dimension dimensionless() {
        return new Dimension(Map.of());
    }

    public static Dimension base(String name) {
        return new Dimension(Map.of(checkedUnitName(name), 1));
    }

    /**
     * A numeric type with dimension annotations. No guest value is held.
     * Numeric promotion, exact rounding and unit scale conversions are
     * independent compiler obligations; until specified, reject mismatches.
     */
    public record MeasureType(String scalar, Dimension dimension) {
        public MeasureType {
            if (!SCALARS.contains(scalar)) {
                throw new IllegalArgumentException("unit annotation requires a known numeric scalar");
            }
            Objects.requireNonNull(dimension, "measure dimension");
        }

        public MeasureType add(MeasureType rhs) {
            requireSame(rhs, "addition");
            return this;
        }

        public MeasureType subtract(MeasureType rhs) {
            requireSame(rhs, "subtraction");
            return this;
        }

        public MeasureType multiply(MeasureType rhs) {
            requireScalar(rhs);
            return new MeasureType(scalar, dimension.multiply(rhs.dimension));
        }

        public MeasureType divide(MeasureType rhs) {
            requireScalar(rhs);
            return new MeasureType(scalar, dimension.divide(rhs.dimension));
        }

        public MeasureType power(int exponent) {
            return new MeasureType(scalar, dimension.pow(exponent));
        }

        public void requireSame(MeasureType rhs, String operation) {
            requireScalar(rhs);
            if (!dimension.equals(rhs.dimension)) {
                throw new IllegalArgumentException("incompatible units for " + operation
                        + ": " + dimension.canonical() + " vs " + rhs.dimension.canonical());
            }
        }

        private void requireScalar(MeasureType rhs) {
            Objects.requireNonNull(rhs, "right measure");
            if (!scalar.equals(rhs.scalar)) {
                throw new IllegalArgumentException(
                        "numeric promotions must be proven before dimension algebra");
            }
        }
    }
}
