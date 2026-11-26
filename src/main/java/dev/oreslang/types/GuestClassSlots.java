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

    public GuestClassSlots(Map<String, Object> initial) {
        slots = new LinkedHashMap<>(initial);
        view = Collections.unmodifiableMap(slots);
    }

    public Object get(String key) { return slots.get(key); }
    public boolean containsKey(Object key) { return slots.containsKey(key); }
    public int size() { return slots.size(); }
    public Map<String, Object> view() { return view; }

    public void initialize(String key, Object value) {
        if (!initializing) throw new IllegalStateException("E_COPY_LAYOUT: class initialization closed");
        slots.put(key, value);
    }

    public void finishInitialization() { initializing = false; }

    /** Caller has independently checked declaration mutability, visibility,
     * and initialization authority. No structural change to a class layout.
     */
    public void replace(String key, Object value) {
        if (!slots.containsKey(key)) throw new IllegalArgumentException("E_COPY_LAYOUT: unknown class slot " + key);
        slots.put(key, value);
    }
}
