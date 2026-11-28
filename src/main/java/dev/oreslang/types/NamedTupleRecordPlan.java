package dev.oreslang.types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
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

    /**
     * Reverse shape plan. A known declared/constructed order must be a
     * permutation of the exact Record members. Otherwise the fallback is
     * ascending UTF-16 code-unit order, independent of host hash iteration.
     * This does not construct runtime Pairs or transfer payload ownership.
     */
    public static <T> List<Slot<T>> reverse(
            Map<String, T> members, List<String> knownOrder, Predicate<String> validIdentifier) {
        Objects.requireNonNull(members, "members");
        Objects.requireNonNull(validIdentifier, "identifier validator");
        if (members.size() > MAX_SLOTS)
            throw new IllegalArgumentException("E_RECORD_SHAPE_LIMIT");
        for (var entry : members.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isEmpty() || !validIdentifier.test(key))
                throw new IllegalArgumentException("E_RECORD_KEY");
            Objects.requireNonNull(entry.getValue(), "resolved payload type");
        }
        List<String> order;
        if (knownOrder == null) {
            order = new ArrayList<>(members.keySet());
            order.sort(String::compareTo);
        } else {
            if (knownOrder.size() != members.size())
                throw new IllegalArgumentException("E_RECORD_ORDER");
            order = new ArrayList<>(knownOrder);
            if (!new HashSet<>(order).equals(members.keySet()))
                throw new IllegalArgumentException("E_RECORD_ORDER");
        }
        var result = new ArrayList<Slot<T>>(order.size());
        for (String key : order) result.add(new Slot<>(key, members.get(key)));
        return List.copyOf(result);
    }

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
