package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.InvertedArray;
import dev.oreslang.runtime.InvertedColumnStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Backend-neutral lowering candidate for one direct field access rooted at an
 * inverted-array bracket expression.
 *
 * <p>This object is deliberately <em>not</em> program-level execution
 * authority: executable guest inverted classes remain fail-closed until the
 * type/ownership pipeline can publish a whole-program lifetime proof. The plan
 * is bound to the exact {@link Ast.IndexExpr} instance supplied by the compiler
 * staging API and never exposes a row handle or row view to guest code:
 * bracket selection is immediately fused with a statically resolved field
 * read/write. Rewritten or reconstructed AST nodes must be re-planned.
 *
 * <p>V1 deliberately admits only a non-suspending direct receiver name and a
 * scalar index expression composed of names/literals/unary/binary operations.
 * Calls, await/task/channel operations, member-derived receivers, and other
 * unknown shapes fail closed until ownership across suspension is proven.
 */
public final class InvertedIndexPlan {
    public enum Access { READ, WRITE }

    private record PhysicalField(
            String name,
            InvertedColumnStore.Kind kind,
            int recordBytes) { }

    private final Ast.IndexExpr site;
    private final Ast.ClassDecl declaration;
    private final String className;
    private final String fieldName;
    private final int column;
    private final Access access;
    private final List<PhysicalField> schema;

    private InvertedIndexPlan(
            Ast.IndexExpr site,
            Ast.ClassDecl declaration,
            String className,
            String fieldName,
            int column,
            Access access,
            List<PhysicalField> schema) {
        this.site = Objects.requireNonNull(site, "inverted index site");
        this.declaration = Objects.requireNonNull(declaration, "inverted nominal declaration");
        this.className = Objects.requireNonNull(className, "inverted class name");
        this.fieldName = Objects.requireNonNull(fieldName, "inverted field name");
        this.column = column;
        this.access = Objects.requireNonNull(access, "inverted index access");
        this.schema = List.copyOf(schema);
    }

    static InvertedIndexPlan compile(
            Ast.ClassDecl invertedClass,
            Ast.IndexExpr site,
            String fieldName,
            Access access) {
        return compile(invertedClass, Map.of(), site, fieldName, access);
    }

    static InvertedIndexPlan compile(
            Ast.ClassDecl invertedClass,
            Map<String, Ast.TypeRef> aliases,
            Ast.IndexExpr site,
            String fieldName,
            Access access) {
        Objects.requireNonNull(invertedClass, "inverted class");
        Objects.requireNonNull(aliases, "inverted aliases");
        Objects.requireNonNull(site, "inverted index site");
        Objects.requireNonNull(fieldName, "inverted field");
        Objects.requireNonNull(access, "inverted access");

        if (!(site.receiver() instanceof Ast.NameExpr)) {
            throw new IllegalArgumentException(
                    "inverted [] v1 requires a direct named receiver; "
                            + "member/dynamic receivers need an ownership provenance proof");
        }
        requireScalarNonSuspending(site.index(), "inverted [] index");

        InvertedArray.Schema physical =
                InvertedArray.Schema.fromClass(invertedClass, aliases);
        int column = physical.index(fieldName);

        Ast.FieldDecl declared = invertedClass.fields().stream()
                .filter(field -> field.name().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "inverted field is not directly declared: "
                                + invertedClass.name() + "." + fieldName));
        if (access == Access.WRITE
                && declared.bindingKind() != Ast.BindingKind.LET) {
            throw new IllegalArgumentException(
                    "inverted field '" + invertedClass.name() + "." + fieldName
                            + "' is immutable; bracket lowering cannot write it");
        }

        List<PhysicalField> fingerprint = new ArrayList<>();
        for (InvertedArray.Schema.Field field : physical.fields()) {
            int recordBytes = field.recordLayout() == null
                    ? 0 : field.recordLayout().bytes();
            fingerprint.add(new PhysicalField(
                    field.name(), field.kind(), recordBytes));
        }
        return new InvertedIndexPlan(
                site, invertedClass, physical.className(), fieldName,
                column, access, fingerprint);
    }

    public String className() { return className; }
    public String fieldName() { return fieldName; }
    public int column() { return column; }
    public Access access() { return access; }

    /** Identity check: structural equality is not a valid staging-site match. */
    public void requireExactSite(Ast.IndexExpr candidate) {
        if (candidate != site) {
            throw new IllegalStateException(
                    "inverted index lowering proof does not belong to this AST site");
        }
    }

    /**
     * Bind the static plan to one runtime column owner. The complete physical
     * schema is checked so same-name but layout-different classes cannot reuse a
     * plan.
     */
    public Bound bind(InvertedArray array) {
        Objects.requireNonNull(array, "inverted array");
        InvertedArray.Schema actual = array.schema();
        if (!actual.representsDeclaration(declaration)
                || !actual.className().equals(className)
                || actual.fields().size() != schema.size()) {
            throw new IllegalArgumentException(
                    "inverted index plan/runtime schema mismatch");
        }
        for (int i = 0; i < schema.size(); i++) {
            PhysicalField expected = schema.get(i);
            InvertedArray.Schema.Field observed = actual.fields().get(i);
            int observedRecordBytes = observed.recordLayout() == null
                    ? 0 : observed.recordLayout().bytes();
            if (!observed.name().equals(expected.name())
                    || observed.kind() != expected.kind()
                    || observedRecordBytes != expected.recordBytes()) {
                throw new IllegalArgumentException(
                        "inverted index plan/runtime schema mismatch at column " + i);
            }
        }
        InvertedArray.ColumnAccess field = array.bindField(fieldName);
        if (!field.name().equals(fieldName)
                || field.kind() != schema.get(column).kind()) {
            throw new IllegalStateException(
                    "inverted field binding disagrees with compiler plan");
        }
        return new Bound(array, field);
    }

    /**
     * Lowered read order: receiver exactly once, then index exactly once.
     */
    public Object read(
            Supplier<InvertedArray> receiver,
            LongSupplier index) {
        if (access != Access.READ) {
            throw new IllegalStateException("write-only inverted plan used for read");
        }
        InvertedArray evaluatedReceiver = Objects.requireNonNull(
                Objects.requireNonNull(receiver, "receiver supplier").get(),
                "inverted receiver");
        long evaluatedIndex =
                Objects.requireNonNull(index, "index supplier").getAsLong();
        return bind(evaluatedReceiver).read(evaluatedIndex);
    }

    /**
     * Lowered assignment order matches Oreslang: RHS once, receiver once,
     * index once, then commit.
     */
    public Object write(
            Supplier<Object> value,
            Supplier<InvertedArray> receiver,
            LongSupplier index) {
        if (access != Access.WRITE) {
            throw new IllegalStateException("read-only inverted plan used for write");
        }
        Object evaluatedValue =
                Objects.requireNonNull(value, "value supplier").get();
        InvertedArray evaluatedReceiver = Objects.requireNonNull(
                Objects.requireNonNull(receiver, "receiver supplier").get(),
                "inverted receiver");
        long evaluatedIndex =
                Objects.requireNonNull(index, "index supplier").getAsLong();
        bind(evaluatedReceiver).write(evaluatedIndex, evaluatedValue);
        return evaluatedValue;
    }

    public final class Bound {
        private final InvertedArray array;
        private final InvertedArray.ColumnAccess field;

        private Bound(
                InvertedArray array,
                InvertedArray.ColumnAccess field) {
            this.array = array;
            this.field = field;
        }

        public Object read(long index) {
            if (access != Access.READ) {
                throw new IllegalStateException(
                        "write-only inverted plan used for read");
            }
            try {
                // Resolve dense row and field under one store monitor.
                // Do not allocate a temporary RowView/Handle: swap-delete
                // between index selection and field read changes the row.
                return field.getAt(index);
            } catch (IndexOutOfBoundsException outside) {
                throw new IllegalArgumentException(
                        "inverted row index out of bounds: " + index, outside);
            }
        }

        public void write(long index, Object value) {
            if (access != Access.WRITE) {
                throw new IllegalStateException(
                        "read-only inverted plan used for write");
            }
            try {
                // Check dense row, field loan and write as one transaction.
                field.setAt(index, value);
            } catch (IndexOutOfBoundsException outside) {
                throw new IllegalArgumentException(
                        "inverted row index out of bounds: " + index, outside);
            }
        }
    }

    /**
     * Conservative syntax-only prefilter for a later verified integer index.
     * This is not type/effect evidence: even accepted NameExpr/binary sites
     * still require the full checker before guest lowering.
     *
     * Use an iterative bounded walk: malformed/synthesized ASTs must produce
     * a diagnostic instead of exhausting the compiler Java stack. The node
     * budget also limits repeated traversal of shared expression subgraphs.
     */
    private static final int MAX_INDEX_EXPR_DEPTH = 1024;
    private static final int MAX_INDEX_EXPR_NODES = 4096;
    private record IndexVisit(Ast.Expr expression, int depth) { }

    private static void requireScalarNonSuspending(
            Ast.Expr expression, String label) {
        Objects.requireNonNull(expression, "inverted index expression");
        java.util.ArrayDeque<IndexVisit> pending = new java.util.ArrayDeque<>();
        pending.push(new IndexVisit(expression, 0));
        int count = 0;
        while (!pending.isEmpty()) {
            IndexVisit visit = pending.pop();
            if (++count > MAX_INDEX_EXPR_NODES
                    || visit.depth() > MAX_INDEX_EXPR_DEPTH) {
                throw new IllegalArgumentException(
                        label + " expression exceeds index staging complexity budget");
            }
            Ast.Expr current = visit.expression();
            if (current instanceof Ast.NameExpr) continue;
            if (current instanceof Ast.LiteralExpr literal) {
                if (literal.value() instanceof Integer
                        || literal.value() instanceof Long) continue;
                throw new IllegalArgumentException(
                        label + " requires an integral i32/i64 literal, got "
                                + (literal.value() == null ? "null"
                                        : literal.value().getClass().getSimpleName()));
            }
            if (current instanceof Ast.UnaryExpr unary) {
                if (!unary.operator().equals("+")
                        && !unary.operator().equals("-")) {
                    throw new IllegalArgumentException(
                            label + " does not permit unary " + unary.operator());
                }
                pending.push(new IndexVisit(unary.operand(), visit.depth() + 1));
                continue;
            }
            if (current instanceof Ast.BinaryExpr binary) {
                if (!java.util.Set.of("+", "-", "*", "/", "%", "mod")
                        .contains(binary.operator())) {
                    throw new IllegalArgumentException(
                            label + " does not permit binary " + binary.operator());
                }
                // Push right before left to inspect left first.
                pending.push(new IndexVisit(binary.right(), visit.depth() + 1));
                pending.push(new IndexVisit(binary.left(), visit.depth() + 1));
                continue;
            }
            throw new IllegalArgumentException(
                    label + " must be a non-suspending integral expression; got "
                            + current.getClass().getSimpleName());
        }
    }

}
