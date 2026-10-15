package dev.oreslang.runtime;

import java.util.Objects;

/** Strong managed handle. No numeric address, manual free, or transport authority. */
public final class OresPointer {
    // Null is the consumed/tombstone state; a guest Pointer never contains null.
    private Object target;

    public OresPointer(Object target) {
        this.target = Objects.requireNonNull(target, "pointer target");
    }

    /** Borrow only; static admission must keep a live loan until the use completes. */
    public synchronized Object target() {
        if (target == null) throw new IllegalStateException("Pointer<T> has already been unref'd");
        return target;
    }

    /** Unique unwrap: move the pointee without copying it and invalidate this handle. */
    public synchronized Object unref() {
        Object live = target();
        target = null;
        return live;
    }
}
