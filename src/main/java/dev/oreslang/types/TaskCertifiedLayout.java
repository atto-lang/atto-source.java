package dev.oreslang.types;

import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import dev.oreslang.ast.Ast;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Narrow proof construction for fresh, inline scalar storage in a compiler
 * Task scope. No user-supplied allocation IDs, alias flags, field-layout
 * booleans, owner identifiers, or arbitrary range endpoints can be admitted.
 *
 * <p>This is compiler metadata, NOT a runtime memory capability. Only invoke
 * freshRecord/freshFixedArray after the owning checker has proven a newly
 * allocated, unaliased value. The source compiler has not yet wired that gate;
 * no guest Task execution or shared-memory loan is authorized by this class.
 */
final class TaskCertifiedLayout {
    private static final AtomicLong NEXT_ISSUER = new AtomicLong(1);

    private TaskCertifiedLayout() { }

    private static boolean inlineScalar(Types.Type type) {
        return type == Types.Primitive.INT || type == Types.Primitive.FLOAT
                || type == Types.Primitive.BOOL;
    }

    /** Unforgeable within the emitter API: only the emitter may build claims. */
    static final class Access {
        private final Emitter issuer;
        private final TaskAccessVerifier.TypedClaim claim;

        private Access(Emitter issuer, TaskAccessVerifier.TypedClaim claim) {
            this.issuer = issuer;
            this.claim = claim;
        }
    }

    static final class Emitter {
        private final TaskAccessVerifier ledger = new TaskAccessVerifier();
        private final String ownerId;
        private long nextAllocationId = 1;
        private final IdentityHashMap<Ast.Expr, Object> issuedSites = new IdentityHashMap<>();
        private boolean sealed;

        Emitter() {
            long serial = NEXT_ISSUER.getAndIncrement();
            if (serial <= 0) throw new IllegalStateException("Task layout issuer overflow");
            ownerId = "compiler-task-scope-" + serial;
        }

        private TaskAccessVerifier.Root newRoot() {
            if (sealed) throw new IllegalStateException("Task layout issuer is sealed");
            if (nextAllocationId == Long.MAX_VALUE) {
                throw new IllegalStateException("Task allocation identity overflow");
            }
            // The caller has already proven a fresh owned allocation. IDs
            // represent that analysis fact, NOT arbitrary source variable names.
            return new TaskAccessVerifier.Root(
                    "PROCESS", ownerId, nextAllocationId++, 1, true);
        }

        /**
         * First source-front-end gateway: a fresh anonymous struct literal,
         * not an arbitrary binding or borrowed value. The ownership pass must
         * still prove its destination is owned and cannot escape during Tasks.
         * One AST evaluation site cannot mint multiple independent roots in
         * this analysis scope; a later loop lowering needs per-iteration IDs.
         */
        synchronized RecordStorage freshRecordLiteral(Ast.ObjectExpr expression,
                                                     Types.Record shape) {
            Objects.requireNonNull(expression, "expression");
            Objects.requireNonNull(shape, "shape");
            if (expression.inferredStruct() && !shape.readOnly()) {
                throw new IllegalArgumentException(
                        "inferred structs are readonly; cannot certify mutable layout");
            }
            if (expression.fields().size() != shape.members().size()) {
                throw new IllegalArgumentException("struct literal fields differ from checked type");
            }
            Set<String> seen = new HashSet<>();
            for (Ast.ObjectField field : expression.fields()) {
                Types.Type expected = shape.members().get(field.name());
                if (expected == null || !seen.add(field.name())
                        || !(field.value() instanceof Ast.LiteralExpr literal)
                        || !scalarLiteralMatches(literal.value(), expected)) {
                    throw new IllegalArgumentException(
                            "only exactly typed fresh scalar literals can issue field certificates");
                }
            }
            if (issuedSites.containsKey(expression)) {
                throw new IllegalStateException(
                        "allocation site already certified; reuse original compiler storage identity");
            }
            if (issuedSites.size() >= 65536) {
                throw new IllegalStateException("source allocation proof budget exceeded");
            }
            RecordStorage result = modelFreshRecordUnchecked(shape);
            issuedSites.put(expression, result);
            return result;
        }

        private static boolean scalarLiteralMatches(Object value, Types.Type expected) {
            if (expected == Types.Primitive.INT) return value instanceof Long;
            if (expected == Types.Primitive.FLOAT) return value instanceof Double;
            if (expected == Types.Primitive.BOOL) return value instanceof Boolean;
            return false;
        }

        /** Model fixture ONLY; not a source-level freshness or layout proof. */
        synchronized RecordStorage modelFreshRecordUnchecked(Types.Record shape) {
            Objects.requireNonNull(shape, "record shape");
            if (shape.members().isEmpty() || shape.members().size() > 1024
                    || shape.members().values().stream().anyMatch(t -> !inlineScalar(t))) {
                throw new IllegalArgumentException(
                        "only nonempty, flat scalar inline records can establish layout proofs");
            }
            return new RecordStorage(this, newRoot(), Map.copyOf(shape.members()), shape.readOnly());
        }

        /** Model fixture ONLY; fixed-array physical layout is unverified. */
        synchronized FixedArrayStorage modelFreshFixedArrayUnchecked(Types.Tuple shape) {
            Objects.requireNonNull(shape, "array shape");
            if (shape.kind() != Types.TupleKind.FIXED_ARRAY
                    || shape.elements().isEmpty() || shape.elements().size() > 65536
                    || shape.elements().stream().anyMatch(t -> !inlineScalar(t))) {
                throw new IllegalArgumentException(
                        "only bounded fixed scalar arrays can establish partition proofs");
            }
            return new FixedArrayStorage(this, newRoot(), List.copyOf(shape.elements()));
        }

        private synchronized Access access(TaskAccessVerifier.Place place, Types.Type type,
                              TaskAccessVerifier.Mode mode) {
            if (sealed) throw new IllegalStateException("Task layout issuer is sealed");
            return new Access(this, new TaskAccessVerifier.TypedClaim(
                    new TaskAccessVerifier.Claim(place, mode), type));
        }

        synchronized TaskAccessVerifier.Ticket admit(List<Access> requested) {
            if (sealed) throw new IllegalStateException("Task layout issuer is sealed");
            List<Access> entries = List.copyOf(Objects.requireNonNull(requested, "claims"));
            for (Access access : entries) {
                if (access.issuer != this) {
                    throw new IllegalArgumentException("foreign Task layout certificate");
                }
            }
            return ledger.admitTyped(entries.stream().map(e -> e.claim).toList(),
                    (nominal, trait) -> false);
        }

        void cancel(TaskAccessVerifier.Ticket ticket) { ledger.requestCancel(ticket); }

        void quiescent(TaskAccessVerifier.Ticket ticket) {
            // ANALYSIS-ONLY transition. Do not call from guest runtime based
            // on a cancelled Future: physical TaskScope join is still pending.
            ledger.quiescent(ticket);
        }

        int active() { return ledger.activeChildren(); }

        synchronized void finish() {
            ledger.finishScope();
            sealed = true;
        }
    }

    static final class RecordStorage {
        private final Emitter issuer;
        private final TaskAccessVerifier.Root root;
        private final Map<String, Types.Type> fields;
        private final boolean readonly;

        private RecordStorage(Emitter issuer, TaskAccessVerifier.Root root,
                              Map<String, Types.Type> fields, boolean readonly) {
            this.issuer = issuer;
            this.root = root;
            this.fields = fields;
            this.readonly = readonly;
        }

        Access readField(String field) { return field(field, TaskAccessVerifier.Mode.READ); }
        Access writeField(String field) { return field(field, TaskAccessVerifier.Mode.WRITE); }

        private Access field(String name, TaskAccessVerifier.Mode mode) {
            Types.Type type = fields.get(Objects.requireNonNull(name, "field"));
            if (type == null) throw new IllegalArgumentException("unknown verified scalar field");
            if (mode == TaskAccessVerifier.Mode.WRITE && readonly) {
                throw new IllegalArgumentException("readonly record cannot grant mutable access");
            }
            return issuer.access(TaskAccessVerifier.Place.whole(root).field(name, true), type, mode);
        }
    }

    static final class FixedArrayStorage {
        private final Emitter issuer;
        private final TaskAccessVerifier.Root root;
        private final List<Types.Type> elements;

        private FixedArrayStorage(Emitter issuer, TaskAccessVerifier.Root root,
                                  List<Types.Type> elements) {
            this.issuer = issuer;
            this.root = root;
            this.elements = elements;
        }

        Access readRange(int from, int until) {
            return range(from, until, TaskAccessVerifier.Mode.READ);
        }

        Access writeRange(int from, int until) {
            return range(from, until, TaskAccessVerifier.Mode.WRITE);
        }

        private Access range(int from, int until, TaskAccessVerifier.Mode mode) {
            if (from < 0 || until <= from || until > elements.size()) {
                throw new IllegalArgumentException("Task partition outside proven array bounds");
            }
            // The verifier needs only Send/Sync of a scalar element type;
            // a heterogeneous fixed array is supported only when the range
            // consists of one identical scalar representation.
            Types.Type type = elements.get(from);
            for (int index = from + 1; index < until; index++) {
                if (elements.get(index) != type) {
                    throw new IllegalArgumentException("heterogeneous partition needs layout lowering");
                }
            }
            return issuer.access(TaskAccessVerifier.Place.whole(root).range(from, until, true),
                    type, mode);
        }
    }
}
