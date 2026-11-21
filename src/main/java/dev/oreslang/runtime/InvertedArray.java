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
 * copies primitive values or opaque fixed-width records into physical
 * columns. Only directly declared fields are separated into SoA columns;
 * internal record members remain AoS within each column element. No persistent
 * row object is allocated on insertion.
 *
 * The class name and field descriptor are runtime checked. The source compiler
 * must still enforce escape, actor-domain and mutable-borrow rules before the
 * guest can access this API.
 */
public final class InvertedArray implements AutoCloseable {
    /** Immutable, ordered, validated physical schema. */
    public static final class Schema {
        public record Field(String name, InvertedColumnStore.Kind kind,
                            InvertedRecordLayout recordLayout) {
            public Field(String name, InvertedColumnStore.Kind kind) {
                this(name, kind, null);
            }
            public Field {
                Objects.requireNonNull(name, "field name");
                Objects.requireNonNull(kind, "field kind");
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("invalid inverted field name");
                }
                if ((kind == InvertedColumnStore.Kind.RECORD) != (recordLayout != null)) {
                    throw new IllegalArgumentException("record column requires an explicit fixed layout");
                }
            }
        }
        private final String className;
        private final List<Field> fields;
        private final Map<String, Integer> indices;
        private final boolean requiresMethodPlan;

        private Schema(String className, List<Field> fields, boolean requiresMethodPlan) {
            this.className = Objects.requireNonNull(className, "class name");
            this.requiresMethodPlan = requiresMethodPlan;
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
            return fromClass(klass, Map.of());
        }

        /**
         * Resolve only explicitly supplied, direct structural type aliases.
         * Never recursively interpret nested type aliases or flatten members.
         */
        public static Schema fromClass(Ast.ClassDecl klass, Map<String, Ast.TypeRef> aliases) {
            Objects.requireNonNull(klass, "inverted class");
            Objects.requireNonNull(aliases, "structural type aliases");
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
                        || field.initializer() != null
                        || !field.annotations().isEmpty()
                        || field.bindingKind() == Ast.BindingKind.CONST) {
                    throw new IllegalArgumentException(
                            "inverted field '" + field.name()
                                    + "' requires a supported fixed-width type with no initializer/annotation");
                }
                Ast.TypeRef type = field.type();
                if (aliases.containsKey(type.name())) {
                    if (!type.arguments().isEmpty() || type.inferArguments()) {
                        throw new IllegalArgumentException("generic inverted record aliases are unsupported");
                    }
                    type = Objects.requireNonNull(aliases.get(type.name()), "record alias type");
                    if (!type.isRecordType()) {
                        throw new IllegalArgumentException("only direct fixed-layout record aliases are supported");
                    }
                }
                if (type.isClosedAggregateType()) {
                    ordered.add(new Field(field.name(), InvertedColumnStore.Kind.RECORD,
                            InvertedRecordLayout.fromType(type)));
                } else {
                    InvertedColumnStore.Kind kind = InvertedRecordLayout.scalarKind(type);
                    if (kind == null) {
                        throw new IllegalArgumentException(
                                "unsupported inverted field type '" + type.name()
                                        + "'; no implicit AoS fallback or recursive SoA lowering");
                    }
                    ordered.add(new Field(field.name(), kind));
                }
            }
            return new Schema(klass.name(), ordered, !klass.methods().isEmpty());
        }

        public String className() { return className; }
        public List<Field> fields() { return fields; }
        public int fieldCount() { return fields.size(); }

        /**
         * Canonical immutable physical descriptor captured by compiler
         * admission. Field names are identifiers, so the separators below are
         * unambiguous. Record members are already canonicalized by name and
         * carry their physical offset and scalar kind.
         */
        public List<String> physicalFingerprint() {
            ArrayList<String> fingerprint = new ArrayList<>(fields.size());
            for (Field field : fields) {
                StringBuilder item = new StringBuilder()
                        .append(field.name()).append(':').append(field.kind());
                if (field.recordLayout() != null) {
                    item.append(':').append(field.recordLayout().bytes());
                    for (InvertedRecordLayout.Member member
                            : field.recordLayout().members()) {
                        item.append(':')
                                .append(member.name()).append('@')
                                .append(member.offset()).append('=')
                                .append(member.kind());
                    }
                }
                fingerprint.add(item.toString());
            }
            return List.copyOf(fingerprint);
        }

        /**
         * Runtime nominal checks must bind the complete physical descriptor,
         * not only the class name. This protects embedding/host boundaries
         * from substituting a same-name InvertedArray with a different column
         * kind/order or fixed-record ABI.
         */
        public void requireMatches(Ast.ClassDecl klass) {
            Schema expected = fromClass(klass);
            if (!className.equals(expected.className)
                    || fields.size() != expected.fields.size()) {
                throw new IllegalStateException(
                        "inverted runtime schema does not match nominal class");
            }
            for (int i = 0; i < fields.size(); i++) {
                Field actualField = fields.get(i);
                Field expectedField = expected.fields.get(i);
                if (!actualField.name().equals(expectedField.name())
                        || actualField.kind() != expectedField.kind()
                        || !sameRecordLayout(
                                actualField.recordLayout(),
                                expectedField.recordLayout())) {
                    throw new IllegalStateException(
                            "inverted runtime schema mismatch at column " + i);
                }
            }
        }

        private static boolean sameRecordLayout(
                InvertedRecordLayout left,
                InvertedRecordLayout right) {
            if (left == right) return true;
            if (left == null || right == null || left.bytes() != right.bytes()) {
                return false;
            }
            return left.members().equals(right.members());
        }
        public int index(String name) {
            Integer index = indices.get(Objects.requireNonNull(name, "field name"));
            if (index == null) {
                throw new IllegalArgumentException(
                        "unknown inverted field '" + className + "." + name + "'");
            }
            return index;
        }
        private InvertedColumnStore.ColumnSpec[] columnSpecs() {
            InvertedColumnStore.ColumnSpec[] specs =
                    new InvertedColumnStore.ColumnSpec[fields.size()];
            for (int i = 0; i < specs.length; i++) {
                Field field = fields.get(i);
                specs[i] = field.kind() == InvertedColumnStore.Kind.RECORD
                        ? InvertedColumnStore.ColumnSpec.record(field.recordLayout().bytes())
                        : InvertedColumnStore.ColumnSpec.scalar(field.kind());
            }
            return specs;
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
        if (schema.requiresMethodPlan && methods.isEmpty()) {
            throw new IllegalArgumentException(
                    "inverted class methods require verified row-method lowering; no silent omission");
        }
        this.methods = Map.copyOf(methods);
        store = new InvertedColumnStore(schema.columnSpecs());
        try {
            store.reserve(initialCapacity);
        } catch (RuntimeException | Error failure) {
            store.close();
            throw failure;
        }
    }

    public static InvertedArray fromClass(Ast.ClassDecl klass, int capacity) {
        return fromClass(klass, capacity, Map.of());
    }

    public static InvertedArray fromClass(
            Ast.ClassDecl klass, int capacity, Map<String, Ast.TypeRef> aliases) {
        Schema schema = Schema.fromClass(klass, aliases);
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
            values[i] = encoded(i, fields.get(name));
        }
        return values;
    }

    /** Stores one value in each top-level field column; nested fields remain AoS. */
    public InvertedColumnStore.Handle push(Map<String, ?> fields) {
        return store.push(checkedValues(fields));
    }

    /** Typed column-value push, checked against the nominal schema. */
    public InvertedColumnStore.Handle pushValues(Object... orderedValues) {
        if (orderedValues == null || orderedValues.length != schema.fieldCount()) {
            throw new IllegalArgumentException("row width must equal inverted field count");
        }
        Object[] encoded = new Object[orderedValues.length];
        for (int i = 0; i < encoded.length; i++) encoded[i] = encoded(i, orderedValues[i]);
        return store.push(encoded);
    }

    private Object encoded(int column, Object value) {
        InvertedRecordLayout record = schema.fields().get(column).recordLayout();
        return record == null ? value : record.encode(value);
    }

    private Object decoded(int column, Object value) {
        InvertedRecordLayout record = schema.fields().get(column).recordLayout();
        return record == null ? value : record.decode((byte[]) value);
    }

    public void eraseSwap(InvertedColumnStore.Handle handle) {
        store.eraseSwap(handle);
    }

    public Object read(InvertedColumnStore.Handle handle, String field) {
        int column = schema.index(field);
        return decoded(column, store.get(handle, column));
    }

    public void write(InvertedColumnStore.Handle handle, String field, Object value) {
        int column = schema.index(field);
        store.set(handle, column, encoded(column, value));
    }

    /** Immutable detached snapshot of scalars and record elements. */
    public Map<String, Object> snapshot(InvertedColumnStore.Handle handle) {
        Object[] values = store.snapshot(handle);
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            result.put(schema.fields().get(i).name(), decoded(i, values[i]));
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
     * Runtime target for a compiler-admitted bracket projection.
     */
    public RowView index(int rowIndex) {
        return new RowView(store.handleAt(rowIndex));
    }

    /**
     * Generated code must range-check before narrowing a guest integral index.
     */
    public RowView index(long rowIndex) {
        final int narrowed;
        try {
            narrowed = Math.toIntExact(rowIndex);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "inverted row index outside supported signed 32-bit range: " + rowIndex,
                    overflow);
        }
        return index(narrowed);
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
            return decoded(column, store.get(handle, column));
        }
        public void set(InvertedColumnStore.Handle handle, Object value) {
            store.set(handle, column, encoded(column, value));
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
        public Object readAt(int physicalRow) {
            return decoded(field, underlying.get(physicalRow));
        }
        public void writeAt(int physicalRow, Object value) {
            if (!write) throw new IllegalStateException("read-only column loan");
            underlying.set(physicalRow, encoded(field, value));
        }
        @Override public void close() { underlying.close(); }
    }

    @Override public void close() { store.close(); }
}
