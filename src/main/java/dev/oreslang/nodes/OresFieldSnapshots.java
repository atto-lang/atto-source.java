package dev.oreslang.nodes;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Consistent shallow snapshot of an OresObject's mutable field slots.
 *
 * Callers must use the same field-map monitor for every mutation. Traversal
 * (including guest callbacks, cleanup, and recursive capability inspection)
 * happens after this helper releases the monitor.
 */
final class OresFieldSnapshots {
    private OresFieldSnapshots() {}

    static <K, V> Map<K, V> copy(Map<K, V> fields) {
        synchronized (fields) {
            return new LinkedHashMap<>(fields);
        }
    }

    // The actual affine class-field storage transition. Source-level
    // permission, visibility and LET checks happen before this call.
    static Object take(
            Map<String, Object> fields,
            String className,
            String fieldName,
            Object uninitialized,
            Object moved) {
        synchronized (fields) {
            if (!fields.containsKey(fieldName)) {
                throw new IllegalArgumentException("unknown class field '" + fieldName + "'");
            }
            Object current = fields.get(fieldName);
            if (current == uninitialized) {
                throw new IllegalArgumentException(
                        "class field '" + className + "." + fieldName
                                + "' is read before initialization");
            }
            if (current == moved) {
                throw new IllegalStateException(
                        "class field '" + className + "." + fieldName
                                + "' was already moved");
            }
            fields.put(fieldName, moved);
            return current;
        }
    }
}
