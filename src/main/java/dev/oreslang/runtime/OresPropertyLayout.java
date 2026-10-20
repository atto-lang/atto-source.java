package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.nio.charset.StandardCharsets;

/**
 * An immutable, closed property layout with a single slot for each tagged key.
 *
 * <p>For future compiler lowering, the slot indices can address an AoS field
 * or a column in SoA/AoSoA. Layouts never allocate an extra synchronized map
 * of values, and never turn closed aggregates into dynamic guest Maps.
 */
public final class OresPropertyLayout {
    /** Initial bounded compiler-key metadata quotas, not legacy field quotas. */
    public static final int MAX_FIELDS = 4096;
    public static final int MAX_ENCODED_KEY_BYTES = 1_048_576;
    private static final int V1_KEY_FRAME_BYTES = 6;

    private final List<OresPropertyKey> keys;
    private final Map<OresPropertyKey, Integer> slotByKey;

    private OresPropertyLayout(Collection<? extends OresPropertyKey> declaredKeys,
                               boolean canonicalOrder) {
        Objects.requireNonNull(declaredKeys, "property keys");
        ArrayList<OresPropertyKey> order = new ArrayList<>();
        long totalEncodedBytes = 0;
        // Do not rely on Collection.size(): a hostile/unstable host collection
        // might lie. Enforce limits while taking the independent snapshot.
        for (OresPropertyKey key : declaredKeys) {
            Objects.requireNonNull(key, "property key");
            if (order.size() >= MAX_FIELDS) {
                throw new IllegalArgumentException("static property layout exceeds 4096 fields");
            }
            long entryBytes = V1_KEY_FRAME_BYTES + (long) key.name().getBytes(StandardCharsets.UTF_8).length;
            if (entryBytes > MAX_ENCODED_KEY_BYTES - totalEncodedBytes) {
                throw new IllegalArgumentException("static property layout exceeds 1 MiB tagged-key budget");
            }
            totalEncodedBytes += entryBytes;
            order.add(key);
        }
        if (canonicalOrder) order.sort(OresPropertyKey.CANONICAL_ORDER);
        Map<OresPropertyKey, Integer> slots = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            OresPropertyKey key = order.get(i);
            if (slots.putIfAbsent(key, i) != null) {
                throw new IllegalArgumentException("duplicate logical property key: " + key);
            }
        }
        this.keys = List.copyOf(order);
        this.slotByKey = Collections.unmodifiableMap(slots);
    }

    /** Declaration-order slots, suitable for nominal class/struct ABI lowering. */
    public static OresPropertyLayout declared(Collection<? extends OresPropertyKey> keys) {
        return new OresPropertyLayout(keys, false);
    }

    /** Stable order independent of source field insertion order. */
    public static OresPropertyLayout canonical(Collection<? extends OresPropertyKey> keys) {
        return new OresPropertyLayout(keys, true);
    }

    public int size() { return keys.size(); }
    public List<OresPropertyKey> keys() { return keys; }
    public OresPropertyKey keyAt(int slot) { return keys.get(slot); }

    public OptionalInt findSlot(OresPropertyKey key) {
        Objects.requireNonNull(key, "property key");
        Integer slot = slotByKey.get(key);
        return slot == null ? OptionalInt.empty() : OptionalInt.of(slot);
    }

    public int requireSlot(OresPropertyKey key) {
        return findSlot(key).orElseThrow(
                () -> new IllegalArgumentException("unknown property key: " + key));
    }
}
