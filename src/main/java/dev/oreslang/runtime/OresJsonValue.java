package dev.oreslang.runtime;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compiler-owned runtime carrier for the closed JSON value tree.
 *
 * <p>This is deliberately not an arbitrary host object wrapper. Every variant
 * is one of the six JSON data domains, numbers remain exact until an explicit
 * target conversion, arrays snapshot their elements, and objects use the
 * canonical {@link OresKeyedMap} with String keys and the JSON value domain.
 *
 * <p>Mutable JSON objects can be nested, so Map insertion performs a graph
 * admission check under one JSON-only global admission lock. The lock exists
 * solely to prevent two concurrently mutated JSON Maps from racing each other
 * into a cycle after both validators observed an acyclic pre-state.
 */
public sealed interface OresJsonValue
        permits OresJsonValue.NullValue,
                OresJsonValue.BoolValue,
                OresJsonValue.NumberValue,
                OresJsonValue.StringValue,
                OresJsonValue.ArrayValue,
                OresJsonValue.ObjectValue {

    int MAX_GRAPH_DEPTH = 256;
    int MAX_GRAPH_NODES = 65_536;
    int MAX_ARRAY_ITEMS = 65_536;
    int MAX_NUMBER_PRECISION = 4_096;
    int MAX_ABS_NUMBER_SCALE = 4_096;


    static OresJsonValue nullValue() {
        return NullValue.INSTANCE;
    }

    public static OresJsonValue of(boolean value) {
        return value ? BoolValue.TRUE : BoolValue.FALSE;
    }

    public static OresJsonValue of(String value) {
        return new StringValue(value);
    }

    public static OresJsonValue of(long value) {
        return new NumberValue(BigDecimal.valueOf(value));
    }

    public static OresJsonValue of(BigInteger value) {
        return new NumberValue(new BigDecimal(
                Objects.requireNonNull(value, "JSON integer")));
    }

    public static OresJsonValue of(BigDecimal value) {
        return new NumberValue(value);
    }

    public static OresJsonValue of(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    "JSON numbers cannot be NaN or infinite");
        }
        return new NumberValue(BigDecimal.valueOf(value));
    }

    public static OresJsonValue array(Iterable<? extends OresJsonValue> source) {
        Objects.requireNonNull(source, "JSON array source");
        ArrayList<OresJsonValue> values = new ArrayList<>();
        for (OresJsonValue value : source) {
            if (values.size() >= MAX_ARRAY_ITEMS) {
                throw new IllegalArgumentException(
                        "JSON array exceeds runtime item limit");
            }
            values.add(Objects.requireNonNull(value, "JSON array element"));
        }
        ArrayValue result = new ArrayValue(List.copyOf(values));
        validateCompleteTree(result);
        return result;
    }

    /**
     * Create an empty mutable dynamic JSON object using the canonical typed Map
     * runtime. This is the backing storage selected for source
     * Map<String, JsonValue>.
     */
    public static OresKeyedMap<String, OresJsonValue> objectMap() {
        return OresKeyedMap.stringKeys(OresKeyedMap.ValueDomain.JSON);
    }

    public static OresJsonValue object(OresKeyedMap<String, OresJsonValue> fields) {
        ObjectValue result = new ObjectValue(fields);
        validateCompleteTree(result);
        return result;
    }

    /**
     * Revalidate a complete JSON value at an externalization boundary.
     *
     * <p>Cycle creation is rejected on every JSON Map mutation. Depth and node
     * budgets, however, are properties of a chosen root: a retained alias to a
     * nested mutable object can later make an older root deeper or larger
     * without mutating that root Map directly. Encoders, transport/freeze
     * boundaries, and other recursive consumers must therefore call this
     * method immediately before traversing the value.</p>
     */
    static void validateCompleteTree(OresJsonValue root) {
        OresJsonValue checked = Objects.requireNonNull(root, "JSON root");
        OresKeyedMap.withJsonGraphLock(() -> {
            validateTree(checked, null);
            return null;
        });
    }

    /**
     * The Map runtime calls this while holding the JSON graph-admission lock
     * and before publishing a write.
     */
    static void validateForMapInsertion(
            OresKeyedMap<?, ?> destination,
            OresJsonValue value) {
        validateTree(
                Objects.requireNonNull(value, "JSON value"),
                Objects.requireNonNull(destination, "destination JSON Map"));
    }


    private static void validateTree(
            OresJsonValue root,
            OresKeyedMap<?, ?> forbiddenDestination) {
        ValidationState state = new ValidationState(forbiddenDestination);
        state.visit(root, 0);
    }

    public static final class NullValue implements OresJsonValue {
        private static final NullValue INSTANCE = new NullValue();
        private NullValue() { }

        @Override
        public String toString() {
            return "JsonNull";
        }
    }

    public static final class BoolValue implements OresJsonValue {
        private static final BoolValue TRUE = new BoolValue(true);
        private static final BoolValue FALSE = new BoolValue(false);

        private final boolean value;

        private BoolValue(boolean value) {
            this.value = value;
        }

        public boolean value() {
            return value;
        }

        @Override
        public String toString() {
            return value ? "JsonBool(true)" : "JsonBool(false)";
        }
    }

    public static final class NumberValue implements OresJsonValue {
        private final BigDecimal value;

        private NumberValue(BigDecimal value) {
            value = Objects.requireNonNull(value, "JSON number");
            if (value.precision() > MAX_NUMBER_PRECISION
                    || Math.abs((long) value.scale()) > MAX_ABS_NUMBER_SCALE) {
                throw new IllegalArgumentException(
                        "JSON number exceeds runtime precision/scale limits");
            }
            this.value = value;
        }

        public BigDecimal value() {
            return value;
        }

        @Override
        public String toString() {
            return "JsonNumber<exact>";
        }
    }

    public static final class StringValue implements OresJsonValue {
        private final String value;

        private StringValue(String value) {
            this.value = requireUnicodeScalarString(
                    Objects.requireNonNull(value, "JSON string"));
        }

        public String value() {
            return value;
        }

        @Override
        public String toString() {
            return "JsonString<opaque>";
        }
    }

    public static final class ArrayValue implements OresJsonValue {
        private final List<OresJsonValue> values;

        private ArrayValue(List<OresJsonValue> values) {
            this.values = values;
        }

        public List<OresJsonValue> values() {
            return values;
        }

        @Override
        public String toString() {
            return "JsonArray[size=" + values.size() + "]";
        }
    }

    public static final class ObjectValue implements OresJsonValue {
        private final OresKeyedMap<String, OresJsonValue> fields;

        private ObjectValue(OresKeyedMap<String, OresJsonValue> fields) {
            fields = Objects.requireNonNull(fields, "JSON object fields");
            if (fields.keyDomain() != OresKeyedMap.KeyDomain.STRING
                    || fields.valueDomain() != OresKeyedMap.ValueDomain.JSON) {
                throw new IllegalArgumentException(
                        "JSON object requires Map<String, JsonValue> storage");
            }
            this.fields = fields;
        }

        public OresKeyedMap<String, OresJsonValue> fields() {
            return fields;
        }

        @Override
        public String toString() {
            return "JsonObject<opaque>";
        }
    }

    private static final class ValidationState {
        private final OresKeyedMap<?, ?> forbiddenDestination;
        private final IdentityHashMap<Object, Boolean> active =
                new IdentityHashMap<>();
        private int nodes;

        private ValidationState(OresKeyedMap<?, ?> forbiddenDestination) {
            this.forbiddenDestination = forbiddenDestination;
        }

        private void visit(OresJsonValue value, int depth) {
            Objects.requireNonNull(value, "JSON tree value");
            if (depth > MAX_GRAPH_DEPTH) {
                throw new IllegalArgumentException(
                        "JSON graph exceeds maximum nesting depth");
            }
            if (++nodes > MAX_GRAPH_NODES) {
                throw new IllegalArgumentException(
                        "JSON graph exceeds validation work limit");
            }

            if (value instanceof ArrayValue array) {
                enter(array);
                try {
                    for (OresJsonValue element : array.values) {
                        visit(element, depth + 1);
                    }
                } finally {
                    active.remove(array);
                }
                return;
            }

            if (value instanceof ObjectValue object) {
                OresKeyedMap<String, OresJsonValue> fields = object.fields;
                if (fields == forbiddenDestination) {
                    throw new IllegalArgumentException(
                            "JSON Map insertion would create a cyclic object graph");
                }
                enter(fields);
                try {
                    List<Map.Entry<String, OresJsonValue>> snapshot =
                            fields.entrySnapshot();
                    for (Map.Entry<String, OresJsonValue> field : snapshot) {
                        // Key validity/type and key quotas are owned by the
                        // String-keyed OresKeyedMap domain. Value traversal is
                        // compiler-owned and never dispatches guest callbacks.
                        visit(
                                Objects.requireNonNull(
                                        field.getValue(),
                                        "JSON object field value"),
                                depth + 1);
                    }
                } finally {
                    active.remove(fields);
                }
            }
        }

        private void enter(Object container) {
            if (active.put(container, Boolean.TRUE) != null) {
                throw new IllegalArgumentException(
                        "JSON values cannot contain cyclic object graphs");
            }
        }
    }

    private static String requireUnicodeScalarString(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                            "JSON string contains an unpaired high surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(
                        "JSON string contains an unpaired low surrogate");
            }
        }
        return value;
    }
}
