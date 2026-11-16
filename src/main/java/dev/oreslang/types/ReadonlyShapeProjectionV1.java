package dev.oreslang.types;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Compiler-only, non-emitting proof for open, read-only i64 structural rows.
 * A projection preserves the ACTUAL source nominal ID and scalar slot.
 *
 * This is not a guest ABI and must never be used to coerce an object pointer,
 * authorize a write, cross an actor boundary, or synthesize a map key witness.
 * Callers must construct Source from validated compiler metadata, never
 * untrusted user-provided field descriptions.
 */
public final class ReadonlyShapeProjectionV1 {
    public static final int MAX_FIELDS = 32;

    private ReadonlyShapeProjectionV1() {}

    public enum Origin { CLASS, RECORD, STRUCT, MAP, ACTOR }
    public enum Scalar { I64, BOOL, STRING }

    public record Field(String name, Scalar type, int slot, boolean isPublic,
                        boolean immutable, boolean effectFree,
                        boolean sealedActorState) {}

    public record RequiredField(String name, Scalar type) {}

    public record Source(Origin origin, int nominalId, long layoutEpoch,
                         long loanId, boolean sealedActorState,
                         List<Field> fields) {}

    public record FieldWitness(String name, Scalar type, int sourceSlot) {}

    public record Plan(int sourceNominalId, long layoutEpoch, long loanId,
                       List<FieldWitness> fields) {
        public FieldWitness field(String name) {
            return fields.stream().filter(f -> f.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "field is not in readonly projection: " + name));
        }
    }

    public static Plan prove(Source source, List<RequiredField> required) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(required, "required");
        if (source.origin() != Origin.CLASS && source.origin() != Origin.RECORD
                && source.origin() != Origin.STRUCT) {
            throw new IllegalArgumentException("map/actor projection has no checked immutable ABI");
        }
        if (source.sealedActorState()) {
            throw new IllegalArgumentException("sealed actor state is never structurally projected");
        }
        if (source.nominalId() <= 0 || source.layoutEpoch() <= 0 || source.loanId() <= 0) {
            throw new IllegalArgumentException("missing source identity, layout generation or live read loan");
        }
        Objects.requireNonNull(source.fields(), "source fields");
        if (required.isEmpty() || required.size() > MAX_FIELDS
                || source.fields().size() > MAX_FIELDS) {
            throw new IllegalArgumentException("empty or oversized structural shape");
        }
        Set<String> allNames = new HashSet<>();
        Set<Integer> allSlots = new HashSet<>();
        for (Field field : source.fields()) {
            Objects.requireNonNull(field, "field");
            if (field.name() == null || field.name().isEmpty()
                    || field.type() == null || !allNames.add(field.name())) {
                throw new IllegalArgumentException("invalid or duplicate source field");
            }
            if (field.slot() < 0 || !allSlots.add(field.slot())) {
                throw new IllegalArgumentException("invalid or overlapping source layout slot");
            }
        }
        Set<String> requested = new HashSet<>();
        List<FieldWitness> witnesses = new ArrayList<>(required.size());
        for (RequiredField expected : required) {
            if (expected == null || expected.name() == null || expected.name().isEmpty()
                    || expected.type() == null || !requested.add(expected.name())) {
                throw new IllegalArgumentException("invalid or duplicate required field");
            }
            Field field = source.fields().stream()
                    .filter(f -> f.name().equals(expected.name()))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException(
                            "source field missing from structural row: " + expected.name()));
            if (field.sealedActorState()) {
                throw new IllegalArgumentException("sealed actor-state field cannot be projected");
            }
            if (expected.type() != Scalar.I64 || field.type() != Scalar.I64
                    || !field.isPublic() || !field.immutable() || !field.effectFree()
                    || field.type() != expected.type()) {
                throw new IllegalArgumentException("source field lacks public immutable exact type: "
                        + expected.name());
            }
            witnesses.add(new FieldWitness(field.name(), field.type(), field.slot()));
        }
        witnesses.sort(Comparator.comparing(FieldWitness::name));
        return new Plan(source.nominalId(), source.layoutEpoch(), source.loanId(),
                List.copyOf(witnesses));
    }
}
