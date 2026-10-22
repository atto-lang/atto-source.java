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

    public sealed interface Step permits NamedField, SpreadFields {
        int sourceIndex();
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
                    .sorted(Map.Entry.comparingByKey()).toList()) {
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
