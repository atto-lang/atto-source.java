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
}
