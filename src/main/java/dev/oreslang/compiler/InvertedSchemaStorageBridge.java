package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.InvertedColumnStore;
import java.util.List;
import java.util.Objects;

/**
 * Checked host reference bridge from the admitted inverted-struct AST into
 * the two-i64 physical SoA primitive. The compiler must eventually lower
 * verified guest MIR into this ABI; creating this reference bridge does NOT
 * authorize source execution, actor sharing, native AOT, or field loans
 * across suspension. TypeChecker must still deny unsupported guest programs.
 *
 * A bridge owns its store. It never publishes the underlying mutable store
 * because that would bypass the AST's immutable-column admission contract.
 */
public final class InvertedSchemaStorageBridge implements AutoCloseable {
    private final String schemaName;
    private final List<String> fields;
    private final boolean[] mutable;
    private final InvertedColumnStore store;
    private final int rowLimit;

    public InvertedSchemaStorageBridge(Ast.InvertedStructDecl schema) {
        this(schema, Integer.MAX_VALUE);
    }

    /** Restricted compiler-owned bounded owner; no implicit growth past the limit. */
    public InvertedSchemaStorageBridge(Ast.InvertedStructDecl schema, int rowLimit) {
        Objects.requireNonNull(schema, "nominal inverted schema");
        if (rowLimit < 0 || rowLimit > 4096) {
            if (rowLimit != Integer.MAX_VALUE) {
                throw new IllegalArgumentException("DOP v1 row capacity outside bound");
            }
        }
        this.rowLimit = rowLimit;
        if (schema.columns().size() != 2) {
            throw new IllegalArgumentException("DOP v1 requires exactly two int columns");
        }
        schemaName = schema.name();
        fields = List.copyOf(schema.columns().stream()
                .map(Ast.InvertedColumnDecl::name).toList());
        if (fields.get(0).equals(fields.get(1))) {
            throw new IllegalArgumentException("duplicate inverted column name");
        }
        mutable = new boolean[schema.columns().size()];
        for (int i = 0; i < schema.columns().size(); i++) {
            Ast.InvertedColumnDecl column = schema.columns().get(i);
            if (!"int".equals(column.type().name())
                    || !column.type().arguments().isEmpty()
                    || column.type().inferArguments()) {
                throw new IllegalArgumentException("DOP v1 supports two scalar int columns only");
            }
            mutable[i] = column.mutable();
        }
        // This restricted v1 compiler-contract slice defines Atto 'int'
        // as i64 for the native inverted pair (not a general primitive ABI).
        store = new InvertedColumnStore(
                InvertedColumnStore.Kind.I64, InvertedColumnStore.Kind.I64);
        if (rowLimit != Integer.MAX_VALUE) {
            store.reserve(rowLimit);
        }
    }

    public String schemaName() { return schemaName; }
    public List<String> fields() { return fields; }
    public int size() { return store.size(); }

    private int fieldIndex(String field) {
        Objects.requireNonNull(field, "field");
        int index = fields.indexOf(field);
        if (index < 0) throw new IllegalArgumentException("unknown inverted field: " + field);
        return index;
    }

    public boolean isMutable(String field) {
        return mutable[fieldIndex(field)];
    }

    public synchronized InvertedColumnStore.Handle push(long first, long second) {
        // The bounds decision and allocation must share the bridge monitor.
        // The underlying store's lock cannot make two separate calls atomic.
        if (store.size() >= rowLimit) {
            throw new IllegalStateException("DOP v1 inverted row capacity exceeded");
        }
        return store.push(first, second);
    }

    public InvertedColumnStore.Handle handleAt(int physicalRow) {
        return store.handleAt(physicalRow);
    }

    public long get(InvertedColumnStore.Handle handle, String field) {
        return (Long) store.get(handle, fieldIndex(field));
    }

    public void write(InvertedColumnStore.Handle handle, String field, long value) {
        int index = fieldIndex(field);
        if (!mutable[index]) {
            throw new IllegalStateException("immutable inverted field cannot be written: " + field);
        }
        store.set(handle, index, value);
    }

    public InvertedColumnStore.ColumnLoan borrowRead(String field) {
        return store.borrowRead(fieldIndex(field));
    }

    public InvertedColumnStore.ColumnLoan borrowWrite(String field) {
        int index = fieldIndex(field);
        if (!mutable[index]) {
            throw new IllegalStateException(
                    "immutable inverted field cannot be borrowed mutably: " + field);
        }
        return store.borrowWrite(index);
    }

    public void eraseSwap(InvertedColumnStore.Handle handle) {
        store.eraseSwap(handle);
    }

    @Override
    public void close() {
        store.close();
    }
}
