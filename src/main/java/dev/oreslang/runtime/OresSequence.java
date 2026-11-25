package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicInteger;

/** Sequence storage with nonparking read loans for guest collection calls. */
public abstract class OresSequence extends ArrayList<Object> {
    // Positive counts are read loans; -1 denotes an active mutation.
    private final AtomicInteger accessState = new AtomicInteger();

    protected OresSequence() {
        super(0);
    }

    public abstract String kind();

    public final void acquireRead() {
        for (;;) {
            int state = accessState.get();
            if (state < 0 || state == Integer.MAX_VALUE) {
                throw new IllegalStateException(
                        "collection cannot acquire read loan while mutating or exhausted");
            }
            if (accessState.compareAndSet(state, state + 1)) return;
        }
    }

    public final void releaseRead() {
        for (;;) {
            int state = accessState.get();
            if (state <= 0) throw new IllegalStateException("unbalanced collection read loan");
            if (accessState.compareAndSet(state, state - 1)) return;
        }
    }

    protected final void acquireWrite() {
        if (!accessState.compareAndSet(0, -1)) {
            throw new IllegalStateException(
                    "collection mutation during map/filter/reduce read loan is forbidden");
        }
    }

    protected final void releaseWrite() {
        accessState.set(0);
    }

    @Override public boolean add(Object item) {
        acquireWrite();
        try { return super.add(item); }
        finally { releaseWrite(); }
    }

    @Override public void add(int index, Object item) {
        acquireWrite();
        try { super.add(index, item); }
        finally { releaseWrite(); }
    }

    @Override public boolean addAll(Collection<?> items) {
        acquireWrite();
        try { return super.addAll(items); }
        finally { releaseWrite(); }
    }

    @Override public boolean addAll(int index, Collection<?> items) {
        acquireWrite();
        try { return super.addAll(index, items); }
        finally { releaseWrite(); }
    }

    @Override public Object set(int index, Object item) {
        acquireWrite();
        try { return super.set(index, item); }
        finally { releaseWrite(); }
    }

    @Override public Object remove(int index) {
        acquireWrite();
        try { return super.remove(index); }
        finally { releaseWrite(); }
    }

    @Override public boolean remove(Object item) {
        acquireWrite();
        try { return super.remove(item); }
        finally { releaseWrite(); }
    }

    @Override public void clear() {
        acquireWrite();
        try { super.clear(); }
        finally { releaseWrite(); }
    }
}
