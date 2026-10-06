package dev.oreslang.runtime;

import java.util.Objects;

/** One pull result from an rx-ores subscription. */
public record OresNotification<T>(Kind kind, T value) {
    public enum Kind { NEXT, COMPLETE }

    public OresNotification {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.NEXT) {
            Objects.requireNonNull(value, "NEXT notifications cannot carry standalone null values");
        } else if (value != null) {
            throw new IllegalArgumentException("COMPLETE notifications cannot carry a value");
        }
    }

    public static <T> OresNotification<T> next(T value) {
        return new OresNotification<>(Kind.NEXT, value);
    }

    public static <T> OresNotification<T> complete() {
        return new OresNotification<>(Kind.COMPLETE, null);
    }

    public boolean isNext() { return kind == Kind.NEXT; }
    public boolean isComplete() { return kind == Kind.COMPLETE; }
}
