package dev.oreslang.types;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Backend-neutral constructor initialization state.
 *
 * <p>Each tracked field carries an assignment-count interval [min,max].
 * The upper bound is saturated at 2, where 2 means "two or more".
 * This is the semantic form behind the traditional MUST/MAY sets:
 * [0,0] uninitialized, [0,1] maybe initialized, [1,1] exactly once,
 * and any max=2 state may have multiple writes.</p>
 *
 * <p>The class is immutable so branch joins cannot accidentally mutate
 * sibling paths. Native and alternate frontends can mirror the same four
 * operations: blank, write, join, and successful-exit validation.</p>
 */
public final class ConstructorFlowState {
    public static final int MANY = 2;

    public record AssignmentCount(int min, int max) {
        public static final AssignmentCount UNINITIALIZED = new AssignmentCount(0, 0);
        public static final AssignmentCount EXACTLY_ONCE = new AssignmentCount(1, 1);

        public AssignmentCount {
            if (min < 0 || min > MANY || max < 0 || max > MANY || min > max) {
                throw new IllegalArgumentException("invalid constructor assignment interval ["
                        + min + "," + max + "]");
            }
        }

        public boolean definitelyInitialized() {
            return min >= 1;
        }

        public boolean maybeInitialized() {
            return max >= 1;
        }

        public boolean exactlyOnce() {
            return min == 1 && max == 1;
        }

        public boolean mayBeMultiple() {
            return max >= MANY;
        }

        public AssignmentCount write() {
            return new AssignmentCount(
                    Math.min(MANY, min + 1),
                    Math.min(MANY, max + 1));
        }

        public AssignmentCount join(AssignmentCount other) {
            Objects.requireNonNull(other, "other assignment count");
            return new AssignmentCount(
                    Math.min(min, other.min),
                    Math.max(max, other.max));
        }
    }

    private final LinkedHashMap<String, AssignmentCount> fields;

    private ConstructorFlowState(LinkedHashMap<String, AssignmentCount> fields) {
        this.fields = fields;
    }

    public static ConstructorFlowState blank(Collection<String> fieldNames) {
        LinkedHashMap<String, AssignmentCount> fields = new LinkedHashMap<>();
        for (String field : fieldNames) {
            if (fields.putIfAbsent(field, AssignmentCount.UNINITIALIZED) != null) {
                throw new IllegalArgumentException("duplicate constructor field '" + field + "'");
            }
        }
        return new ConstructorFlowState(fields);
    }

    public AssignmentCount count(String field) {
        AssignmentCount count = fields.get(field);
        if (count == null) throw new IllegalArgumentException("untracked constructor field '" + field + "'");
        return count;
    }

    public boolean definitelyInitialized(String field) {
        return count(field).definitelyInitialized();
    }

    public boolean maybeInitialized(String field) {
        return count(field).maybeInitialized();
    }

    /**
     * A class is fully initialized when each blank field has been written.
     * Immutable fields additionally require exactly one write; mutable fields
     * may have multiple writes. This is the publication proof for mixed
     * `val`/`let` classes.
     */
    public boolean completeWithFixedFields(java.util.Set<String> fixedFields) {
        Objects.requireNonNull(fixedFields, "fixed fields");
        if (!fields.keySet().containsAll(fixedFields)) {
            throw new IllegalArgumentException(
                    "fixed-field requirements contain an untracked constructor field");
        }
        for (Map.Entry<String, AssignmentCount> entry : fields.entrySet()) {
            AssignmentCount count = entry.getValue();
            if (!count.definitelyInitialized()) return false;
            if (fixedFields.contains(entry.getKey()) && !count.exactlyOnce()) return false;
        }
        return true;
    }

    public boolean completeExactlyOnce() {
        for (AssignmentCount count : fields.values()) {
            if (!count.exactlyOnce()) return false;
        }
        return true;
    }

    public ConstructorFlowState write(String field) {
        LinkedHashMap<String, AssignmentCount> next = new LinkedHashMap<>(fields);
        next.put(field, count(field).write());
        return new ConstructorFlowState(next);
    }

    public ConstructorFlowState join(ConstructorFlowState other) {
        Objects.requireNonNull(other, "other constructor state");
        if (!fields.keySet().equals(other.fields.keySet())) {
            throw new IllegalArgumentException("cannot join constructor states with different fields");
        }
        LinkedHashMap<String, AssignmentCount> joined = new LinkedHashMap<>();
        for (Map.Entry<String, AssignmentCount> entry : fields.entrySet()) {
            joined.put(entry.getKey(), entry.getValue().join(other.fields.get(entry.getKey())));
        }
        return new ConstructorFlowState(joined);
    }

    public static ConstructorFlowState join(List<ConstructorFlowState> states) {
        if (states.isEmpty()) return null;
        ConstructorFlowState joined = states.getFirst();
        for (int i = 1; i < states.size(); i++) joined = joined.join(states.get(i));
        return joined;
    }

    public Map<String, AssignmentCount> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    public List<String> incompleteFields() {
        List<String> incomplete = new ArrayList<>();
        for (Map.Entry<String, AssignmentCount> entry : fields.entrySet()) {
            if (!entry.getValue().exactlyOnce()) incomplete.add(entry.getKey());
        }
        return List.copyOf(incomplete);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConstructorFlowState state && fields.equals(state.fields);
    }

    @Override
    public int hashCode() {
        return fields.hashCode();
    }

    @Override
    public String toString() {
        return fields.toString();
    }
}
