package dev.oreslang.types;

/** Runtime defense: Number is not an immutability or guest-ownership witness. */
public final class CopyScalarAdmission {
    private CopyScalarAdmission() { }

    public static boolean isImmutableNumber(Object value) {
        if (value == null) return false;
        Class<?> kind = value.getClass();
        return kind == Byte.class || kind == Short.class || kind == Integer.class
                || kind == Long.class || kind == Float.class || kind == Double.class
                || kind == java.math.BigInteger.class || kind == java.math.BigDecimal.class;
    }

    public static boolean isImmutableScalar(Object value) {
        return value instanceof String || value instanceof Boolean || isImmutableNumber(value);
    }
}
