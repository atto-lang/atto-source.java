package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Typed, allocation-backed column owner for one nominal inverted class.
 *
 * This is a host reference/target for future Oreslang lowering, not an
 * interpreter-visible builtin. There is NO implicit AoS fallback. Each push
 * copies fixed-width scalar values into existing physical columns; no object
 * is allocated for the row on insertion.
 *
 * The class name and field descriptor are runtime checked. The source compiler
 * must still enforce escape, actor-domain and mutable-borrow rules before the
 * guest can access this API.
 */
public final class InvertedArray implements AutoCloseable {
    /** Immutable, ordered, validated physical schema. */
    public static final class Schema {
        public record Field(String name, InvertedColumnStore.Kind kind) {
            public Field {
                Objects.requireNonNull(name, "field name");
                Objects.requireNonNull(kind, "field kind");
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("invalid inverted field name");
                }
            }
        }
        private final String className;
        private final List<Field> fields;
        private final Map<String, Integer> indices;

        private Schema(String className, List<Field> fields) {
            this.className = Objects.requireNonNull(className, "class name");
            if (fields.isEmpty()) throw new IllegalArgumentException("inverted class requires fields");
            LinkedHashMap<String, Integer> mapped = new LinkedHashMap<>();
            for (int i = 0; i < fields.size(); i++) {
                Field field = fields.get(i);
                if (mapped.putIfAbsent(field.name(), i) != null) {
                    throw new IllegalArgumentException("duplicate inverted field '" + field.name() + "'");
                }
            }
            this.fields = List.copyOf(fields);
            indices = Map.copyOf(mapped);
        }

        public static Schema fromClass(Ast.ClassDecl klass) {
            Objects.requireNonNull(klass, "inverted class");
            if (!klass.inverted() || klass.actorKind() != Ast.ActorKind.NONE
                    || klass.isAbstract() || !klass.parents().isEmpty()
                    || !klass.interfaces().isEmpty() || klass.constructor() != null
                    || !klass.genericParameters().isEmpty()) {
                throw new IllegalArgumentException(
                        "only non-generic, flat, non-actor inverted classes are supported");
            }
            List<Field> ordered = new ArrayList<>();
            for (Ast.FieldDecl field : klass.fields()) {
                if (field.type() == null || field.type().inferArguments()
                        || !field.type().arguments().isEmpty()
                        || field.initializer() != null
                        || !field.annotations().isEmpty()
                        || field.bindingKind() == Ast.BindingKind.CONST) {
                    throw new IllegalArgumentException(
                            "inverted field '" + field.name()
                                    + "' requires a plain fixed-width scalar with no initializer/annotation");
                }
                // No unverified aliases or pointer-containing user records.
                InvertedColumnStore.Kind kind = switch (field.type().name()) {
                    case "i32" -> InvertedColumnStore.Kind.I32;
                    case "i64" -> InvertedColumnStore.Kind.I64;
                    case "f32" -> InvertedColumnStore.Kind.F32;
                    case "f64" -> InvertedColumnStore.Kind.F64;
                    case "bool" -> InvertedColumnStore.Kind.BOOL;
                    default -> throw new IllegalArgumentException(
                            "unsupported inverted scalar type '" + field.type().name()
                                    + "'; use i32/i64/f32/f64/bool until target ABI mapping is proved");
                };
                ordered.add(new Field(field.name(), kind));
            }
            return new Schema(klass.name(), ordered);
        }

        public String className() { return className; }
        public List<Field> fields() { return fields; }
        public int fieldCount() { return fields.size(); }
        public int index(String name) {
            Integer index = indices.get(Objects.requireNonNull(name, "field name"));
            if (index == null) {
                throw new IllegalArgumentException(
                        "unknown inverted field '" + className + "." + name + "'");
            }
            return index;
        }
        private InvertedColumnStore.Kind[] columnKinds() {
            InvertedColumnStore.Kind[] kinds = new InvertedColumnStore.Kind[fields.size()];
            for (int i = 0; i < kinds.length; i++) kinds[i] = fields.get(i).kind();
            return kinds;
        }
    }

    private final Schema schema;
    private final InvertedColumnStore store;
    private final Map<String, InvertedRowMethod> methods;

    public InvertedArray(Schema schema, int initialCapacity) {
        this(schema, initialCapacity, Map.of());
    }

    private InvertedArray(Schema schema, int initialCapacity, Map<String, InvertedRowMethod> methods) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.methods = Map.copyOf(methods);
        store = new InvertedColumnStore(schema.columnKinds());
        try {
            store.reserve(initialCapacity);
        } catch (RuntimeException | Error failure) {
            store.close();
            throw failure;
        }
    }

    public static InvertedArray fromClass(Ast.ClassDecl klass, int capacity) {
        Schema schema = Schema.fromClass(klass);
        LinkedHashMap<String, InvertedRowMethod> prepared = new LinkedHashMap<>();
        for (Ast.MethodDecl method : klass.methods()) {
            InvertedRowMethod plan = InvertedRowMethod.compile(method, schema);
            if (prepared.putIfAbsent(plan.selector(), plan) != null) {
                throw new IllegalArgumentException("duplicate inverted method selector " + plan.selector());
            }
        }
        return new InvertedArray(schema, capacity, prepared);
    }

    /**
     * Execute only a statically admitted scalar method plan. Caller-supplied
     * arguments are evaluated exactly once before the atomic row update.
     */
    public void invoke(InvertedColumnStore.Handle handle, String method, Object... args) {
        Objects.requireNonNull(method, "method");
        int arity = args == null ? -1 : args.length;
        InvertedRowMethod plan = methods.get(method + "/" + arity);
        if (plan == null) {
            throw new IllegalArgumentException("unknown or unsupported inverted method '" + method + "'");
        }
        plan.invoke(store, handle, args);
    }

    public Schema schema() { return schema; }
    public int size() { return store.size(); }
    public int capacity() { return store.capacity(); }
    public void reserve(int capacity) { store.reserve(capacity); }

    private Object[] checkedValues(Map<String, ?> fields) {
        Objects.requireNonNull(fields, "row values");
        if (fields.size() != schema.fieldCount()) {
            throw new IllegalArgumentException("inverted row must contain exactly "
                    + schema.fieldCount() + " declared fields");
        }
        Object[] values = new Object[schema.fieldCount()];
        for (int i = 0; i < values.length; i++) {
            String name = schema.fields().get(i).name();
            if (!fields.containsKey(name)) {
                throw new IllegalArgumentException("missing inverted field '" + name + "'");
            }
            values[i] = fields.get(name);
        }
        return values;
    }

    /** Stores a row in existing scalar columns; no persistent row object is created. */
    public InvertedColumnStore.Handle push(Map<String, ?> fields) {
        return store.push(checkedValues(fields));
    }

    /** Typed column-value push, checked against the nominal schema. */
    public InvertedColumnStore.Handle pushValues(Object... orderedValues) {
        return store.push(orderedValues);
    }

    public void eraseSwap(InvertedColumnStore.Handle handle) {
        store.eraseSwap(handle);
    }

    public Object read(InvertedColumnStore.Handle handle, String field) {
        return store.get(handle, schema.index(field));
    }

    public void write(InvertedColumnStore.Handle handle, String field, Object value) {
        store.set(handle, schema.index(field), value);
    }

    /** Immutable snapshot: copies scalars, not mutable column buffers. */
    public Map<String, Object> snapshot(InvertedColumnStore.Handle handle) {
        Object[] values = store.snapshot(handle);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            result.put(schema.fields().get(i).name(), values[i]);
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    public void updateRow(InvertedColumnStore.Handle handle, Map<String, ?> fields) {
        store.updateRow(handle, checkedValues(fields));
    }

    /**
     * A transient row projection, NOT a heap-allocated row on push. Its handle
     * is revalidated on each operation, including after swap-delete/close.
     * The future compiler must forbid storing/transporting unsafe views.
     */
    public RowView row(InvertedColumnStore.Handle handle) {
        // Reject foreign, stale or deleted handles before allocating a view.
        store.snapshot(handle);
        return new RowView(handle);
    }

    /**
     * Compile-time name resolution target. A guest field site will bind one
     * accessor once and use its fixed column index thereafter; every access
     * still validates the opaque row handle and active borrow permissions.
     */
    public ColumnAccess bindField(String field) {
        return new ColumnAccess(schema.index(field));
    }

    public final class ColumnAccess {
        private final int column;
        private ColumnAccess(int column) { this.column = column; }
        public String name() { return schema.fields().get(column).name(); }
        public InvertedColumnStore.Kind kind() { return schema.fields().get(column).kind(); }
        public Object get(InvertedColumnStore.Handle handle) {
            return store.get(handle, column);
        }
        public void set(InvertedColumnStore.Handle handle, Object value) {
            store.set(handle, column, value);
        }
    }

    public final class RowView {
        private final InvertedColumnStore.Handle handle;
        private RowView(InvertedColumnStore.Handle handle) { this.handle = handle; }
        public Object get(String field) { return read(handle, field); }
        public void set(String field, Object value) { write(handle, field, value); }
        public Map<String, Object> snapshot() { return InvertedArray.this.snapshot(handle); }
        public void invoke(String method, Object... args) {
            InvertedArray.this.invoke(handle, method, args);
        }
        public InvertedColumnStore.Handle handle() { return handle; }
    }

    /** Capability-bound column loans, no raw backing array escapes. */
    public FieldLoan borrowRead(String field) {
        int index = schema.index(field);
        return new FieldLoan(store.borrowRead(index), index, false);
    }

    public FieldLoan borrowWrite(String field) {
        int index = schema.index(field);
        return new FieldLoan(store.borrowWrite(index), index, true);
    }

    public final class FieldLoan implements AutoCloseable {
        private final InvertedColumnStore.ColumnLoan underlying;
        private final int field;
        private final boolean write;
        private FieldLoan(InvertedColumnStore.ColumnLoan loan, int field, boolean write) {
            this.underlying = loan;
            this.field = field;
            this.write = write;
        }
        public String fieldName() { return schema.fields().get(field).name(); }
        public int length() { return underlying.length(); }
        public Object readAt(int physicalRow) { return underlying.get(physicalRow); }
        public void writeAt(int physicalRow, Object value) {
            if (!write) throw new IllegalStateException("read-only column loan");
            underlying.set(physicalRow, value);
        }
        @Override public void close() { underlying.close(); }
    }

    @Override public void close() { store.close(); }
}
