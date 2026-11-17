package dev.oreslang.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** A scalar-only immutable nominal guest enum message with source-unit identity. */
public final class NominalMessageValue {
    private static final int MAX_FIELDS = 128;
    private final String sourceUnitId;
    private final String qualifiedEnumName;
    private final String enumName;
    private final String variantName;
    private final long tag;
    private final Map<String, Object> payload;

    public NominalMessageValue(String sourceUnitId, String qualifiedEnumName,
                               String enumName, String variantName, long tag,
                               Map<String, ?> payload) {
        this.sourceUnitId = nonblank(sourceUnitId, "source unit");
        this.qualifiedEnumName = nonblank(qualifiedEnumName, "qualified enum");
        this.enumName = nonblank(enumName, "enum");
        this.variantName = nonblank(variantName, "variant");
        if (!(qualifiedEnumName.equals(enumName) ||
                qualifiedEnumName.endsWith("." + enumName))) {
            throw new IllegalArgumentException("enum identity and short name disagree");
        }
        if (tag <= 0) throw new IllegalArgumentException("enum tag must be positive");
        this.tag = tag;
        if (payload == null) {
            this.payload = null;
        } else {
            if (payload.size() > MAX_FIELDS) throw new IllegalArgumentException(
                    "nominal enum payload exceeds field limit");
            Map<String, Object> checked = new LinkedHashMap<>();
            for (Map.Entry<String, ?> field : payload.entrySet()) {
                String key = nonblank(field.getKey(), "enum payload field");
                Object value = Objects.requireNonNull(field.getValue(),
                        "enum payload scalar cannot be null");
                if (!(value instanceof String || value instanceof Boolean ||
                        value instanceof Byte || value instanceof Short ||
                        value instanceof Integer || value instanceof Long ||
                        value instanceof Float || value instanceof Double)) {
                    throw new IllegalArgumentException(
                            "enum payload fields must be immutable primitive scalars");
                }
                checked.put(key, value);
            }
            this.payload = Collections.unmodifiableMap(checked);
        }
    }

    private static String nonblank(String s, String label) {
        if (s == null || s.isBlank()) throw new IllegalArgumentException(
                label + " must not be empty");
        return s;
    }

    public String sourceUnitId() { return sourceUnitId; }
    public String qualifiedEnumName() { return qualifiedEnumName; }
    public String enumName() { return enumName; }
    public String variantName() { return variantName; }
    public long tag() { return tag; }
    /** Null means payloadless, otherwise a private immutable scalar snapshot. */
    public Map<String, Object> payload() { return payload; }

    public NominalMessageValue isolatedCopy() {
        return new NominalMessageValue(sourceUnitId, qualifiedEnumName, enumName,
                variantName, tag, payload);
    }

    @Override public boolean equals(Object other) {
        return other instanceof NominalMessageValue v &&
                sourceUnitId.equals(v.sourceUnitId) &&
                qualifiedEnumName.equals(v.qualifiedEnumName) &&
                enumName.equals(v.enumName) &&
                variantName.equals(v.variantName) && tag == v.tag &&
                Objects.equals(payload, v.payload);
    }

    @Override public int hashCode() {
        return Objects.hash(sourceUnitId, qualifiedEnumName, enumName, variantName, tag, payload);
    }

    @Override public String toString() {
        return qualifiedEnumName + "." + variantName + "#" + tag +
                (payload == null ? "" : payload.toString());
    }
}
