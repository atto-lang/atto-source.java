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
    // A depth limit alone does not bound DAG-shaped types: a tuple that
    // references the preceding tuple twice can require 2^depth visits.
    // Count every node examination and reject oversized proofs.
    private static final int MAX_PROOF_NODES = 4096;

    /**
     * Unresolved, borrowed, executable, and actor-like types cannot provide
     * a closed proof of readonly positional slot compatibility. This is
     * intentionally stricter than ordinary tuple assignability.
     */
    private static boolean certifiedSlotType(Type type) {
        // Budget is per proof, not shared between threads or compiler passes.
        return certifiedSlotType(type, 0, new int[]{MAX_PROOF_NODES});
    }

    private static boolean certifiedSlotType(Type type, int depth, int[] remaining) {
        if (depth > MAX_ARITY || remaining[0]-- <= 0) return false;
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
            return certifiedSlotType(named.arguments().getFirst(), depth + 1, remaining);
        }
        if (type instanceof Types.Tuple tuple) {
            // Nested FixedArray/FixedList values need their own alias and
            // ownership proof. They are not positional tuple views.
            if (tuple.kind() != TupleKind.TUPLE || tuple.elements().size() < 2
                    || tuple.elements().size() > MAX_ARITY) {
                return false;
            }
            for (Type element : tuple.elements()) if (!certifiedSlotType(element, depth + 1, remaining)) return false;
            return true;
        }
        if (type instanceof Types.Union union) {
            if (union.options().size() > MAX_ARITY) return false;
            for (Type alternative : union.options()) {
                if (!certifiedSlotType(alternative, depth + 1, remaining)) return false;
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
            if (slots.size() < 2) {
                throw new IllegalArgumentException(
                        "positional tuple shape requires at least two slots");
            }
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
     * Materialization modes for a checked, finite, positional projection.
     * These are lowering instructions, NOT runtime values: the emitter must
     * evaluate the source tuple exactly once, then materialize slots in order.
     */
    public enum Materialization {
        KEEP_VALUE,
        WRAP_SOME,
        KEEP_OPTION,
        SYNTHESIZE_NONE
    }

    /**
     * Lifetime and capability information survives even when a readonly
     * structural view hides no positions. Equality of source and visible
     * arity is NOT permission to escape across return, task, await or actor
     * boundaries. Ownership/escape analysis must consume this tag.
     */
    public enum ProjectionKind {
        EXACT_TUPLE_VALUE,
        READONLY_NONESCAPING_VIEW
    }

    /**
     * A single visible output slot, with its source provenance. A sourceIndex
     * of -1 is reserved for omitted optional trailing slots, never an alias
     * of an actual source position.
     */
    public static final class ProjectedSlot {
        private final int visibleIndex;
        private final int sourceIndex;
        private final Type outputType;
        private final Materialization materialization;

        // A caller cannot fabricate a verified slot without going through
        // projection(Shape, Type) and its closed-type/hidden-width checks.
        private ProjectedSlot(int visibleIndex, int sourceIndex, Type outputType,
                              Materialization materialization) {
            if (visibleIndex < 0 || sourceIndex < -1) {
                throw new IllegalArgumentException("invalid tuple projection index");
            }
            this.outputType = Objects.requireNonNull(outputType, "output type");
            this.materialization = Objects.requireNonNull(materialization, "materialization");
            if ((materialization == Materialization.SYNTHESIZE_NONE)
                    != (sourceIndex == -1)) {
                throw new IllegalArgumentException("only an absent optional slot may have no source");
            }
            if (materialization != Materialization.KEEP_VALUE
                    && !(outputType instanceof Named option
                         && option.name().equals("Option")
                         && option.arguments().size() == 1)) {
                throw new IllegalArgumentException("optional projection must expose Option<T>");
            }
            this.visibleIndex = visibleIndex;
            this.sourceIndex = sourceIndex;
        }

        public int visibleIndex() { return visibleIndex; }
        public int sourceIndex() { return sourceIndex; }
        public Type outputType() { return outputType; }
        public Materialization materialization() { return materialization; }
    }

    /**
     * Compiler-owned, immutable projection proof. Hidden source slots are
     * deliberately absent from the visible slot table. There is no accessor
     * from this plan to a hidden element; runtime views must enforce the same
     * visible width rather than exposing the original tuple for indexing.
     *
     * This object proves only static slot compatibility. It does not grant
     * permission to return, capture, suspend, send, or store a readonly view.
     */
    public static final class ProjectionPlan {
        private final int sourceArity;
        private final int hiddenTailWidth;
        private final ProjectionKind kind;
        private final List<ProjectedSlot> visibleSlots;

        private ProjectionPlan(int sourceArity, int hiddenTailWidth,
                               ProjectionKind kind, List<ProjectedSlot> visibleSlots) {
            this.sourceArity = sourceArity;
            this.hiddenTailWidth = hiddenTailWidth;
            this.kind = Objects.requireNonNull(kind, "projection kind");
            this.visibleSlots = List.copyOf(visibleSlots);
            if (sourceArity < 2 || sourceArity > MAX_ARITY
                    || visibleSlots.size() < 2 || visibleSlots.size() > MAX_ARITY
                    || hiddenTailWidth != Math.max(0, sourceArity - visibleSlots.size())
                    || (hiddenTailWidth > 0 && kind != ProjectionKind.READONLY_NONESCAPING_VIEW)) {
                throw new IllegalArgumentException("inconsistent tuple projection bounds");
            }
            for (int i = 0; i < visibleSlots.size(); i++) {
                ProjectedSlot slot = visibleSlots.get(i);
                if (slot.visibleIndex() != i
                        || (slot.sourceIndex() != i && slot.sourceIndex() != -1)
                        || (slot.sourceIndex() >= sourceArity)) {
                    throw new IllegalArgumentException("tuple projection provenance is not positional");
                }
            }
        }

        public int sourceArity() { return sourceArity; }
        public int visibleWidth() { return visibleSlots.size(); }
        public int hiddenTailWidth() { return hiddenTailWidth; }
        public ProjectionKind kind() { return kind; }
        public boolean requiresNonescapingOwnershipCheck() {
            return kind == ProjectionKind.READONLY_NONESCAPING_VIEW;
        }
        public List<ProjectedSlot> visibleSlots() { return visibleSlots; }
        public ProjectedSlot visibleSlot(int index) { return visibleSlots.get(index); }
    }

    /**
     * Produce an explicit, immutable plan suitable for later checked lowering.
     * Optional omission produces None; a supplied T produces Some(T); a
     * supplied Option<T> remains an Option<T>. In particular, null is NOT an
     * omission sentinel. Neither host reflection nor numeric coercion appears
     * in this proof. A rejected view yields Optional.empty().
     */
    public static java.util.Optional<ProjectionPlan> projection(Shape expected, Type actual) {
        Objects.requireNonNull(expected, "expected");
        if (!(actual instanceof Tuple source) || source.kind() != TupleKind.TUPLE) {
            return java.util.Optional.empty();
        }
        int count = source.elements().size();
        int width = expected.slots().size();
        // A 0/1-element product is grouping/unit syntax, not a tuple source.
        if (count < 2 || count > MAX_ARITY || count < expected.required()
                || (!expected.structural() && count > width)) {
            return java.util.Optional.empty();
        }

        List<ProjectedSlot> projected = new ArrayList<>(width);
        for (int i = 0; i < width; i++) {
            Slot expectedSlot = expected.slots().get(i);
            Type output = expectedSlot.omittable()
                    ? new Named("Option", List.of(expectedSlot.type()))
                    : expectedSlot.type();
            if (i >= count) {
                if (!expectedSlot.omittable()) return java.util.Optional.empty();
                projected.add(new ProjectedSlot(
                        i, -1, output, Materialization.SYNTHESIZE_NONE));
                continue;
            }

            Type supplied = source.elements().get(i);
            if (!certifiedSlotType(supplied)) return java.util.Optional.empty();
            if (supplied.equals(expectedSlot.type())) {
                projected.add(new ProjectedSlot(i, i, output,
                        expectedSlot.omittable()
                                ? Materialization.WRAP_SOME
                                : Materialization.KEEP_VALUE));
                continue;
            }
            if (expectedSlot.omittable()
                    && supplied instanceof Named option
                    && option.name().equals("Option")
                    && option.arguments().size() == 1
                    && option.arguments().getFirst().equals(expectedSlot.type())) {
                projected.add(new ProjectedSlot(i, i, output, Materialization.KEEP_OPTION));
                continue;
            }
            return java.util.Optional.empty();
        }

        // An explicitly structural projection can hide only proven closed,
        // readonly-safe positions. Hidden indexes never appear in the plan.
        for (int i = width; i < count; i++) {
            if (!certifiedSlotType(source.elements().get(i))) {
                return java.util.Optional.empty();
            }
        }
        return java.util.Optional.of(new ProjectionPlan(
                count, Math.max(0, count - width),
                expected.structural()
                        ? ProjectionKind.READONLY_NONESCAPING_VIEW
                        : ProjectionKind.EXACT_TUPLE_VALUE,
                projected));
    }

    /** Conservative boolean facade for callers not yet using a typed plan. */
    public static boolean admits(Shape expected, Type actual) {
        return projection(expected, actual).isPresent();
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
     * An immutable, compiler-authored overload resolution. Pack lowering
     * consumes the exact projection verified during selection, rather than
     * repeating a potentially divergent shape lookup later.
     *
     * Runtime execution and ownership checks are deliberately NOT granted
     * by this token. Only checked lowering may consume this static result.
     */
    public static final class ResolvedSpread {
        private final Selection selection;
        private final ProjectionPlan packingProjection;

        private ResolvedSpread(Selection selection, ProjectionPlan packingProjection) {
            this.selection = Objects.requireNonNull(selection, "spread selection");
            if (selection.pack() != (packingProjection != null)) {
                throw new IllegalArgumentException("spread packing proof disagrees with selected call");
            }
            this.packingProjection = packingProjection;
        }

        public Selection selection() { return selection; }
        public java.util.Optional<ProjectionPlan> packingProjection() {
            return java.util.Optional.ofNullable(packingProjection);
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
        // Compatibility facade: new emitter integrations must use the proof
        // bearing ResolvedSpread rather than reconstruct the pack projection.
        return resolveExplicitTupleSpread(family, source).selection();
    }

    public static ResolvedSpread resolveExplicitTupleSpread(List<Candidate> family, Type source) {
        Objects.requireNonNull(family, "family");
        if (family.size() > MAX_FAMILY) {
            throw new IllegalArgumentException("tuple overload family exceeds 128 candidates");
        }
        // Freeze the caller's candidate list once: validation and selection
        // must never traverse different snapshots of a mutable family.
        List<Candidate> candidates = List.copyOf(family);
        if (!(source instanceof Tuple tuple) || tuple.kind() != TupleKind.TUPLE) {
            throw new IllegalArgumentException("spread needs a statically typed finite tuple");
        }
        if (candidates.size() > MAX_FAMILY || tuple.elements().size() < 2
                || tuple.elements().size() > MAX_ARITY) {
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
                return new ResolvedSpread(new Selection(candidate.id(), false), null);
            }
        }
        List<ResolvedSpread> eligible = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (candidate.tupleParameter() == null) continue;
            var plan = projection(candidate.tupleParameter(), source);
            if (plan.isPresent()) {
                eligible.add(new ResolvedSpread(
                        new Selection(candidate.id(), true), plan.get()));
            }
        }
        if (eligible.size() != 1) {
            throw new IllegalArgumentException(eligible.isEmpty()
                    ? "no callable accepts explicit tuple spread"
                    : "ambiguous tuple packing cannot use runtime shape dispatch");
        }
        return eligible.getFirst();
    }
}
