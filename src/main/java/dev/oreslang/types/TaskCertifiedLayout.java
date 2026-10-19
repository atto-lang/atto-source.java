package dev.oreslang.types;

import java.util.List;
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

        RecordStorage freshRecord(Types.Record shape) {
            Objects.requireNonNull(shape, "record shape");
            if (shape.members().isEmpty() || shape.members().size() > 1024
                    || shape.members().values().stream().anyMatch(t -> !inlineScalar(t))) {
                throw new IllegalArgumentException(
                        "only nonempty, flat scalar inline records can establish layout proofs");
            }
            return new RecordStorage(this, newRoot(), Map.copyOf(shape.members()), shape.readOnly());
        }

        FixedArrayStorage freshFixedArray(Types.Tuple shape) {
            Objects.requireNonNull(shape, "array shape");
            if (shape.kind() != Types.TupleKind.FIXED_ARRAY
                    || shape.elements().isEmpty() || shape.elements().size() > 65536
                    || shape.elements().stream().anyMatch(t -> !inlineScalar(t))) {
                throw new IllegalArgumentException(
                        "only bounded fixed scalar arrays can establish partition proofs");
            }
            return new FixedArrayStorage(this, newRoot(), List.copyOf(shape.elements()));
        }

        private Access access(TaskAccessVerifier.Place place, Types.Type type,
                              TaskAccessVerifier.Mode mode) {
            if (sealed) throw new IllegalStateException("Task layout issuer is sealed");
            return new Access(this, new TaskAccessVerifier.TypedClaim(
                    new TaskAccessVerifier.Claim(place, mode), type));
        }

        TaskAccessVerifier.Ticket admit(List<Access> requested) {
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

        void finish() {
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
