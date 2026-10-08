package dev.oreslang.runtime;

import java.util.Objects;

/** Strong managed handle. No numeric address, manual free, or transport authority. */
public final class OresPointer {
    private final Object target;

    public OresPointer(Object target) {
        this.target = Objects.requireNonNull(target, "pointer target");
    }

    public Object target() { return target; }
}
