package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Objects;

/**
 * Bounded storage for the built-in Oreslang Vector[T] in the interpreter.
 *
 * <p>Uses ArrayList's checked growth and keeps the declared logical capacity
 * separate from its private implementation capacity. Compilation remains
 * responsible for element types, moves, and projected borrow lifetimes; this
 * class does not make arbitrary Java aliases safe to mutate.</p>
 *
 * <p>The strong exception guarantee applies to quota failures: a rejected
 * append/insert/reservation leaves the elements and logical capacity intact.
 * Allocator failures are propagated before the logical reserve is published.</p>
 */
public final class OresVector extends ArrayList<Object> {
    /** ArrayList's largest practical backing allocation bound. */
    public static final int MAX_CAPACITY = Integer.MAX_VALUE - 8;

    private final int maxCapacity;
    private int reservedCapacity;

    public OresVector(int maxCapacity, int inlineCapacity) {
        super(0);
        if (maxCapacity < 0 || maxCapacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("Vector max_capacity out of range: " + maxCapacity);
        }
        if (inlineCapacity < 0 || inlineCapacity > maxCapacity) {
            throw new IllegalArgumentException("Vector inline_capacity exceeds max_capacity");
        }
        this.maxCapacity = maxCapacity;
        reserve(inlineCapacity);
    }

    public int maxCapacity() {
        return maxCapacity;
    }

    public int reservedCapacity() {
        return reservedCapacity;
    }

    public void reserve(int requiredCapacity) {
        if (requiredCapacity < 0 || requiredCapacity > maxCapacity) {
            throw new IllegalArgumentException(
                    "Vector capacity " + requiredCapacity + " exceeds max_capacity=" + maxCapacity);
        }
        if (requiredCapacity <= reservedCapacity) return;
        // Only commit the logical reservation after allocation succeeds.
        super.ensureCapacity(requiredCapacity);
        reservedCapacity = requiredCapacity;
    }

    @Override
    public void ensureCapacity(int requiredCapacity) {
        reserve(requiredCapacity);
    }

    private void admitAdditional(int additional) {
        if (additional < 0 || (long) size() + additional > maxCapacity) {
            throw new IllegalArgumentException("Vector growth exceeds max_capacity=" + maxCapacity);
        }
    }

    private void recordGrowth() {
        if (size() > reservedCapacity) reservedCapacity = size();
    }

    @Override
    public boolean add(Object value) {
        admitAdditional(1);
        boolean added = super.add(value);
        recordGrowth();
        return added;
    }

    @Override
    public void add(int index, Object value) {
        admitAdditional(1);
        super.add(index, value);
        recordGrowth();
    }

    @Override
    public boolean addAll(Collection<?> values) {
        Objects.requireNonNull(values, "Vector.addAll values");
        admitAdditional(values.size());
        boolean changed = super.addAll(values);
        if (changed) recordGrowth();
        return changed;
    }

    @Override
    public boolean addAll(int index, Collection<?> values) {
        Objects.requireNonNull(values, "Vector.addAll values");
        admitAdditional(values.size());
        boolean changed = super.addAll(index, values);
        if (changed) recordGrowth();
        return changed;
    }

    public void push(Object value) {
        add(value);
    }

    public Object pop() {
        if (isEmpty()) throw new IllegalStateException("Vector is empty");
        return remove(size() - 1);
    }
}
