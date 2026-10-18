package dev.oreslang.types;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A source-order-preserving, type-agnostic binding plan for named and optional
 * arguments. This is a pre-integration compiler building block: parser, AST,
 * type/ownership checking, and both runtimes must be integrated before the
 * surface syntax can be enabled.
 *
 * <p>Argument expressions MUST be evaluated exactly once in the original
 * source order before being placed into parameter order according to this plan.
 * A -1 source index means the parameter was omitted and must be lowered to
 * a typed Option.None, not null, undefined or a default argument expression.
 */
public final class OptionalCallShapeBinder {
    private OptionalCallShapeBinder() { }

    /** The call-site label is null for a positional argument. */
    public record Argument(String label, boolean omission) {
        public Argument {
            if (label != null && label.isBlank()) {
                throw new IllegalArgumentException("named argument label cannot be blank");
            }
        }
        public Argument(String label) { this(label, false); }
        public static Argument positional() { return new Argument(null); }
        public static Argument named(String name) {
            return new Argument(Objects.requireNonNull(name, "name"));
        }
        /** An explicit omission is a call item with NO evaluated expression. */
        public static Argument positionalOmission() { return new Argument(null, true); }
        /** The parser maps bare '_' and 'omit' call items to the same omission. */
        public static Argument underscore() { return positionalOmission(); }
        public static Argument namedOmission(String name) {
            return new Argument(Objects.requireNonNull(name, "name"), true);
        }
        /** Named 'label: _' also normalizes to exactly the same omission shape. */
        public static Argument namedUnderscore(String name) { return namedOmission(name); }
        /**
         * Call-argument grammar bridge: only an entire standalone '_' or
         * 'omit' token can denote omission. The lexer/parser must establish
         * argument-item boundaries; this helper NEVER parses an expression.
         * Treating 'None' or 'void' as tokens here would erase an essential
         * type/evaluation distinction.
         */
        public static Argument omittedCallItemToken(String token, String label) {
            Objects.requireNonNull(token, "token");
            if (!"_".equals(token) && !"omit".equals(token)) {
                throw new IllegalArgumentException(
                        "only a standalone '_' or 'omit' may denote an omitted call item");
            }
            return new Argument(label, true);
        }
    }

    public record Parameter(String name, boolean optional, boolean variadic) {
        public Parameter {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("parameter name cannot be blank");
            }
            if (optional && variadic) {
                throw new IllegalArgumentException("variadic parameter cannot be optional");
            }
        }
        public Parameter(String name, boolean optional) { this(name, optional, false); }
        public static Parameter required(String name) { return new Parameter(name, false); }
        public static Parameter optional(String name) { return new Parameter(name, true); }
        /** A single final rest parameter; omission is an empty collection, NEVER None. */
        public static Parameter variadic(String name) { return new Parameter(name, false, true); }
    }

    /** Exact source-to-formal mapping for an explicit no-value call item. */
    public record OmittedCallItem(int callItemIndex, int parameterIndex) { }

    /**
     * Formal-to-source index plan. -1 marks an omitted OPTIONAL scalar formal;
     * the final variadic formal is represented separately by a list of source
     * indexes and is not omitted even when its list is empty.
     */
    public record Plan(List<Integer> sourceIndexByParameter,
                       int variadicParameterIndex,
                       List<Integer> variadicSourceIndices,
                       List<Integer> explicitlyOmittedParameterIndices,
                       List<Integer> callItemIndexByEvaluatedSource) {
        public Plan(List<Integer> sourceIndexByParameter) {
            this(sourceIndexByParameter, -1, List.of(), List.of(),
                    identityItemIndexes(sourceIndexByParameter, List.of()));
        }
        public Plan(List<Integer> sourceIndexByParameter,
                    int variadicParameterIndex,
                    List<Integer> variadicSourceIndices) {
            this(sourceIndexByParameter, variadicParameterIndex, variadicSourceIndices,
                    List.of(), identityItemIndexes(sourceIndexByParameter, variadicSourceIndices));
        }
        public Plan(List<Integer> sourceIndexByParameter,
                    int variadicParameterIndex,
                    List<Integer> variadicSourceIndices,
                    List<Integer> explicitlyOmittedParameterIndices) {
            this(sourceIndexByParameter, variadicParameterIndex, variadicSourceIndices,
                    rejectMissingOrigins(explicitlyOmittedParameterIndices),
                    identityItemIndexes(sourceIndexByParameter, variadicSourceIndices));
        }

        private static List<Integer> rejectMissingOrigins(List<Integer> omissions) {
            Objects.requireNonNull(omissions, "omissions");
            if (!omissions.isEmpty()) {
                throw new IllegalArgumentException(
                        "explicitly omitted call items require original-source indexes");
            }
            return omissions;
        }

        private static List<Integer> identityItemIndexes(List<Integer> fixed, List<Integer> rest) {
            int count = rest.size();
            for (Integer source : fixed) if (source != null && source >= 0) count++;
            List<Integer> identity = new ArrayList<>(count);
            for (int i = 0; i < count; i++) identity.add(i);
            return identity;
        }

        public Plan {
            sourceIndexByParameter = List.copyOf(sourceIndexByParameter);
            variadicSourceIndices = List.copyOf(variadicSourceIndices);
            explicitlyOmittedParameterIndices = List.copyOf(explicitlyOmittedParameterIndices);
            callItemIndexByEvaluatedSource = List.copyOf(callItemIndexByEvaluatedSource);
            boolean[] claimedOmission = new boolean[sourceIndexByParameter.size()];
            for (int parameterIndex : explicitlyOmittedParameterIndices) {
                if (parameterIndex < 0 || parameterIndex >= sourceIndexByParameter.size()
                        || parameterIndex == variadicParameterIndex
                        || claimedOmission[parameterIndex]
                        || sourceIndexByParameter.get(parameterIndex) != -1) {
                    throw new IllegalArgumentException("invalid explicit omission plan slot");
                }
                claimedOmission[parameterIndex] = true;
            }
            if (variadicParameterIndex != -1
                    && variadicParameterIndex != sourceIndexByParameter.size() - 1) {
                throw new IllegalArgumentException("variadic plan slot must be final");
            }
            if (variadicParameterIndex == -1 && !variadicSourceIndices.isEmpty()) {
                throw new IllegalArgumentException("rest sources without variadic parameter");
            }
            if (variadicParameterIndex >= 0
                    && sourceIndexByParameter.get(variadicParameterIndex) != -1) {
                throw new IllegalArgumentException("rest slot cannot have a scalar source");
            }
            int supplied = variadicSourceIndices.size();
            for (int i = 0; i < sourceIndexByParameter.size(); i++) {
                int source = sourceIndexByParameter.get(i);
                if (source < -1) throw new IllegalArgumentException("invalid call-plan source index");
                if (source >= 0) supplied++;
            }
            if (callItemIndexByEvaluatedSource.size() != supplied) {
                throw new IllegalArgumentException(
                        "evaluated-source to original-call-item index map has incorrect size");
            }
            int rawCallItems = Math.addExact(
                    supplied, explicitlyOmittedParameterIndices.size());
            int priorCallItemIndex = -1;
            for (int callItemIndex : callItemIndexByEvaluatedSource) {
                if (callItemIndex <= priorCallItemIndex || callItemIndex >= rawCallItems) {
                    throw new IllegalArgumentException(
                            "evaluated call-item indexes must be strictly increasing");
                }
                priorCallItemIndex = callItemIndex;
            }
            boolean[] seen = new boolean[supplied];
            for (int i = 0; i < sourceIndexByParameter.size(); i++) {
                int source = sourceIndexByParameter.get(i);
                if (source != -1) markSeen(seen, source);
            }
            for (int source : variadicSourceIndices) {
                markSeen(seen, source);
            }
            for (boolean isSeen : seen) {
                if (!isSeen) throw new IllegalArgumentException("noncontiguous call-plan source indexes");
            }
        }

        private static void markSeen(boolean[] seen, int source) {
            if (source < 0 || source >= seen.length || seen[source]) {
                throw new IllegalArgumentException("invalid or duplicate call-plan source index");
            }
            seen[source] = true;
        }

        public int declaredArity() { return sourceIndexByParameter.size(); }
        /**
         * Original call-item index of an evaluated argument. Crucial because
         * '_' and 'omit' consume slots WITHOUT contributing an expression:
         * foo(3, _, effect()) maps evaluated source #1 to call item #2.
         */
        public int callItemIndex(int evaluatedSourceIndex) {
            return callItemIndexByEvaluatedSource.get(evaluatedSourceIndex);
        }
        public int suppliedArgumentCount() {
            int count = variadicSourceIndices.size();
            for (int source : sourceIndexByParameter) if (source >= 0) count++;
            return count;
        }
        /** Count of all syntactic argument items, including explicit omissions. */
        public int callItemCount() {
            return Math.addExact(suppliedArgumentCount(), explicitlyOmittedParameterIndices.size());
        }

        /**
         * Recover the source indexes of syntactic omit items. The original
         * call-item order is preserved even when named bindings are reordered.
         * Implicitly omitted parameters have no call item and are excluded.
         */
        public List<Integer> omittedCallItemIndices() {
            List<Integer> result = new ArrayList<>();
            int evaluated = 0;
            for (int callItem = 0; callItem < callItemCount(); callItem++) {
                if (evaluated < callItemIndexByEvaluatedSource.size()
                        && callItemIndexByEvaluatedSource.get(evaluated) == callItem) {
                    evaluated++;
                } else {
                    result.add(callItem);
                }
            }
            if (result.size() != explicitlyOmittedParameterIndices.size()) {
                throw new IllegalStateException("inconsistent omitted call-item origin plan");
            }
            return List.copyOf(result);
        }

        /**
         * Preserve the source order of explicit omissions while identifying
         * the formal they skip. In named calls, formal indexes can be reordered;
         * never infer a formal from the raw omission item's ordinal.
         */
        public List<OmittedCallItem> omittedCallItemBindings() {
            List<Integer> itemIndexes = omittedCallItemIndices();
            List<OmittedCallItem> mappings = new ArrayList<>(itemIndexes.size());
            for (int i = 0; i < itemIndexes.size(); i++) {
                mappings.add(new OmittedCallItem(
                        itemIndexes.get(i), explicitlyOmittedParameterIndices.get(i)));
            }
            return List.copyOf(mappings);
        }
        public boolean omitted(int parameterIndex) {
            return parameterIndex != variadicParameterIndex
                    && sourceIndexByParameter.get(parameterIndex) == -1;
        }
        public boolean explicitlyOmitted(int parameterIndex) {
            return explicitlyOmittedParameterIndices.contains(parameterIndex);
        }
        public int sourceIndex(int parameterIndex) {
            if (parameterIndex == variadicParameterIndex) {
                throw new IllegalArgumentException("rest parameter has multiple source indexes");
            }
            return sourceIndexByParameter.get(parameterIndex);
        }
    }

    /**
     * Typed lowering decisions for an optional scalar slot. This deliberately
     * does not perform the compiler's nominal/generic subtype check:
     * OPTION_VALUE and NONE_LITERAL must be verified as Option<T> by the
     * type checker. In particular, a void-producing expression is NOT None.
     */
    public enum SuppliedForm {
        PLAIN_VALUE, OPTION_VALUE, NONE_LITERAL, NULL_LITERAL, VOID_EXPRESSION
    }
    public enum OptionalAdaptation { WRAP_SOME, PASS_OPTION, INJECT_NONE }

    public static OptionalAdaptation optionalAdaptation(
            Parameter parameter, boolean omitted, SuppliedForm suppliedForm) {
        Objects.requireNonNull(parameter, "parameter");
        if (!parameter.optional() || parameter.variadic()) {
            throw new IllegalArgumentException("optional adaptation requires optional scalar formal");
        }
        if (omitted) {
            if (suppliedForm != null) {
                throw new IllegalArgumentException("omitted slot cannot have a supplied value");
            }
            return OptionalAdaptation.INJECT_NONE;
        }
        Objects.requireNonNull(suppliedForm, "suppliedForm");
        return switch (suppliedForm) {
            case PLAIN_VALUE -> OptionalAdaptation.WRAP_SOME;
            case OPTION_VALUE, NONE_LITERAL -> OptionalAdaptation.PASS_OPTION;
            case NULL_LITERAL -> throw new IllegalArgumentException(
                    "null is not None; use typed None for an absent optional argument");
            case VOID_EXPRESSION -> throw new IllegalArgumentException(
                    "void is not a value and cannot represent an omitted argument");
        };
    }

    /** The selected declaration index and its permutation; never a runtime overload choice. */
    public record Selection(int signatureIndex, Plan plan) {
        public Selection {
            if (signatureIndex < 0) throw new IllegalArgumentException("negative signature index");
            Objects.requireNonNull(plan, "plan");
        }
    }

    /**
     * Bind without executing or reordering a single expression.
     *
     * <p>Ordinary positional arguments always claim the next leading slot.
     * A middle optional slot can be omitted through a label, not by positional
     * hole shifting. Following the first named argument, positional arguments
     * are forbidden; spreads must be rejected by the frontend before this call.
     * A sole final rest parameter collects remaining positional source indexes
     * in lexical order and uses empty collection (not None) when unsupplied.
     * Passing rest by label is gated until collection and spread checking exist.
     * An explicit positional/named omit is permitted ONLY for an optional
     * fixed slot, consumes exactly one slot, and never evaluates an expression.
     * A separate plan field preserves explicit vs implicit omissions without
     * changing the callee-visible Option<T>.None representation.
     */
    public static Plan bind(List<Parameter> parameters, List<Argument> arguments) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(arguments, "arguments");
        Map<String, Integer> parameterIndex = checkedParameters(parameters);

        int[] binding = new int[parameters.size()];
        Arrays.fill(binding, -1);
        boolean[] claimed = new boolean[parameters.size()];
        List<Integer> explicitOmissions = new ArrayList<>();
        List<Integer> evaluatedCallItems = new ArrayList<>();
        int nextPositional = 0;
        // An explicit omit consumes a CALL SLOT but never evaluates a value.
        // Source indices refer to evaluated expressions, not raw call items.
        int evaluatedSourceIndex = 0;
        int restSlot = parameters.isEmpty() || !parameters.getLast().variadic()
                ? -1 : parameters.size() - 1;
        List<Integer> restSources = new ArrayList<>();
        boolean seenNamed = false;
        for (int itemIndex = 0; itemIndex < arguments.size(); itemIndex++) {
            Argument argument = Objects.requireNonNull(arguments.get(itemIndex), "argument");
            int slot;
            if (argument.label() == null) {
                if (seenNamed) {
                    throw new IllegalArgumentException("positional argument after named argument");
                }
                if (restSlot >= 0 && nextPositional >= restSlot) {
                    if (argument.omission()) {
                        throw new IllegalArgumentException("rest tail cannot contain omit");
                    }
                    restSources.add(evaluatedSourceIndex++);
                    evaluatedCallItems.add(itemIndex);
                    continue;
                }
                if (nextPositional >= parameters.size()) {
                    throw new IllegalArgumentException("too many positional arguments");
                }
                slot = nextPositional++;
            } else {
                seenNamed = true;
                Integer namedSlot = parameterIndex.get(argument.label());
                if (namedSlot == null) {
                    throw new IllegalArgumentException("unknown named argument '" + argument.label() + "'");
                }
                slot = namedSlot;
                if (slot == restSlot) {
                    throw new IllegalArgumentException(
                            "named rest argument requires an explicit collection/spread contract");
                }
            }
            if (claimed[slot]) {
                throw new IllegalArgumentException(
                        "parameter '" + parameters.get(slot).name() + "' was supplied more than once");
            }
            claimed[slot] = true;
            if (argument.omission()) {
                if (!parameters.get(slot).optional()) {
                    throw new IllegalArgumentException(
                            "required parameter '" + parameters.get(slot).name() + "' cannot be omitted");
                }
                explicitOmissions.add(slot);
            } else {
                binding[slot] = evaluatedSourceIndex++;
                evaluatedCallItems.add(itemIndex);
            }
        }

        List<Integer> permutation = new ArrayList<>(binding.length);
        for (int i = 0; i < binding.length; i++) {
            if (i != restSlot && binding[i] == -1 && !parameters.get(i).optional()) {
                throw new IllegalArgumentException(
                        "missing required argument '" + parameters.get(i).name() + "'");
            }
            permutation.add(binding[i]);
        }
        return new Plan(permutation, restSlot, restSources,
                explicitOmissions, evaluatedCallItems);
    }

    private static Map<String, Integer> checkedParameters(List<Parameter> parameters) {
        Map<String, Integer> parameterIndex = new HashMap<>();
        for (int i = 0; i < parameters.size(); i++) {
            Parameter parameter = Objects.requireNonNull(parameters.get(i), "parameter");
            if (parameter.variadic() && i != parameters.size() - 1) {
                throw new IllegalArgumentException("variadic parameter must be final");
            }
            if (parameterIndex.putIfAbsent(parameter.name(), i) != null) {
                throw new IllegalArgumentException("duplicate parameter name '" + parameter.name() + "'");
            }
        }
        return parameterIndex;
    }

    /**
     * Conservative v1 admission check for an existing callable overload domain.
     * An optional declaration accepts a supplied-argument COUNT interval
     * [number of required slots, total slots], or infinity for a rest tail.
     * Any interval intersecting
     * another declaration's interval is rejected at declaration/link time,
     * even if their label sets appear disjoint. This preserves deterministic
     * arity-only dispatch until cross-backend call-shape proofs exist.
     *
     * <p>Nonoptional/nonoptional signature collision rules (including the
     * separately staged nominal enum exception) remain with the caller.
     */
    public static void requireNonOverlappingOptionalArityIntervals(
            List<List<Parameter>> signatures) {
        Objects.requireNonNull(signatures, "signatures");
        for (List<Parameter> signature : signatures) {
            checkedParameters(Objects.requireNonNull(signature, "signature"));
        }
        for (int i = 0; i < signatures.size(); i++) {
            List<Parameter> a = signatures.get(i);
            long aRequired = a.stream().filter(p -> !p.optional() && !p.variadic()).count();
            long aMaximum = a.isEmpty() || !a.getLast().variadic() ? a.size() : Long.MAX_VALUE;
            for (int j = i + 1; j < signatures.size(); j++) {
                List<Parameter> b = signatures.get(j);
                long bRequired = b.stream().filter(p -> !p.optional() && !p.variadic()).count();
                long bMaximum = b.isEmpty() || !b.getLast().variadic() ? b.size() : Long.MAX_VALUE;
                if (aRequired == a.size() && bRequired == b.size()) {
                    continue; // ordinary exact-arity policy remains separate
                }
                if (Math.max(aRequired, bRequired) <= Math.min(aMaximum, bMaximum)) {
                    throw new IllegalArgumentException(
                            "overlapping optional supplied-arity intervals for declarations "
                                    + i + " and " + j);
                }
            }
        }
    }

    /**
     * Resolve a family structurally, by labels and required slots ONLY.
     * Full declared arity is retained for the ordinary callable ABI selector.
     * If omitted optionals make two declarations match, reject ambiguity;
     * neither argument types nor an "exact arity wins" preference may hide it.
     *
     * <p>Signature ownership, visibility, overrides and constructor-receiver
     * erasure must already be validated by the calling compiler phase.
     */
    public static Selection bindUnique(
            List<List<Parameter>> signatures,
            List<Argument> arguments) {
        Objects.requireNonNull(signatures, "signatures");
        Objects.requireNonNull(arguments, "arguments");
        requireNonOverlappingOptionalArityIntervals(signatures);
        // The binder must never accidentally use labels to dispatch two
        // ordinary signatures with the same arity. Exceptional nominal-enum
        // overloads need a separately proved, already-selected declaration,
        // not this type-agnostic family resolver.
        for (int i = 0; i < signatures.size(); i++) {
            for (int j = i + 1; j < signatures.size(); j++) {
                List<Parameter> a = signatures.get(i);
                List<Parameter> b = signatures.get(j);
                if (a.size() == b.size()
                        && a.stream().allMatch(p -> !p.optional() && !p.variadic())
                        && b.stream().allMatch(p -> !p.optional() && !p.variadic())) {
                    throw new IllegalArgumentException(
                            "same-arity overloads cannot dispatch by argument labels");
                }
            }
        }
        Selection selected = null;
        List<Integer> valid = new ArrayList<>();
        for (int i = 0; i < signatures.size(); i++) {
            // Malformed declarations are errors, not silently skipped candidates.
            checkedParameters(Objects.requireNonNull(signatures.get(i), "signature"));
            try {
                Plan plan = bind(signatures.get(i), arguments);
                selected = new Selection(i, plan);
                valid.add(i);
            } catch (IllegalArgumentException ignored) {
                // The whole family is checked below. A nonmatching candidate
                // must not win solely because its parameter types differ.
            }
        }
        if (valid.size() > 1) {
            throw new IllegalArgumentException(
                    "ambiguous optional/named call shape matches declarations " + valid);
        }
        if (selected == null) {
            if (signatures.size() == 1) {
                // Preserve useful exact diagnostic for single-signature calls.
                bind(signatures.getFirst(), arguments);
            }
            throw new IllegalArgumentException(
                    "no declared callable has a compatible required/named argument shape");
        }
        return selected;
    }
}
