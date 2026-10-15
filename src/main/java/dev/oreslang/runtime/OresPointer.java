package dev.oreslang.runtime;

import java.util.Objects;

/** Strong managed handle. No numeric address, manual free, or transport authority. */
public final class OresPointer {
    private final Object target;

    public OresPointer(Object target) {
        Object checked = Objects.requireNonNull(target, "pointer target");
        // Defense in depth: the source type checker rejects Pointer<Pointer<T>>.
        // Host-created or malformed values must not bypass that invariant and
        // create an untracked chain of owning pointer capabilities.
        if (checked instanceof OresPointer) {
            throw new IllegalArgumentException("nested Pointer<T> runtime targets are forbidden");
        }
        this.target = checked;
    }

    public Object target() { return target; }
}
