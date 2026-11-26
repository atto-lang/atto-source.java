package dev.oreslang.types;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Encapsulated guest class slots. Initialization is a separate, bounded
 * lifetime from ordinary slot replacement. This is a mutation seam, not
 * a proof of shallow sharing or exclusive ownership.
 */
public final class GuestClassSlots {
    private final Map<String, Object> slots;
    private final Map<String, Object> view;
    private boolean initializing = true;
    private static final int MAX_READ_ONLY_LOANS = 65536;
    private int readOnlyLoans;

    /** Opaque, single-release runtime loan. Never expose to guest/FFI. */
    public static final class ReadOnlyLease implements AutoCloseable {
        private final GuestClassSlots owner;
        private boolean released;
        private ReadOnlyLease(GuestClassSlots owner) { this.owner = owner; }
        @Override public void close() { owner.release(this); }
    }

    /** Protect this concrete slot store, not any parent slot pointing at it.
     * Whole-graph admission and lifetime management are separate obligations.
     */
    public synchronized ReadOnlyLease retainReadOnly() {
        if (initializing) throw new IllegalStateException("E_COPY_LAYOUT: unpublished class");
        if (readOnlyLoans >= MAX_READ_ONLY_LOANS) throw new IllegalArgumentException("E_COPY_BUDGET: read-only loan limit");
        ReadOnlyLease lease = new ReadOnlyLease(this);
        readOnlyLoans++;
        return lease;
    }

    private synchronized void release(ReadOnlyLease lease) {
        if (lease.released || readOnlyLoans <= 0) throw new IllegalStateException("E_COPY_STALE: released read-only loan");
        lease.released = true;
        readOnlyLoans--;
    }

    public synchronized boolean isReadOnly() { return readOnlyLoans != 0; }

    public GuestClassSlots(Map<String, Object> initial) {
        slots = new LinkedHashMap<>(initial);
        view = Collections.unmodifiableMap(slots);
    }

    public Object get(String key) { return slots.get(key); }
    public boolean containsKey(Object key) { return slots.containsKey(key); }
    public int size() { return slots.size(); }
    public Map<String, Object> view() { return view; }

    public synchronized void initialize(String key, Object value) {
        if (!initializing) throw new IllegalStateException("E_COPY_LAYOUT: class initialization closed");
        slots.put(key, value);
    }

    public synchronized void finishInitialization() { initializing = false; }

    /** Caller has independently checked declaration mutability, visibility,
     * and initialization authority. No structural change to a class layout.
     */
    public synchronized void replace(String key, Object value) {
        if (readOnlyLoans != 0) throw new IllegalArgumentException("E_COPY_READONLY: shared class referent");
        if (!slots.containsKey(key)) throw new IllegalArgumentException("E_COPY_LAYOUT: unknown class slot " + key);
        slots.put(key, value);
    }
}
