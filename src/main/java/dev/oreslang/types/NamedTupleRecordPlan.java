package dev.oreslang.types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Closed structural conversion metadata only. Input indexes refer to already
 * evaluated tuple slots, never expression trees. The type checker must prove
 * nominal Pair identity and exact payload type, then supply its identifier
 * validator. Lowering must separately prove ownership, lifetime and drops.
 */
public final class NamedTupleRecordPlan {
    private static final int MAX_SLOTS = 4096;
    public enum Duplicates { REJECT, FIRST, LAST }

    /** null key means unknown or unnamed; such a slot cannot enter a Record. */
    public record Slot<T>(String key, T payloadType) {}
    public record Field<T>(String key, T payloadType, int sourceSlot) {}
    public record Plan<T>(List<Field<T>> fields, List<Integer> discarded) {
        public Plan {
            fields = List.copyOf(fields);
            discarded = List.copyOf(discarded);
        }
    }

    private NamedTupleRecordPlan() {}

    public static <T> Plan<T> bind(
            List<Slot<T>> slots, Duplicates policy, Predicate<String> validIdentifier) {
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(validIdentifier, "identifier validator");
        if (slots.size() > MAX_SLOTS)
            throw new IllegalArgumentException("E_RECORD_SHAPE_LIMIT");
        var selected = new LinkedHashMap<String, Field<T>>();
        for (int i = 0; i < slots.size(); i++) {
            Slot<T> slot = Objects.requireNonNull(slots.get(i), "slot");
            String key = slot.key();
            if (key == null || key.isEmpty() || !validIdentifier.test(key))
                throw new IllegalArgumentException("E_RECORD_KEY");
            T type = Objects.requireNonNull(slot.payloadType(), "resolved payload type");
            if (selected.containsKey(key)) {
                if (policy == Duplicates.REJECT)
                    throw new IllegalArgumentException("E_RECORD_DUPLICATE: " + key);
                if (policy == Duplicates.FIRST) continue;
            }
            // LinkedHashMap retains first encounter order even on replacement.
            selected.put(key, new Field<>(key, type, i));
        }
        var fields = new ArrayList<>(selected.values());
        boolean[] retained = new boolean[slots.size()];
        for (Field<T> field : fields) retained[field.sourceSlot()] = true;
        var discarded = new ArrayList<Integer>();
        for (int i = 0; i < retained.length; i++) if (!retained[i]) discarded.add(i);
        return new Plan<>(fields, discarded);
    }
}
