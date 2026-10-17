package dev.oreslang.types;

import java.util.Map;
import java.util.Objects;

import dev.oreslang.types.Types.Type;

/**
 * Conservative compile-time proof of structural collection views.
 *
 * This is NOT parser, ownership-loan, ABI, emitter, or guest support. The
 * caller must separately prove non-escape across await/actors and bind a
 * compiler-declared read-only view. No runtime inspection or casts here.
 *
 * The five backends share contracts/structural/v3/cases.json as TARGET ONLY.
 */
public final class StructuralContainerPolicy {
    private StructuralContainerPolicy() { }

    private static final int MAX_STATIC_EXTENT = 64;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_RECORD_FIELDS = 64;

    public enum Kind { ARRAY, LIST, FIXED_ARRAY, FIXED_LIST, MAP }

    /**
     * Key is nonnull only for maps. Extent is -1 for dynamic collections,
     * 0..64 for fixed arrays/lists. A readonly target has NO mutation rights.
     */
    public record Shape(Kind kind, Type key, Type value, int extent, boolean readOnly) {
        public Shape {
            Objects.requireNonNull(kind, "container kind");
            Objects.requireNonNull(value, "container value type");
            if (!closedValue(value, 0)) {
                throw new IllegalArgumentException("collection requires a closed readonly-safe value type");
            }
            if (kind == Kind.MAP) {
                if (extent != -1 || !stableKey(key)) {
                    throw new IllegalArgumentException("map requires a canonical hash/equality key");
                }
            } else if (key != null) {
                throw new IllegalArgumentException("array/list must not carry map key metadata");
            }
            if (kind == Kind.FIXED_ARRAY || kind == Kind.FIXED_LIST) {
                if (extent < 0 || extent > MAX_STATIC_EXTENT) {
                    throw new IllegalArgumentException("fixed collection extent outside 0..64");
                }
            } else if (extent != -1) {
                throw new IllegalArgumentException("dynamic collection cannot advertise fixed extent");
            }
        }
    }

    public static Shape array(Type element, boolean readOnly) {
        return new Shape(Kind.ARRAY, null, element, -1, readOnly);
    }

    public static Shape list(Type element, boolean readOnly) {
        return new Shape(Kind.LIST, null, element, -1, readOnly);
    }

    public static Shape fixedArray(Type element, int size, boolean readOnly) {
        return new Shape(Kind.FIXED_ARRAY, null, element, size, readOnly);
    }

    public static Shape fixedList(Type element, int size, boolean readOnly) {
        return new Shape(Kind.FIXED_LIST, null, element, size, readOnly);
    }

    public static Shape map(Type key, Type value, boolean readOnly) {
        return new Shape(Kind.MAP, key, value, -1, readOnly);
    }

    /** Map keys are always invariant; no numeric widening or object hash guesses. */
    private static boolean stableKey(Type type) {
        return type == Types.Primitive.STRING || type == Types.Primitive.INT
                || type == Types.Primitive.BOOL
                || type instanceof Types.StringLiteral
                || type instanceof Types.BoolLiteral;
    }

    /** No Unknown, generic holes, writable nested records, mutable borrows or containers. */
    private static boolean closedValue(Type type, int depth) {
        if (type == null || depth > MAX_DEPTH) return false;
        if (type instanceof Types.Primitive p) {
            return p != Types.Primitive.NULL && p != Types.Primitive.VOID;
        }
        if (type instanceof Types.StringLiteral || type instanceof Types.BoolLiteral) return true;
        if (type instanceof Types.Named n) {
            return n.name().equals("Option") && n.arguments().size() == 1
                    && closedValue(n.arguments().get(0), depth + 1);
        }
        if (type instanceof Types.Tuple tuple) {
            if (tuple.kind() != Types.TupleKind.TUPLE || tuple.elements().size() > MAX_STATIC_EXTENT)
                return false;
            for (Type element : tuple.elements()) {
                if (!closedValue(element, depth + 1)) return false;
            }
            return true;
        }
        if (type instanceof Types.Record record) {
            if (!record.readOnly() || record.members().size() > MAX_RECORD_FIELDS) return false;
            for (Map.Entry<String, Type> field : record.members().entrySet()) {
                if (field.getKey() == null || field.getKey().isBlank()
                        || !closedValue(field.getValue(), depth + 1)) return false;
            }
            return true;
        }
        return false;
    }

    /**
     * Readonly loans may project only representation-preserving closed types;
     * writable collections remain EXACTLY invariant, including all nested types.
     */
    public static boolean admits(Shape actual, Shape target) {
        Objects.requireNonNull(actual, "actual");
        Objects.requireNonNull(target, "target");
        if (actual.kind() != target.kind() || actual.extent() != target.extent()) return false;
        if (!Objects.equals(actual.key(), target.key())) return false;
        if (!target.readOnly() && actual.readOnly()) return false;
        if (!target.readOnly()) return actual.value().equals(target.value());
        return readProjection(actual.value(), target.value(), 0);
    }

    private static boolean readProjection(Type from, Type to, int depth) {
        if (depth > MAX_DEPTH || !closedValue(from, depth) || !closedValue(to, depth)) return false;
        if (from.equals(to)) return true;
        if (from instanceof Types.StringLiteral && to == Types.Primitive.STRING) return true;
        if (from instanceof Types.BoolLiteral && to == Types.Primitive.BOOL) return true;
        if (from instanceof Types.Named a && to instanceof Types.Named b
                && a.name().equals("Option") && b.name().equals("Option")
                && a.arguments().size() == 1 && b.arguments().size() == 1) {
            return readProjection(a.arguments().get(0), b.arguments().get(0), depth + 1);
        }
        if (from instanceof Types.Tuple a && to instanceof Types.Tuple b
                && a.kind() == Types.TupleKind.TUPLE
                && b.kind() == Types.TupleKind.TUPLE
                && a.elements().size() == b.elements().size()) {
            for (int i = 0; i < a.elements().size(); i++) {
                if (!readProjection(a.elements().get(i), b.elements().get(i), depth + 1))
                    return false;
            }
            return true;
        }
        if (from instanceof Types.Record a && to instanceof Types.Record b
                && a.readOnly() && b.readOnly()
                && a.members().size() <= MAX_RECORD_FIELDS) {
            for (Map.Entry<String, Type> field : b.members().entrySet()) {
                Type actualField = a.members().get(field.getKey());
                if (actualField == null
                        || !readProjection(actualField, field.getValue(), depth + 1))
                    return false;
            }
            // The closedValue guard certifies ALL hidden fields, not just the target prefix.
            return true;
        }
        return false;
    }
}
