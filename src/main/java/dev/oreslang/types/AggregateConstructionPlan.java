package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.Types.AggregateKind;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.Record;
import dev.oreslang.types.Types.Type;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, type-checked construction recipe for a closed anonymous aggregate.
 *
 * <p>This is FRONTEND METADATA, not runtime authority. In particular it does not
 * prove a source is owned, immutable or actor-sendable: OwnershipChecker retains
 * the independent Copy/borrowing/snapshot proof, and the runtime has its own
 * tagged-value guard. No dynamic Map, reflection or implicit heap promotion is
 * admitted. Future LLVM/Wasm/JS/.NET lowering must consume a fully checked
 * version of these ordered steps instead of reinterpreting source syntax.
 */
public final class AggregateConstructionPlan {
    private AggregateConstructionPlan() { }

    /**
     * Identity-bound checker provenance. Recording a typed shape is NOT runtime
     * admission: the ownership checker commits approvals only if its entire
     * program pass succeeds. Weak identities prevent cross-parse equal-object
     * collisions and avoid retaining obsolete ASTs. Optimizers must recheck
     * transformed aggregates rather than copy this authority by shape.
     */
    public static final class Checked {
        private Checked() { }

        private static final java.lang.ref.ReferenceQueue<Ast.ObjectExpr> CLEARED =
                new java.lang.ref.ReferenceQueue<>();
        private static final Map<IdentityKey, Plan> TYPED = new java.util.HashMap<>();
        private static final Map<IdentityKey, Plan> ADMITTED = new java.util.HashMap<>();
        private static final java.lang.ref.ReferenceQueue<Ast.Program> PROGRAMS_CLEARED =
                new java.lang.ref.ReferenceQueue<>();
        /** Proof of which exact compilation unit admitted each AST node. */
        private static final Map<ProgramIdentity, java.util.Set<IdentityKey>> TYPED_UNITS =
                new java.util.HashMap<>();
        private static final Map<ProgramIdentity, java.util.Set<IdentityKey>> ADMITTED_UNITS =
                new java.util.HashMap<>();

        private static final class ProgramIdentity
                extends java.lang.ref.WeakReference<Ast.Program> {
            private final int hash;
            private ProgramIdentity(
                    Ast.Program program,
                    java.lang.ref.ReferenceQueue<Ast.Program> queue) {
                super(Objects.requireNonNull(program, "compilation unit"), queue);
                hash = System.identityHashCode(program);
            }
            @Override public int hashCode() { return hash; }
            @Override public boolean equals(Object other) {
                if (this == other) return true;
                if (!(other instanceof ProgramIdentity key)) return false;
                Ast.Program value = get();
                return value != null && value == key.get();
            }
        }

        private static final class IdentityKey
                extends java.lang.ref.WeakReference<Ast.ObjectExpr> {
            private final int hash;
            private IdentityKey(
                    Ast.ObjectExpr expression,
                    java.lang.ref.ReferenceQueue<Ast.ObjectExpr> queue) {
                super(Objects.requireNonNull(expression, "aggregate expression"), queue);
                hash = System.identityHashCode(expression);
            }
            @Override public int hashCode() { return hash; }
            @Override public boolean equals(Object other) {
                if (this == other) return true;
                if (!(other instanceof IdentityKey key)) return false;
                Ast.ObjectExpr referent = get();
                return referent != null && referent == key.get();
            }
        }

        private static void expunge() {
            java.lang.ref.Reference<? extends Ast.ObjectExpr> collected;
            while ((collected = CLEARED.poll()) != null) {
                TYPED.remove(collected);
                ADMITTED.remove(collected);
            }
            java.lang.ref.Reference<? extends Ast.Program> unit;
            while ((unit = PROGRAMS_CLEARED.poll()) != null) {
                TYPED_UNITS.remove(unit);
                ADMITTED_UNITS.remove(unit);
            }
        }

        /**
         * Publish no candidate until the WHOLE type-checking pass succeeds.
         * Staging inside a single TypeChecker instance prevents a later error
         * from leaving a usable partial type proof behind.
         */
        static synchronized void recordTypedUnit(
                Ast.Program program, Map<Ast.ObjectExpr, Plan> plans) {
            Objects.requireNonNull(program, "typed compilation unit");
            Objects.requireNonNull(plans, "staged aggregate construction plans");
            expunge();
            ProgramIdentity unit = new ProgramIdentity(program, PROGRAMS_CLEARED);
            java.util.Set<IdentityKey> nodes = new java.util.HashSet<>();
            for (Ast.ObjectExpr source : plans.keySet()) {
                nodes.add(new IdentityKey(source, CLEARED));
            }
            java.util.Set<IdentityKey> priorUnit = TYPED_UNITS.get(unit);
            if (priorUnit != null && !priorUnit.equals(nodes)) {
                throw new IllegalStateException(
                        "same compilation unit received inconsistent aggregate spread nodes");
            }
            for (Map.Entry<Ast.ObjectExpr, Plan> entry : plans.entrySet()) {
                Ast.ObjectExpr source = Objects.requireNonNull(entry.getKey(), "source AST");
                Plan plan = Objects.requireNonNull(entry.getValue(), "typed construction plan");
                if (source.kind() != plan.resultKind()
                        || source.fields().size() != plan.orderedSteps().size()) {
                    throw new IllegalArgumentException(
                            "typed construction plan does not describe its source AST");
                }
                Plan prior = TYPED.get(new IdentityKey(source, null));
                if (prior != null && !prior.equals(plan)) {
                    throw new IllegalStateException(
                            "same aggregate AST received inconsistent complete type proofs");
                }
            }
            // The preflight above is deliberately separate from mutation:
            // one collision must never publish a subset of this pass.
            for (Map.Entry<Ast.ObjectExpr, Plan> entry : plans.entrySet()) {
                TYPED.put(new IdentityKey(entry.getKey(), CLEARED), entry.getValue());
            }
            TYPED_UNITS.put(unit, java.util.Set.copyOf(nodes));
        }

        /**
         * Atomically finalize an entire set of ownership-checked spread nodes.
         * A missing type proof is not accepted. Independent ownership-only
         * smoke checks may still run, but do not grant executable authority.
         */
        static synchronized void admitAfterOwnership(
                Ast.Program program, java.util.Collection<Ast.ObjectExpr> checkedNodes) {
            Objects.requireNonNull(program, "ownership-checked compilation unit");
            Objects.requireNonNull(checkedNodes, "ownership-checked aggregates");
            expunge();
            ProgramIdentity unit = new ProgramIdentity(program, PROGRAMS_CLEARED);
            java.util.Set<IdentityKey> typed = TYPED_UNITS.get(unit);
            java.util.Set<IdentityKey> admittedNodes = new java.util.HashSet<>();
            for (Ast.ObjectExpr source : checkedNodes) {
                IdentityKey key = new IdentityKey(source, CLEARED);
                if (typed == null || !typed.contains(key) || !TYPED.containsKey(key)) {
                    throw new IllegalStateException(
                            "cannot admit aggregate spread outside its type-checked compilation unit");
                }
                admittedNodes.add(key);
            }
            // A checker path that silently skips an expression must not
            // authorize only part of a compilation unit.
            if (typed != null && !typed.equals(admittedNodes)) {
                throw new IllegalStateException(
                        "ownership pass did not validate every type-checked spread");
            }
            for (IdentityKey key : admittedNodes) {
                ADMITTED.put(key, TYPED.get(key));
            }
            ADMITTED_UNITS.put(unit, java.util.Set.copyOf(admittedNodes));
        }

        /** Runtime authorization is scoped to both exact program and expression. */
        public static synchronized Plan requireAdmitted(
                Ast.Program program, Ast.ObjectExpr source) {
            Objects.requireNonNull(program, "executing compilation unit");
            Objects.requireNonNull(source, "executing aggregate AST");
            expunge();
            java.util.Set<IdentityKey> admittedUnit =
                    ADMITTED_UNITS.get(new ProgramIdentity(program, null));
            IdentityKey node = new IdentityKey(source, null);
            Plan admitted = ADMITTED.get(node);
            if (admittedUnit == null || !admittedUnit.contains(node) || admitted == null) {
                throw new IllegalStateException(
                        "aggregate spread requires exact-program and AST type and ownership admission");
            }
            return admitted;
        }
    }

    public sealed interface Step permits NamedField, SpreadFields {
        int sourceIndex();
    }

    /**
     * Unicode scalar-value lexicographic order; identical to unsigned UTF-8
     * byte lexicographic order for valid Unicode. Java String.compareTo uses
     * UTF-16 code units and gives a different answer for some astral keys.
     * This choice is reproducible in native LLVM/WASM/JS/.NET code generation.
     */
    public static int compareStaticFieldNames(String left, String right) {
        Ast.validateStaticAggregateFieldName(left);
        Ast.validateStaticAggregateFieldName(right);
        int i = 0;
        int j = 0;
        while (i < left.length() && j < right.length()) {
            int a = left.codePointAt(i);
            int b = right.codePointAt(j);
            if (a != b) return Integer.compare(a, b);
            i += Character.charCount(a);
            j += Character.charCount(b);
        }
        return Boolean.compare(i < left.length(), j < right.length());
    }

    /** A directly initialized compile-time field, possibly replacing a spread field. */
    public record NamedField(int sourceIndex, String name, Type type) implements Step {
        public NamedField {
            if (sourceIndex < 0) throw new IllegalArgumentException("negative aggregate source index");
            Ast.validateStaticAggregateFieldName(name);
            Objects.requireNonNull(type, "named aggregate field type");
        }
    }

    /**
     * Statically admitted projection of a closed record or struct.
     * Projection fields use stable static-name order; source evaluation order
     * belongs to orderedSteps and is independent of physical storage order.
     */
    public record SpreadFields(
            int sourceIndex, AggregateKind sourceKind, Map<String, Type> fields)
            implements Step {
        public SpreadFields {
            if (sourceIndex < 0) throw new IllegalArgumentException("negative aggregate source index");
            if (sourceKind != AggregateKind.RECORD && sourceKind != AggregateKind.STRUCT) {
                throw new IllegalArgumentException("aggregate spread cannot project a contract row");
            }
            Objects.requireNonNull(fields, "spread projection fields");
            for (Map.Entry<String, Type> entry : fields.entrySet()) {
                Ast.validateStaticAggregateFieldName(entry.getKey());
                Objects.requireNonNull(entry.getValue(), "spread projection field type");
            }
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        }
    }

    /**
     * One statically scheduled field write after evaluating its source operand.
     * Every write targets a known result slot; overwritten writes are retained
     * because their source expressions may have observable effects.
     */
    public record FieldWrite(
            int sourceIndex, String fieldName, int targetSlot,
            Type sourceFieldType, boolean fromSpread) {
        public FieldWrite {
            if (sourceIndex < 0 || targetSlot < 0) {
                throw new IllegalArgumentException("negative static field write index");
            }
            Ast.validateStaticAggregateFieldName(fieldName);
            Objects.requireNonNull(sourceFieldType, "static field write type");
        }
    }

    public record Plan(
            Ast.ObjectKind resultKind,
            List<Step> orderedSteps,
            Map<String, Type> resolvedFields,
            Record resultType) {
        public Plan {
            Objects.requireNonNull(resultKind, "aggregate result kind");
            orderedSteps = List.copyOf(orderedSteps);
            resolvedFields = Collections.unmodifiableMap(new LinkedHashMap<>(resolvedFields));
            Objects.requireNonNull(resultType, "checked aggregate result type");
            if (!resultType.members().equals(resolvedFields)) {
                throw new IllegalArgumentException("plan result type differs from resolved fields");
            }
            boolean validStruct = resultKind == Ast.ObjectKind.INFERRED_STRUCT
                    && resultType.kind() == AggregateKind.STRUCT
                    && !resultType.readOnly();
            boolean validRecord = (resultKind == Ast.ObjectKind.RECORD
                    || resultKind == Ast.ObjectKind.INFERRED_RECORD)
                    && resultType.kind() == AggregateKind.RECORD
                    && resultType.readOnly();
            if (!validStruct && !validRecord) {
                throw new IllegalArgumentException("plan result kind and readonly capability mismatch");
            }
            LinkedHashMap<String, Type> reconstructed = new LinkedHashMap<>();
            boolean spreadPresent = orderedSteps.stream().anyMatch(SpreadFields.class::isInstance);
            for (int i = 0; i < orderedSteps.size(); i++) {
                Step step = orderedSteps.get(i);
                if (step.sourceIndex() != i) {
                    throw new IllegalArgumentException("plan steps must follow unique source indices");
                }
                if (step instanceof NamedField named) {
                    if (spreadPresent && !Builder.copyableScalar(named.type())) {
                        throw new IllegalArgumentException("non-scalar initializer in spreading plan");
                    }
                    reconstructed.put(named.name(), named.type());
                } else if (step instanceof SpreadFields spread) {
                    for (Map.Entry<String, Type> field : spread.fields().entrySet()) {
                        if (!Builder.copyableScalar(field.getValue())) {
                            throw new IllegalArgumentException("non-scalar spread projection");
                        }
                    }
                    reconstructed.putAll(spread.fields());
                }
            }
            if (!reconstructed.equals(resolvedFields)) {
                throw new IllegalArgumentException(
                        "plan output shape must equal the ordered field-write result");
            }
        }

        /**
         * Stable static write schedule suitable for non-reflective AOT lowering.
         * The outer sourceIndex is evaluated exactly once; each spread projects
         * its scalar slots in order, with rightmost overwrites addressing the
         * same output slot. This is NOT a resource cleanup/ownership proof.
         */
        public List<FieldWrite> scheduledWrites() {
            LinkedHashMap<String, Integer> slots = new LinkedHashMap<>();
            for (String fieldName : resolvedFields.keySet()) {
                slots.put(fieldName, slots.size());
            }
            ArrayList<FieldWrite> writes = new ArrayList<>();
            for (Step step : orderedSteps) {
                if (step instanceof NamedField named) {
                    writes.add(new FieldWrite(named.sourceIndex(), named.name(),
                            slots.get(named.name()), named.type(), false));
                } else if (step instanceof SpreadFields spread) {
                    for (Map.Entry<String, Type> field : spread.fields().entrySet()) {
                        writes.add(new FieldWrite(spread.sourceIndex(), field.getKey(),
                                slots.get(field.getKey()), field.getValue(), true));
                    }
                }
            }
            return List.copyOf(writes);
        }
    }

    public static final class Builder {
        private final Ast.ObjectExpr source;
        private final boolean hasSpread;
        private final ArrayList<Step> steps = new ArrayList<>();
        private final LinkedHashMap<String, Type> resolved = new LinkedHashMap<>();

        public Builder(Ast.ObjectExpr source) {
            this.source = Objects.requireNonNull(source, "aggregate expression");
            if (source.explicitStruct()) {
                throw new IllegalArgumentException(
                        "explicitly shaped structs require a separate definite-initialization plan");
            }
            hasSpread = source.fields().stream().anyMatch(Ast.ObjectField::spread);
        }

        private void requireNext(int index, boolean spread) {
            if (index != steps.size() || index >= source.fields().size()) {
                throw new IllegalArgumentException("aggregate plan source step is missing, repeated or out of order");
            }
            if (source.fields().get(index).spread() != spread) {
                throw new IllegalArgumentException("aggregate plan step disagrees with source AST");
            }
        }

        private static boolean copyableScalar(Type type) {
            return type == Primitive.INT || type == Primitive.FLOAT
                    || type == Primitive.BOOL || type == Primitive.STRING;
        }

        /** Named-field types must already have undergone ordinary literal widening. */
        public Builder addNamed(int sourceIndex, String name, Type type) {
            requireNext(sourceIndex, false);
            Ast.ObjectField field = source.fields().get(sourceIndex);
            if (!field.name().equals(name)) {
                throw new IllegalArgumentException("aggregate plan field identity mismatch");
            }
            Objects.requireNonNull(type, "aggregate named field type");
            if (hasSpread && !copyableScalar(type)) {
                throw new IllegalArgumentException(
                        "aggregate spread scalar-only stage rejects initializer field '" + name + "'");
            }
            steps.add(new NamedField(sourceIndex, name, type));
            resolved.put(name, type);
            return this;
        }

        public Builder addSpread(int sourceIndex, Type sourceType) {
            requireNext(sourceIndex, true);
            if (!(sourceType instanceof Record row) || row.kind() == AggregateKind.CONTRACT) {
                throw new IllegalArgumentException(
                        "aggregate spread requires an owned, closed struct or record type");
            }
            // Evaluate and validate the entire projection before inserting it:
            // a failed spread must not make a partially admitted compiler plan.
            LinkedHashMap<String, Type> projection = new LinkedHashMap<>();
            // Types.Record uses an order-independent map; derive deterministic
            // projection slots instead of trusting its iteration order.
            for (Map.Entry<String, Type> field : row.members().entrySet().stream()
                    .sorted((a, b) -> compareStaticFieldNames(a.getKey(), b.getKey()))
                    .toList()) {
                if (!copyableScalar(field.getValue())) {
                    throw new IllegalArgumentException(
                            "aggregate spread scalar-only stage rejects field '" + field.getKey() + "'");
                }
                projection.put(field.getKey(), field.getValue());
            }
            steps.add(new SpreadFields(sourceIndex, row.kind(), projection));
            resolved.putAll(projection);
            return this;
        }

        public Plan finish() {
            if (steps.size() != source.fields().size()) {
                throw new IllegalArgumentException("incomplete aggregate construction plan");
            }
            Record type;
            if (source.structuralRecord()) {
                type = new Record(resolved, true, AggregateKind.RECORD);
            } else if (source.inferredStruct()) {
                type = Record.struct(resolved);
            } else {
                throw new IllegalArgumentException("unsupported aggregate result kind " + source.kind());
            }
            return new Plan(source.kind(), steps, resolved, type);
        }
    }
}
