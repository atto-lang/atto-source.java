package dev.oreslang.types;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Tuple;
import dev.oreslang.types.Types.TupleKind;
import dev.oreslang.types.Types.Type;

/**
 * Conservative proof substrate for structural positional tuples. This is
 * compile-time-only: callers must not substitute it for emitter-bound calls.
 * In particular, this class never coerces numeric values, creates padded
 * tuples, inspects host reflection metadata, or performs runtime dispatch.
 *
 * contracts/structural/v2/README.md remains the cross-backend target.
 */
public final class StructuralTuplePolicy {
    private StructuralTuplePolicy() { }

    // Keep proof complexity aligned with the existing bounded nominal
    // overload selector; no source input can trigger unbounded shape work.
    private static final int MAX_ARITY = 64;
    private static final int MAX_FAMILY = 128;

    /**
     * Unresolved, borrowed, executable, and actor-like types cannot provide
     * a closed proof of readonly positional slot compatibility. This is
     * intentionally stricter than ordinary tuple assignability.
     */
    private static boolean certifiedSlotType(Type type) {
        return certifiedSlotType(type, 0);
    }

    private static boolean certifiedSlotType(Type type, int depth) {
        if (depth > MAX_ARITY) return false;
        if (type == null || type == Types.Unknown.INSTANCE
                || type instanceof Types.Borrow
                || type instanceof Types.Generic
                || type instanceof Types.SelfType
                || type instanceof Types.ClassNamespace
                || type instanceof Types.Function
                || type instanceof Types.Not) return false;
        if (type == Types.Primitive.VOID || type == Types.Primitive.NULL) return false;
        if (type instanceof Types.Named named) {
            // Do not pretend arbitrary nominal objects or ActorRef<T>
            // have a proven structural ownership/serialization contract.
            if (!named.name().equals("Option") || named.arguments().size() != 1) {
                return false;
            }
            return certifiedSlotType(named.arguments().getFirst(), depth + 1);
        }
        if (type instanceof Types.Tuple tuple) {
            if (tuple.elements().size() > MAX_ARITY) return false;
            for (Type element : tuple.elements()) if (!certifiedSlotType(element, depth + 1)) return false;
            return true;
        }
        if (type instanceof Types.Union union) {
            for (Type alternative : union.options()) {
                if (!certifiedSlotType(alternative, depth + 1)) return false;
            }
            return true;
        }
        if (type instanceof Types.Record) return false; // dedicated record/loan proof required
        if (type instanceof Types.ListType) return false; // mutable alias authority not proven
        return type instanceof Types.Primitive
                || type instanceof Types.StringLiteral
                || type instanceof Types.BoolLiteral;
    }

    public record Slot(Type type, boolean omittable) {
        public Slot {
            Objects.requireNonNull(type, "slot type");
            if (!certifiedSlotType(type)) {
                throw new IllegalArgumentException("tuple slot needs a closed, readonly-safe type proof");
            }
        }
    }

    public record Shape(List<Slot> slots, boolean structural) {
        public Shape {
            slots = List.copyOf(slots);
            if (slots.size() > MAX_ARITY) {
                throw new IllegalArgumentException("structural tuple width exceeds 64 slots");
            }
            boolean optionalStarted = false;
            for (Slot slot : slots) {
                Objects.requireNonNull(slot, "slot");
                if (optionalStarted && !slot.omittable()) {
                    throw new IllegalArgumentException(
                            "omittable tuple slots must form a contiguous trailing suffix");
                }
                optionalStarted |= slot.omittable();
            }
        }

        public int required() {
            int result = 0;
            for (Slot slot : slots) {
                if (slot.omittable()) break;
                result++;
            }
            return result;
        }
    }

    /**
     * Returns a *conservative* assignability proof. General coercion is never
     * used to supply a structural readonly projected tuple slot.
     */
    public static boolean admits(Shape expected, Type actual) {
        Objects.requireNonNull(expected, "expected");
        if (!(actual instanceof Tuple source) || source.kind() != TupleKind.TUPLE) return false;
        int count = source.elements().size();
        if (count > MAX_ARITY) return false;
        if (count < expected.required()) return false;
        if (!expected.structural() && count > expected.slots().size()) return false;
        int declaredPresent = Math.min(count, expected.slots().size());
        for (int i = 0; i < declaredPresent; i++) {
            Type supplied = source.elements().get(i);
            Slot slot = expected.slots().get(i);
            if (!certifiedSlotType(supplied)) return false;
            if (supplied.equals(slot.type())) continue;
            // The optional *slot* may be represented by T or Option<T>.
            // Whether Option<T> can be observed requires the later ABI/view
            // lowering, not merely this compile-time proof.
            if (slot.omittable()
                    && supplied instanceof Named option
                    && option.name().equals("Option")
                    && option.arguments().size() == 1
                    && option.arguments().getFirst().equals(slot.type())) {
                continue;
            }
            return false;
        }
        return true;
    }

    public record Candidate(String id, int arity, Shape tupleParameter) {
        public Candidate {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("candidate id is empty");
            if (arity < 0 || arity > MAX_ARITY) {
                throw new IllegalArgumentException("callable arity is outside supported range 0..64");
            }
            if (tupleParameter != null && arity != 1) {
                throw new IllegalArgumentException("tuple packing target must accept exactly one tuple parameter");
            }
        }
    }

    public record Selection(String id, boolean pack) {
        public Selection {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("tuple overload selection needs a bound callable ID");
            }
        }
    }

    /**
     * Resolve a single explicitly spread *statically typed finite tuple*.
     * A matching ordinary expanded-arity callable always shadows the packing
     * fallback, regardless of its parameter types. An eventual type mismatch
     * on that direct target is an error; it must never retry the tuple pack.
     * Nominal-enum same-arity families require a separate compiler proof.
     */
    public static Selection selectExplicitTupleSpread(List<Candidate> family, Type source) {
        Objects.requireNonNull(family, "family");
        // Freeze the caller's candidate list once: validation and selection
        // must never traverse different snapshots of a mutable family.
        List<Candidate> candidates = List.copyOf(family);
        if (!(source instanceof Tuple tuple) || tuple.kind() != TupleKind.TUPLE) {
            throw new IllegalArgumentException("spread needs a statically typed finite tuple");
        }
        if (candidates.size() > MAX_FAMILY || tuple.elements().size() > MAX_ARITY) {
            throw new IllegalArgumentException("tuple overload proof exceeds bounded family/arity limits");
        }
        Set<Integer> usedArities = new HashSet<>();
        Set<String> usedIds = new HashSet<>();
        for (Candidate candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            if (!usedIds.add(candidate.id())) {
                throw new IllegalArgumentException("duplicate callable target identity in overload family");
            }
            if (!usedArities.add(candidate.arity())) {
                throw new IllegalArgumentException(
                        "same-arity overload requires independent nominal-enum proof");
            }
        }
        for (Candidate candidate : candidates) {
            if (candidate.arity() == tuple.elements().size()) {
                return new Selection(candidate.id(), false);
            }
        }
        List<Candidate> eligible = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.tupleParameter() != null
                    && admits(candidate.tupleParameter(), source)) eligible.add(candidate);
        }
        if (eligible.size() != 1) {
            throw new IllegalArgumentException(eligible.isEmpty()
                    ? "no callable accepts explicit tuple spread"
                    : "ambiguous tuple packing cannot use runtime shape dispatch");
        }
        return new Selection(eligible.getFirst().id(), true);
    }
}
