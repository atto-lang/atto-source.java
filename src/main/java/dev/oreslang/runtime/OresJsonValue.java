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
    int DEFAULT_STRINGIFY_UTF8_BYTES = 1024 * 1024;
    int MAX_STRINGIFY_UTF8_BYTES = 16 * 1024 * 1024;


    public static OresJsonValue nullValue() {
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

    /**
     * Binary floating-point input is intentionally not admitted. Converting a
     * double through its shortest decimal spelling would manufacture a decimal
     * value that is not the exact source numeric carrier promised by alloc/json.
     * Keep the signature fail-closed for host callers as well as guest lowering.
     */
    public static OresJsonValue of(double value) {
        throw new IllegalArgumentException(
                "JSON floating input is fail-closed until an exact decimal carrier is supplied");
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
     * Encode one dynamic JSON value under the default bounded output policy.
     *
     * <p>This is a non-consuming read. Complete-root validation and recursive
     * traversal execute under the same JSON graph-stability guard so a
     * concurrent put/remove/clear cannot create a torn wire view.</p>
     */
    public static String stringify(OresJsonValue root) {
        return stringify(root, DEFAULT_STRINGIFY_UTF8_BYTES);
    }

    /**
     * Encode one dynamic JSON value with an explicit UTF-8 byte quota.
     *
     * <p>The requested quota is additionally capped by a hard runtime ceiling.
     * The quota counts encoded UTF-8 bytes, not UTF-16 code units or Java
     * {@link String#length()}.</p>
     */
    public static String stringify(OresJsonValue root, int maxUtf8Bytes) {
        OresJsonValue checked = Objects.requireNonNull(root, "JSON root");
        int quota = requireStringifyQuota(maxUtf8Bytes);
        return OresKeyedMap.withJsonGraphLock(() -> {
            validateTree(checked, null);
            JsonStringifier encoder = new JsonStringifier(quota);
            encoder.write(checked);
            return encoder.result();
        });
    }

    /**
     * Encode a live Map<String, JsonValue> directly as a JSON object without
     * consuming or re-owning the Map.
     */
    public static String stringifyObject(
            OresKeyedMap<String, OresJsonValue> fields) {
        return stringifyObject(fields, DEFAULT_STRINGIFY_UTF8_BYTES);
    }

    public static String stringifyObject(
            OresKeyedMap<String, OresJsonValue> fields,
            int maxUtf8Bytes) {
        ObjectValue root = new ObjectValue(
                Objects.requireNonNull(fields, "JSON object fields"));
        return stringify(root, maxUtf8Bytes);
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

    private static int requireStringifyQuota(int maxUtf8Bytes) {
        if (maxUtf8Bytes < 0) {
            throw new IllegalArgumentException(
                    "JSON stringify UTF-8 output quota cannot be negative");
        }
        if (maxUtf8Bytes > MAX_STRINGIFY_UTF8_BYTES) {
            throw new IllegalArgumentException(
                    "JSON stringify UTF-8 output quota exceeds hard runtime limit of "
                            + MAX_STRINGIFY_UTF8_BYTES + " bytes");
        }
        return maxUtf8Bytes;
    }

    private static final class JsonStringifier {
        private static final char[] HEX = "0123456789abcdef".toCharArray();

        private final int maxUtf8Bytes;
        private final StringBuilder output = new StringBuilder();
        private int utf8Bytes;

        private JsonStringifier(int maxUtf8Bytes) {
            this.maxUtf8Bytes = maxUtf8Bytes;
        }

        private String result() {
            return output.toString();
        }

        private void write(OresJsonValue value) {
            if (value instanceof NullValue) {
                ascii("null");
                return;
            }
            if (value instanceof BoolValue bool) {
                ascii(bool.value ? "true" : "false");
                return;
            }
            if (value instanceof NumberValue number) {
                ascii(number.value.toString());
                return;
            }
            if (value instanceof StringValue string) {
                quoted(string.value);
                return;
            }
            if (value instanceof ArrayValue array) {
                ascii("[");
                for (int index = 0; index < array.values.size(); index++) {
                    if (index > 0) ascii(",");
                    write(array.values.get(index));
                }
                ascii("]");
                return;
            }
            if (value instanceof ObjectValue object) {
                ascii("{");
                List<Map.Entry<String, OresJsonValue>> entries =
                        object.fields.entrySnapshot();
                for (int index = 0; index < entries.size(); index++) {
                    if (index > 0) ascii(",");
                    Map.Entry<String, OresJsonValue> field = entries.get(index);
                    quoted(field.getKey());
                    ascii(":");
                    write(Objects.requireNonNull(
                            field.getValue(), "JSON object field value"));
                }
                ascii("}");
                return;
            }
            throw new IllegalStateException(
                    "unknown compiler-owned JSON runtime variant");
        }

        private void quoted(String value) {
            Objects.requireNonNull(value, "JSON string");
            ascii("\"");
            for (int index = 0; index < value.length();) {
                char current = value.charAt(index);
                switch (current) {
                    case '\"' -> {
                        ascii("\\\"");
                        index++;
                    }
                    case '\\' -> {
                        ascii("\\\\");
                        index++;
                    }
                    case '\b' -> {
                        ascii("\\b");
                        index++;
                    }
                    case '\f' -> {
                        ascii("\\f");
                        index++;
                    }
                    case '\n' -> {
                        ascii("\\n");
                        index++;
                    }
                    case '\r' -> {
                        ascii("\\r");
                        index++;
                    }
                    case '\t' -> {
                        ascii("\\t");
                        index++;
                    }
                    default -> {
                        if (current <= 0x1f) {
                            ascii("\\u00");
                            asciiChar(HEX[(current >>> 4) & 0x0f]);
                            asciiChar(HEX[current & 0x0f]);
                            index++;
                            continue;
                        }
                        if (Character.isHighSurrogate(current)) {
                            if (index + 1 >= value.length()
                                    || !Character.isLowSurrogate(
                                            value.charAt(index + 1))) {
                                throw new IllegalArgumentException(
                                        "JSON string contains an unpaired high surrogate");
                            }
                            int codePoint = Character.toCodePoint(
                                    current, value.charAt(index + 1));
                            unicodeScalar(codePoint);
                            index += 2;
                            continue;
                        }
                        if (Character.isLowSurrogate(current)) {
                            throw new IllegalArgumentException(
                                    "JSON string contains an unpaired low surrogate");
                        }
                        unicodeScalar(current);
                        index++;
                    }
                }
            }
            ascii("\"");
        }

        private void ascii(String value) {
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current > 0x7f) {
                    throw new IllegalStateException(
                            "internal JSON encoder expected ASCII token");
                }
                asciiChar(current);
            }
        }

        private void asciiChar(char value) {
            reserve(1);
            output.append(value);
        }

        private void unicodeScalar(int codePoint) {
            int bytes;
            if (codePoint <= 0x7f) bytes = 1;
            else if (codePoint <= 0x7ff) bytes = 2;
            else if (codePoint <= 0xffff) bytes = 3;
            else bytes = 4;
            reserve(bytes);
            output.appendCodePoint(codePoint);
        }

        private void reserve(int bytes) {
            if ((long) utf8Bytes + bytes > maxUtf8Bytes) {
                throw new IllegalArgumentException(
                        "JSON stringify exceeds UTF-8 output quota of "
                                + maxUtf8Bytes + " bytes");
            }
            utf8Bytes += bytes;
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
