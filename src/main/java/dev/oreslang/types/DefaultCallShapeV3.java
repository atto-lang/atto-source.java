package dev.oreslang.types;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, side-effect-free binding model for a previously checked v3 call target.
 * This class never evaluates an expression, proves a nominal Option type,
 * grants an effect/capability, or authorizes interpreter/LLVM dispatch.
 * All user-visible argument indexes exclude the hidden method receiver.
 */
public final class DefaultCallShapeV3 {
    private DefaultCallShapeV3() { }

    public enum ConstantKind { INTEGER, BOOLEAN, STRING, NONE }
    public enum Origin { PROVIDED, EXPLICIT_OMISSION, UNSUPPLIED }
    public enum Fill { EXPRESSION, DEFAULT_VALUE, DEFAULT_SOME, DEFAULT_NONE, IMPLICIT_NONE }

    public record Constant(ConstantKind kind, Object value) {
        public Constant {
            Objects.requireNonNull(kind, "constant kind");
            boolean good = switch (kind) {
                case INTEGER -> value instanceof Long;
                case BOOLEAN -> value instanceof Boolean;
                case STRING -> value instanceof String;
                case NONE -> value == null;
            };
            if (!good) throw new IllegalArgumentException("malformed closed default constant");
        }
    }

    /** intrinsicOption must be established by the real nominal resolver, not source spelling. */
    public record Formal(String name, boolean intrinsicOption, boolean markedOmittable,
                         Constant defaultConstant) {
        public Formal {
            if (name == null || name.isBlank() || name.equals("self"))
                throw new IllegalArgumentException("invalid value formal name");
            if (defaultConstant != null && defaultConstant.kind() == ConstantKind.NONE && !intrinsicOption)
                throw new IllegalArgumentException("None requires a verified Option formal");
            if (markedOmittable && defaultConstant == null && !intrinsicOption)
                throw new IllegalArgumentException("plain omittable formal requires a default");
        }
        public boolean omittable() { return markedOmittable || defaultConstant != null; }
    }

    /** Caller item in lexical order. Omission is a distinct item, not an expression. */
    public record Item(String label, boolean omission) {
        public Item {
            if (label != null && (label.isBlank() || label.equals("self")))
                throw new IllegalArgumentException("invalid named-argument label");
        }
        public static Item value(String label) { return new Item(label, false); }
        public static Item omit(String label) { return new Item(label, true); }
    }

    public record Bound(int formalIndex, int rawItemIndex, int evaluatedIndex,
                        Origin origin, Fill fill, Constant constant) {
        public Bound {
            if (formalIndex < 0 || rawItemIndex < -1 || evaluatedIndex < -1)
                throw new IllegalArgumentException("invalid provenance index");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(fill, "fill");
            if ((fill == Fill.EXPRESSION) != (evaluatedIndex >= 0))
                throw new IllegalArgumentException("expression origin mismatch");
            if ((origin == Origin.PROVIDED) != (fill == Fill.EXPRESSION))
                throw new IllegalArgumentException("supplied item cannot be dropped");
            if ((origin == Origin.UNSUPPLIED) != (rawItemIndex == -1))
                throw new IllegalArgumentException("missing call-item origin mismatch");
            if (origin == Origin.EXPLICIT_OMISSION && rawItemIndex == -1)
                throw new IllegalArgumentException("explicit omission must have a source item");
            boolean hasDefault = fill == Fill.DEFAULT_VALUE || fill == Fill.DEFAULT_SOME || fill == Fill.DEFAULT_NONE;
            if (hasDefault != (constant != null))
                throw new IllegalArgumentException("default witness mismatch");
            if (constant != null && ((fill == Fill.DEFAULT_NONE)
                    != (constant.kind() == ConstantKind.NONE)))
                throw new IllegalArgumentException("default fill kind mismatch");
        }
    }

    /** ABI order is declaration order; evaluatedRawIndices is lexical source order. */
    public record Plan(List<Bound> abiSlots, List<Integer> evaluatedRawIndices) {
        public Plan {
            abiSlots = List.copyOf(abiSlots);
            evaluatedRawIndices = List.copyOf(evaluatedRawIndices);
            int lastRaw = -1;
            for (int raw : evaluatedRawIndices) {
                if (raw <= lastRaw)
                    throw new IllegalArgumentException("supplied expressions must preserve distinct lexical source indexes");
                lastRaw = raw;
            }
            boolean[] seen = new boolean[evaluatedRawIndices.size()];
            for (int i = 0; i < abiSlots.size(); i++) {
                Bound slot = abiSlots.get(i);
                if (slot.formalIndex() != i)
                    throw new IllegalArgumentException("ABI plan not in formal declaration order");
                if (slot.evaluatedIndex() >= 0) {
                    int ev = slot.evaluatedIndex();
                    if (ev >= seen.length || seen[ev]
                            || evaluatedRawIndices.get(ev) != slot.rawItemIndex())
                        throw new IllegalArgumentException("duplicate or forged evaluation origin");
                    seen[ev] = true;
                }
            }
            for (boolean present : seen) {
                if (!present) throw new IllegalArgumentException("lost supplied expression");
            }
            // A plan also accounts for *every raw call item*, including
            // explicit omission markers that do not have expressions.
            // Without this total-coverage proof a forged plan can silently
            // discard a supplied expression or reuse an omission token.
            int explicitOmissions = 0;
            for (Bound slot : abiSlots) {
                if (slot.origin() == Origin.EXPLICIT_OMISSION) explicitOmissions++;
            }
            int rawCount = evaluatedRawIndices.size() + explicitOmissions;
            if (rawCount > abiSlots.size()) {
                throw new IllegalArgumentException("more raw call items than ABI slots");
            }
            boolean[] rawPresent = new boolean[rawCount];
            for (Bound slot : abiSlots) {
                int raw = slot.rawItemIndex();
                if (raw < 0) continue;
                if (raw >= rawCount || rawPresent[raw]) {
                    throw new IllegalArgumentException("duplicate, missing or out-of-range raw call item");
                }
                rawPresent[raw] = true;
            }
            for (boolean present : rawPresent) {
                if (!present) throw new IllegalArgumentException("missing raw call-item origin");
            }
        }
    }

    public static Plan bind(List<Formal> formals, List<Item> items) {
        formals = List.copyOf(formals);
        items = List.copyOf(items);
        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < formals.size(); i++) {
            if (byName.putIfAbsent(formals.get(i).name(), i) != null)
                throw new IllegalArgumentException("duplicate formal name");
        }
        Bound[] ordered = new Bound[formals.size()];
        List<Integer> evaluationOrder = new ArrayList<>();
        boolean namedSeen = false;
        int nextPositional = 0;
        for (int raw = 0; raw < items.size(); raw++) {
            Item item = items.get(raw);
            int target;
            if (item.label() == null) {
                if (namedSeen) throw new IllegalArgumentException("positional item after named item");
                target = nextPositional++;
            } else {
                namedSeen = true;
                Integer found = byName.get(item.label());
                if (found == null) throw new IllegalArgumentException("unknown named argument");
                target = found;
            }
            if (target >= formals.size()) throw new IllegalArgumentException("too many caller arguments");
            if (ordered[target] != null) throw new IllegalArgumentException("duplicate formal binding");
            if (item.omission()) {
                if (!formals.get(target).omittable())
                    throw new IllegalArgumentException("cannot omit required formal");
                ordered[target] = substitute(target, raw, Origin.EXPLICIT_OMISSION, formals.get(target));
            } else {
                int evaluationIndex = evaluationOrder.size();
                evaluationOrder.add(raw);
                ordered[target] = new Bound(target, raw, evaluationIndex,
                        Origin.PROVIDED, Fill.EXPRESSION, null);
            }
        }
        for (int i = 0; i < ordered.length; i++) {
            if (ordered[i] == null) {
                if (!formals.get(i).omittable())
                    throw new IllegalArgumentException("missing required formal: " + formals.get(i).name());
                ordered[i] = substitute(i, -1, Origin.UNSUPPLIED, formals.get(i));
            }
        }
        return new Plan(Arrays.asList(ordered), evaluationOrder);
    }

    private static Bound substitute(int slot, int raw, Origin origin, Formal formal) {
        Constant value = formal.defaultConstant();
        if (value == null) return new Bound(slot, raw, -1, origin, Fill.IMPLICIT_NONE, null);
        Fill fill = value.kind() == ConstantKind.NONE ? Fill.DEFAULT_NONE
                : formal.intrinsicOption() ? Fill.DEFAULT_SOME : Fill.DEFAULT_VALUE;
        return new Bound(slot, raw, -1, origin, fill, value);
    }

    /** Count ranges must never overlap in the same owner/kind/name family. */
    public static void rejectOverlappingArities(List<Formal> left, List<Formal> right) {
        int leftMin = (int) left.stream().filter(f -> !f.omittable()).count();
        int rightMin = (int) right.stream().filter(f -> !f.omittable()).count();
        int intersectionMin = Math.max(leftMin, rightMin);
        int intersectionMax = Math.min(left.size(), right.size());
        if (intersectionMin <= intersectionMax)
            throw new IllegalArgumentException("ambiguous effective caller arity " + intersectionMin);
    }
}
