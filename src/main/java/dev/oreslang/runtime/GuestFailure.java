package dev.oreslang.runtime;

/** Explicit guest failure, distinct from host and scheduler control failures. */
public final class GuestFailure extends RuntimeException {
    public final String operation;
    public final Object value;

    public GuestFailure(String operation, Object value) {
        super(message(operation, value), null, true, false);
        this.operation = operation;
        this.value = value;
    }

    private static String message(String operation, Object value) {
        if (!java.util.Set.of("throw", "raise", "panic").contains(operation))
            throw new IllegalArgumentException("unknown failure operation: " + operation);
        if (!(value instanceof String || value instanceof Long || value instanceof Double || value instanceof Boolean))
            throw new IllegalArgumentException("failure payload must be an immutable scalar");
        return String.valueOf(value);
    }
}
