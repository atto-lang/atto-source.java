package dev.oreslang.types;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class Types {
    private Types() { }

    public sealed interface Type permits Primitive, Named, SelfType, Borrow, ClassNamespace, Record, Function, ListType, Tuple, Union, Generic, StringLiteral, BoolLiteral, Not, Unknown { }

    public enum Primitive implements Type {
        INT, FLOAT, DECIMAL, COMPLEX, BOOL, STRING, VOID, NULL
    }

    public record Named(String name, List<Type> arguments) implements Type {
        public Named { arguments = List.copyOf(arguments); }
    }

    /**
     * Receiver-polymorphic type carried by the implicit instance receiver.
     *
     * <p>The bound is the nominal type whose members are available while checking
     * the method body. A SelfType value may widen to that nominal type, but a
     * fresh value of the nominal type cannot narrow back to SelfType because the
     * actual receiver may be a more-derived subtype. Values already proven to
     * have the receiver-polymorphic self type may flow through aliases/parameters.
     */
    public record SelfType(Type bound) implements Type {
        public SelfType { Objects.requireNonNull(bound, "self bound"); }
    }

    /** Rust-style compile-time borrow; erased by the interpreter runtime. */
    public record Borrow(Type target, boolean mutable) implements Type { }

    /** Compile-time meta-value for access to static class functions. */
    public record ClassNamespace(String className) implements Type { }

    /**
     * Closed structural record type. readOnly is a value capability, not a
     * structural compatibility dimension: it prevents mutation from being
     * regained through aliases while preserving ordinary shape matching.
     */
    public record Record(Map<String, Type> members, boolean readOnly) implements Type {
        public Record { members = Map.copyOf(members); }
        public Record(Map<String, Type> members) { this(members, false); }
    }

    public record Function(
            List<Type> parameters,
            List<Boolean> mutableParameters,
            boolean async,
            Type result) implements Type {
        public Function {
            parameters = List.copyOf(parameters);
            mutableParameters = List.copyOf(mutableParameters);
            if (parameters.size() != mutableParameters.size()) {
                throw new IllegalArgumentException(
                        "function parameter mutability metadata must match parameter arity");
            }
        }

        /**
         * Source-level Fnc<...> types and built-in callables have no mutable
         * parameters and are synchronous unless a declaration explicitly says
         * otherwise.
         */
        public Function(List<Type> parameters, Type result) {
            this(parameters, java.util.Collections.nCopies(parameters.size(), false), false, result);
        }
    }

    public record ListType(Type element) implements Type { }

    public enum TupleKind {
        TUPLE,
        FIXED_ARRAY,
        FIXED_LIST
    }

    public record Tuple(List<Type> elements, TupleKind kind) implements Type {
        public Tuple {
            elements = List.copyOf(elements);
            kind = Objects.requireNonNull(kind, "kind");
        }

        public Tuple(List<Type> elements) {
            this(elements, TupleKind.TUPLE);
        }
    }

    public record Union(List<Type> options) implements Type {
        public Union {
            options = List.copyOf(options);
            if (options.size() < 2) throw new IllegalArgumentException("union needs at least two distinct types");
        }
    }

    public record Generic(String name) implements Type { }

    public record StringLiteral(String value) implements Type { }

    /** Compile-time singleton boolean; unlike Primitive.BOOL it is not the entire boolean domain. */
    public record BoolLiteral(boolean value) implements Type { }

    /** The complement of excluded in the universe of Oreslang values. */
    public record Not(Type excluded) implements Type {
        public Not { Objects.requireNonNull(excluded, "excluded type"); }
    }

    public enum Unknown implements Type { INSTANCE }

    public static Type notOf(Type type) {
        Objects.requireNonNull(type, "type");
        return type instanceof Not not ? not.excluded() : new Not(type);
    }

    /**
     * A conservative disjointness proof, never a runtime guess.
     *
     * Unknown and generics must not pass a negative type merely because
     * ordinary dynamic compatibility treats them permissively. Structural
     * absence is not proof: a narrowed/widened record view may hide fields.
     */
    private static final int MAX_RELATION_PROOF_STEPS = 100_000;

    /** Identity-only pair key: structural record equality is itself recursive. */
    private static final class TypePair {
        private final Type left;
        private final Type right;

        private TypePair(Type left, Type right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof TypePair pair
                    && left == pair.left && right == pair.right;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(left) + System.identityHashCode(right);
        }
    }

    private static final class RelationProof {
        private int remaining = MAX_RELATION_PROOF_STEPS;
        private boolean exhausted;
        private final Map<TypePair, Boolean> equal = new HashMap<>();
        private final Map<TypePair, Boolean> assignable = new HashMap<>();
        private final Map<TypePair, Boolean> disjoint = new HashMap<>();
        private final IdentityHashMap<Type, Boolean> uncertain = new IdentityHashMap<>();

        private boolean step() {
            if (--remaining < 0) {
                exhausted = true;
                return false;
            }
            return true;
        }
    }

    public static boolean areProvablyDisjoint(Type first, Type second) {
        Objects.requireNonNull(first);
        Objects.requireNonNull(second);
        if (!isStructurallyWellFormed(first) || !isStructurallyWellFormed(second)) {
            return false;
        }
        return areProvablyDisjointChecked(first, second, new RelationProof());
    }

    private static boolean areProvablyDisjointChecked(
            Type first, Type second, RelationProof proof) {
        TypePair key = new TypePair(first, second);
        Boolean cached = proof.disjoint.get(key);
        if (cached != null) return cached;
        if (!proof.step()) return false;

        boolean result;
        if (first == Unknown.INSTANCE || second == Unknown.INSTANCE
                || first instanceof Generic || second instanceof Generic) {
            result = false;
        } else if (sameTypeChecked(first, second, proof)) {
            result = false;
        } else if (proof.exhausted) {
            result = false;
        } else if (first instanceof Union union) {
            result = true;
            for (Type option : union.options()) {
                if (!areProvablyDisjointChecked(option, second, proof)) {
                    result = false;
                    break;
                }
            }
        } else if (second instanceof Union union) {
            result = true;
            for (Type option : union.options()) {
                if (!areProvablyDisjointChecked(first, option, proof)) {
                    result = false;
                    break;
                }
            }
        } else if (first instanceof Not not) {
            result = isAssignableChecked(second, not.excluded(), proof)
                    && !containsUncertainTypeChecked(second, proof);
        } else if (second instanceof Not not) {
            result = isAssignableChecked(first, not.excluded(), proof)
                    && !containsUncertainTypeChecked(first, proof);
        } else if (first instanceof BoolLiteral a && second instanceof BoolLiteral b) {
            result = a.value() != b.value();
        } else if (first instanceof BoolLiteral) {
            result = second != Primitive.BOOL
                    && second != Primitive.VOID && second != Primitive.NULL
                    && !(second instanceof Not);
        } else if (second instanceof BoolLiteral) {
            result = areProvablyDisjointChecked(second, first, proof);
        } else if (first instanceof StringLiteral a && second instanceof StringLiteral b) {
            result = !Objects.equals(a.value(), b.value());
        } else if (first instanceof StringLiteral) {
            result = second != Primitive.STRING;
        } else if (second instanceof StringLiteral) {
            result = areProvablyDisjointChecked(second, first, proof);
        } else if (first instanceof Primitive a && second instanceof Primitive b) {
            // Numeric widening makes primitive numeric domains potentially overlap.
            result = a != b && !(isNumeric(a) && isNumeric(b));
        } else if (first instanceof Record a && second instanceof Record b) {
            result = false;
            for (var entry : a.members().entrySet()) {
                Type candidate = b.members().get(entry.getKey());
                if (candidate != null
                        && areProvablyDisjointChecked(entry.getValue(), candidate, proof)) {
                    result = true;
                    break;
                }
            }
        } else {
            result = (first instanceof Record && second instanceof Primitive)
                    || (second instanceof Record && first instanceof Primitive)
                    || (first instanceof Named && second instanceof Primitive)
                    || (second instanceof Named && first instanceof Primitive)
                    || (first instanceof ListType && second instanceof Primitive)
                    || (second instanceof ListType && first instanceof Primitive)
                    || (first instanceof Tuple && second instanceof Primitive)
                    || (second instanceof Tuple && first instanceof Primitive);
        }
        if (proof.exhausted) result = false;
        proof.disjoint.put(key, result);
        return result;
    }

    private static boolean containsUncertainTypeChecked(Type type, RelationProof proof) {
        Boolean cached = proof.uncertain.get(type);
        if (cached != null) return cached;
        if (!proof.step()) return true;

        boolean result;
        if (type == Unknown.INSTANCE || type instanceof Generic) {
            result = true;
        } else if (type instanceof Union union) {
            result = false;
            for (Type option : union.options()) {
                if (containsUncertainTypeChecked(option, proof)) {
                    result = true;
                    break;
                }
            }
        } else if (type instanceof Not not) {
            result = containsUncertainTypeChecked(not.excluded(), proof);
        } else if (type instanceof Named named) {
            result = anyUncertain(named.arguments(), proof);
        } else if (type instanceof Record record) {
            result = anyUncertain(record.members().values(), proof);
        } else if (type instanceof ListType list) {
            result = containsUncertainTypeChecked(list.element(), proof);
        } else if (type instanceof Tuple tuple) {
            result = anyUncertain(tuple.elements(), proof);
        } else if (type instanceof Function function) {
            result = anyUncertain(function.parameters(), proof)
                    || containsUncertainTypeChecked(function.result(), proof);
        } else if (type instanceof Borrow borrow) {
            result = containsUncertainTypeChecked(borrow.target(), proof);
        } else if (type instanceof SelfType self) {
            result = containsUncertainTypeChecked(self.bound(), proof);
        } else {
            result = false;
        }
        if (proof.exhausted) result = true;
        proof.uncertain.put(type, result);
        return result;
    }

    private static boolean anyUncertain(
            Iterable<? extends Type> types, RelationProof proof) {
        for (Type type : types) {
            if (containsUncertainTypeChecked(type, proof)) return true;
        }
        return false;
    }

    public static boolean isAssignable(Type from, Type to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        // Validate the entire structural type once before any permissive
        // Unknown/generic/equality/negative-type shortcut can authorize it.
        // The shared budget also turns pathologically deep or wide malformed
        // compiler API input into a controlled fail-closed result.
        if (!isStructurallyWellFormed(from) || !isStructurallyWellFormed(to)) {
            return false;
        }
        return isAssignableChecked(from, to, new RelationProof());
    }

    private static boolean isAssignableChecked(
            Type from, Type to, RelationProof proof) {
        TypePair key = new TypePair(from, to);
        Boolean cached = proof.assignable.get(key);
        if (cached != null) return cached;
        if (!proof.step()) return false;

        boolean result;
        // Complements must be checked before permissive Unknown/Generic rules.
        // No compiler pass may turn uncertainty into a negative-type proof.
        if (to instanceof Not negative) {
            result = from instanceof Not source
                    ? sameTypeChecked(source.excluded(), negative.excluded(), proof)
                    : areProvablyDisjointChecked(from, negative.excluded(), proof);
        } else if (from instanceof Not) {
            result = to == Unknown.INSTANCE;
        } else if (from instanceof BoolLiteral && to == Primitive.BOOL) {
            result = true;
        } else if (from == Unknown.INSTANCE || to == Unknown.INSTANCE) {
            result = true;
        } else if (to instanceof Generic || from instanceof Generic) {
            result = true;
        } else if (sameTypeChecked(from, to, proof)) {
            result = true;
        } else if (proof.exhausted) {
            result = false;
        } else if (from instanceof SelfType source) {
            if (to instanceof SelfType target) {
                result = isAssignableChecked(source.bound(), target.bound(), proof)
                        && isAssignableChecked(target.bound(), source.bound(), proof);
            } else {
                result = isAssignableChecked(source.bound(), to, proof);
            }
        } else if (to instanceof SelfType) {
            // A nominal base value cannot manufacture the receiver-polymorphic proof.
            result = false;
        } else if (from instanceof StringLiteral && to == Primitive.STRING) {
            result = true;
        } else if (from instanceof Union source) {
            result = true;
            for (Type option : source.options()) {
                if (!isAssignableChecked(option, to, proof)) {
                    result = false;
                    break;
                }
            }
        } else if (to instanceof Union target) {
            result = false;
            for (Type option : target.options()) {
                if (isAssignableChecked(from, option, proof)) {
                    result = true;
                    break;
                }
            }
        } else if (from instanceof Borrow source && to instanceof Borrow target) {
            result = !target.mutable() || source.mutable();
            if (result) {
                result = isAssignableChecked(source.target(), target.target(), proof)
                        && isAssignableChecked(target.target(), source.target(), proof);
            }
        } else if (from instanceof Named source && to instanceof Named target
                && Objects.equals(source.name(), target.name())) {
            result = source.arguments().size() == target.arguments().size();
            for (int i = 0; result && i < source.arguments().size(); i++) {
                Type a = source.arguments().get(i);
                Type b = target.arguments().get(i);
                result = isAssignableChecked(a, b, proof)
                        && isAssignableChecked(b, a, proof);
            }
        } else if (from instanceof Record source && to instanceof Record target) {
            result = true;
            for (Map.Entry<String, Type> required : target.members().entrySet()) {
                Type actual = source.members().get(required.getKey());
                if (actual == null
                        || !isAssignableChecked(actual, required.getValue(), proof)) {
                    result = false;
                    break;
                }
            }
        } else if (from instanceof ListType source && to instanceof ListType target) {
            result = isAssignableChecked(source.element(), target.element(), proof)
                    && isAssignableChecked(target.element(), source.element(), proof);
        } else if (from instanceof Tuple source && to instanceof Tuple target) {
            result = source.kind() == target.kind()
                    && source.elements().size() == target.elements().size();
            for (int i = 0; result && i < source.elements().size(); i++) {
                result = isAssignableChecked(
                        source.elements().get(i), target.elements().get(i), proof);
            }
        } else if (from instanceof Function source && to instanceof Function target) {
            result = source.parameters().size() == target.parameters().size()
                    && source.async() == target.async()
                    && source.mutableParameters().equals(target.mutableParameters());
            for (int i = 0; result && i < source.parameters().size(); i++) {
                result = isAssignableChecked(
                        target.parameters().get(i), source.parameters().get(i), proof);
            }
            if (result) {
                result = isAssignableChecked(source.result(), target.result(), proof);
            }
        } else {
            result = numericWidening(from, to);
        }
        if (proof.exhausted) result = false;
        proof.assignable.put(key, result);
        return result;
    }

    private static boolean sameTypeChecked(Type left, Type right, RelationProof proof) {
        if (left == right) return true;
        TypePair key = new TypePair(left, right);
        Boolean cached = proof.equal.get(key);
        if (cached != null) return cached;
        if (!proof.step()) return false;

        boolean result = false;
        if (left != null && right != null && left.getClass() == right.getClass()) {
            if (left instanceof Primitive) {
                result = false; // enum identity was handled above.
            } else if (left instanceof Named a && right instanceof Named b) {
                result = Objects.equals(a.name(), b.name())
                        && sameTypeList(a.arguments(), b.arguments(), proof);
            } else if (left instanceof SelfType a && right instanceof SelfType b) {
                result = sameTypeChecked(a.bound(), b.bound(), proof);
            } else if (left instanceof Borrow a && right instanceof Borrow b) {
                result = a.mutable() == b.mutable()
                        && sameTypeChecked(a.target(), b.target(), proof);
            } else if (left instanceof ClassNamespace a && right instanceof ClassNamespace b) {
                result = Objects.equals(a.className(), b.className());
            } else if (left instanceof Record a && right instanceof Record b) {
                result = a.readOnly() == b.readOnly()
                        && a.members().size() == b.members().size();
                for (Map.Entry<String, Type> entry : a.members().entrySet()) {
                    Type candidate = b.members().get(entry.getKey());
                    if (candidate == null
                            || !sameTypeChecked(entry.getValue(), candidate, proof)) {
                        result = false;
                        break;
                    }
                }
            } else if (left instanceof Function a && right instanceof Function b) {
                result = a.async() == b.async()
                        && a.mutableParameters().equals(b.mutableParameters())
                        && sameTypeList(a.parameters(), b.parameters(), proof)
                        && sameTypeChecked(a.result(), b.result(), proof);
            } else if (left instanceof ListType a && right instanceof ListType b) {
                result = sameTypeChecked(a.element(), b.element(), proof);
            } else if (left instanceof Tuple a && right instanceof Tuple b) {
                result = a.kind() == b.kind()
                        && sameTypeList(a.elements(), b.elements(), proof);
            } else if (left instanceof Union a && right instanceof Union b) {
                result = sameTypeList(a.options(), b.options(), proof);
            } else if (left instanceof Generic a && right instanceof Generic b) {
                result = Objects.equals(a.name(), b.name());
            } else if (left instanceof StringLiteral a && right instanceof StringLiteral b) {
                result = Objects.equals(a.value(), b.value());
            } else if (left instanceof BoolLiteral a && right instanceof BoolLiteral b) {
                result = a.value() == b.value();
            } else if (left instanceof Not a && right instanceof Not b) {
                result = sameTypeChecked(a.excluded(), b.excluded(), proof);
            } else if (left == Unknown.INSTANCE && right == Unknown.INSTANCE) {
                result = true;
            }
        }
        if (proof.exhausted) result = false;
        proof.equal.put(key, result);
        return result;
    }

    private static boolean sameTypeList(
            List<? extends Type> left, List<? extends Type> right, RelationProof proof) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) {
            if (!sameTypeChecked(left.get(i), right.get(i), proof)) return false;
        }
        return true;
    }

    /**
     * Validate direct compiler/embedding Type objects before they participate
     * in compatibility, negative-type, Send/Sync, or actor-boundary proofs.
     *
     * <p>Parser-produced types already obey these invariants, but compiler
     * transformations and embedding callers can instantiate the public record
     * classes directly. This check is intentionally bounded and fail-closed.
     */
    public static boolean isStructurallyWellFormed(Type type) {
        return !failsStructuralTypeValidation(
                type, 0, new int[] {100_000},
                Collections.newSetFromMap(new IdentityHashMap<>()),
                new IdentityHashMap<>());
    }

    private static boolean failsStructuralTypeValidation(
            Type type, int depth, int[] budget, Set<Type> visiting,
            IdentityHashMap<Type, Map<Integer, Boolean>> memoizedFailures) {
        if (type == null || depth > 256 || --budget[0] < 0) return true;
        // Direct compiler/embedding objects are expected to be immutable, but
        // fail closed if a malformed cyclic graph is supplied reflectively.
        if (!visiting.add(type)) return true;
        try {
            // A node that is valid at a shallow depth is not automatically
            // valid when reused deeper in the graph; include depth in the
            // identity cache so MAX_TYPE_DEPTH cannot be bypassed.
            Map<Integer, Boolean> byDepth = memoizedFailures.get(type);
            if (byDepth != null && byDepth.containsKey(depth)) {
                return byDepth.get(depth);
            }

            boolean failed = false;
            if (type instanceof Named named) {
                if (named.name() == null || named.name().isBlank()) {
                    failed = true;
                } else {
                    for (Type argument : named.arguments()) {
                        if (failsStructuralTypeValidation(
                                argument, depth + 1, budget, visiting, memoizedFailures)) {
                            failed = true;
                            break;
                        }
                    }
                }
            } else if (type instanceof SelfType self) {
                failed = failsStructuralTypeValidation(
                        self.bound(), depth + 1, budget, visiting, memoizedFailures);
            } else if (type instanceof Borrow borrow) {
                failed = failsStructuralTypeValidation(
                        borrow.target(), depth + 1, budget, visiting, memoizedFailures);
            } else if (type instanceof ClassNamespace namespace) {
                failed = namespace.className() == null || namespace.className().isBlank();
            } else if (type instanceof Record record) {
                for (Map.Entry<String, Type> member : record.members().entrySet()) {
                    if (member.getKey() == null || member.getKey().isBlank()
                            || failsStructuralTypeValidation(
                                    member.getValue(), depth + 1, budget,
                                    visiting, memoizedFailures)) {
                        failed = true;
                        break;
                    }
                }
            } else if (type instanceof Function function) {
                for (Type parameter : function.parameters()) {
                    if (failsStructuralTypeValidation(
                            parameter, depth + 1, budget, visiting, memoizedFailures)) {
                        failed = true;
                        break;
                    }
                }
                if (!failed) {
                    failed = failsStructuralTypeValidation(
                            function.result(), depth + 1, budget,
                            visiting, memoizedFailures);
                }
            } else if (type instanceof ListType list) {
                failed = failsStructuralTypeValidation(
                        list.element(), depth + 1, budget, visiting, memoizedFailures);
            } else if (type instanceof Tuple tuple) {
                for (Type element : tuple.elements()) {
                    if (failsStructuralTypeValidation(
                            element, depth + 1, budget, visiting, memoizedFailures)) {
                        failed = true;
                        break;
                    }
                }
            } else if (type instanceof Union union) {
                for (Type option : union.options()) {
                    if (failsStructuralTypeValidation(
                            option, depth + 1, budget, visiting, memoizedFailures)) {
                        failed = true;
                        break;
                    }
                }
            } else if (type instanceof Generic generic) {
                failed = generic.name() == null || generic.name().isBlank();
            } else if (type instanceof StringLiteral literal) {
                failed = literal.value() == null;
            } else if (type instanceof Not not) {
                failed = failsStructuralTypeValidation(
                        not.excluded(), depth + 1, budget, visiting, memoizedFailures);
            }

            memoizedFailures.computeIfAbsent(type, ignored -> new HashMap<>())
                    .put(depth, failed);
            return failed;
        } finally {
            visiting.remove(type);
        }
    }

    private static boolean numericWidening(Type from, Type to) {
        if (from == Primitive.INT && (to == Primitive.FLOAT || to == Primitive.DECIMAL || to == Primitive.COMPLEX)) return true;
        if ((from == Primitive.FLOAT || from == Primitive.DECIMAL) && to == Primitive.COMPLEX) return true;
        return false;
    }

    public static Type numericJoin(Type left, Type right) {
        if (!isNumeric(left) || !isNumeric(right)) return Unknown.INSTANCE;
        if (left == Primitive.COMPLEX || right == Primitive.COMPLEX) return Primitive.COMPLEX;
        if (left == Primitive.DECIMAL || right == Primitive.DECIMAL) return Primitive.DECIMAL;
        if (left == Primitive.FLOAT || right == Primitive.FLOAT) return Primitive.FLOAT;
        return Primitive.INT;
    }

    public static Type unionOf(Type left, Type right) {
        return unionOf(List.of(left, right));
    }

    public static Type unionOf(List<Type> types) {
        Objects.requireNonNull(types, "types");
        java.util.ArrayList<Type> candidates = new java.util.ArrayList<>();
        for (Type type : types) {
            if (type == null || type == Unknown.INSTANCE) return Unknown.INSTANCE;
            if (type instanceof Union union) candidates.addAll(union.options());
            else candidates.add(type);
        }
        if (candidates.isEmpty()) return Unknown.INSTANCE;

        // Do not call List.contains()/record equals on compiler type graphs:
        // Java record equality recursively re-expands shared DAGs. Validate
        // first, then deduplicate through the bounded identity-pair proof.
        for (Type candidate : candidates) {
            if (!isStructurallyWellFormed(candidate)) return Unknown.INSTANCE;
        }
        RelationProof equalityProof = new RelationProof();
        java.util.ArrayList<Type> flattened = new java.util.ArrayList<>();
        outer:
        for (Type candidate : candidates) {
            for (Type existing : flattened) {
                if (sameTypeChecked(existing, candidate, equalityProof)) continue outer;
                if (equalityProof.exhausted) return Unknown.INSTANCE;
            }
            flattened.add(candidate);
        }
        if (flattened.size() == 1) return flattened.getFirst();

        // Object.toString() on records has the same DAG-expansion problem.
        // Use an exact deterministic structural order with a bounded shared
        // comparison cache. An exhausted proof yields Unknown rather than
        // spending unbounded compiler CPU.
        CanonicalOrder order = new CanonicalOrder();
        try {
            flattened.sort((left, right) -> compareCanonical(left, right, order));
        } catch (CanonicalOrderBudgetExceeded exhausted) {
            return Unknown.INSTANCE;
        }
        return new Union(flattened);
    }

    private static final class CanonicalOrder {
        private int remaining = MAX_RELATION_PROOF_STEPS;
        private final Map<TypePair, Integer> memo = new HashMap<>();

        private void step() {
            if (--remaining < 0) throw CanonicalOrderBudgetExceeded.INSTANCE;
        }
    }

    private static final class CanonicalOrderBudgetExceeded extends RuntimeException {
        private static final CanonicalOrderBudgetExceeded INSTANCE =
                new CanonicalOrderBudgetExceeded();

        private CanonicalOrderBudgetExceeded() {
            super(null, null, false, false);
        }
    }

    private static int compareCanonical(Type left, Type right, CanonicalOrder order) {
        if (left == right) return 0;
        TypePair key = new TypePair(left, right);
        Integer cached = order.memo.get(key);
        if (cached != null) return cached;
        order.step();

        int result = Integer.compare(typeRank(left), typeRank(right));
        if (result == 0) {
            if (left instanceof Primitive a && right instanceof Primitive b) {
                result = Integer.compare(a.ordinal(), b.ordinal());
            } else if (left instanceof Named a && right instanceof Named b) {
                result = a.name().compareTo(b.name());
                if (result == 0) result = compareCanonicalList(
                        a.arguments(), b.arguments(), order);
            } else if (left instanceof SelfType a && right instanceof SelfType b) {
                result = compareCanonical(a.bound(), b.bound(), order);
            } else if (left instanceof Borrow a && right instanceof Borrow b) {
                result = Boolean.compare(a.mutable(), b.mutable());
                if (result == 0) result = compareCanonical(a.target(), b.target(), order);
            } else if (left instanceof ClassNamespace a && right instanceof ClassNamespace b) {
                result = a.className().compareTo(b.className());
            } else if (left instanceof Record a && right instanceof Record b) {
                result = compareCanonicalRecord(a, b, order);
                if (result == 0) result = Boolean.compare(a.readOnly(), b.readOnly());
            } else if (left instanceof Function a && right instanceof Function b) {
                result = compareCanonicalList(a.parameters(), b.parameters(), order);
                if (result == 0) result = compareBooleanList(
                        a.mutableParameters(), b.mutableParameters());
                if (result == 0) result = Boolean.compare(a.async(), b.async());
                if (result == 0) result = compareCanonical(a.result(), b.result(), order);
            } else if (left instanceof ListType a && right instanceof ListType b) {
                result = compareCanonical(a.element(), b.element(), order);
            } else if (left instanceof Tuple a && right instanceof Tuple b) {
                result = compareCanonicalList(a.elements(), b.elements(), order);
                if (result == 0) result = Integer.compare(
                        a.kind().ordinal(), b.kind().ordinal());
            } else if (left instanceof Union a && right instanceof Union b) {
                result = compareCanonicalList(a.options(), b.options(), order);
            } else if (left instanceof Generic a && right instanceof Generic b) {
                result = a.name().compareTo(b.name());
            } else if (left instanceof StringLiteral a && right instanceof StringLiteral b) {
                result = a.value().compareTo(b.value());
            } else if (left instanceof BoolLiteral a && right instanceof BoolLiteral b) {
                result = Boolean.compare(a.value(), b.value());
            } else if (left instanceof Not a && right instanceof Not b) {
                result = compareCanonical(a.excluded(), b.excluded(), order);
            }
        }
        result = Integer.signum(result);
        order.memo.put(key, result);
        order.memo.put(new TypePair(right, left), -result);
        return result;
    }

    private static int typeRank(Type type) {
        if (type instanceof Primitive) return 0;
        if (type instanceof BoolLiteral) return 1;
        if (type instanceof StringLiteral) return 2;
        if (type instanceof Named) return 3;
        if (type instanceof Generic) return 4;
        if (type instanceof ClassNamespace) return 5;
        if (type instanceof Borrow) return 6;
        if (type instanceof ListType) return 7;
        if (type instanceof Tuple) return 8;
        if (type instanceof Record) return 9;
        if (type instanceof Function) return 10;
        if (type instanceof SelfType) return 11;
        if (type instanceof Not) return 12;
        if (type instanceof Union) return 13;
        return 14; // Unknown is returned before canonicalization.
    }

    private static int compareCanonicalList(
            List<? extends Type> left, List<? extends Type> right, CanonicalOrder order) {
        int size = Integer.compare(left.size(), right.size());
        if (size != 0) return size;
        for (int i = 0; i < left.size(); i++) {
            int compared = compareCanonical(left.get(i), right.get(i), order);
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static int compareBooleanList(List<Boolean> left, List<Boolean> right) {
        int size = Integer.compare(left.size(), right.size());
        if (size != 0) return size;
        for (int i = 0; i < left.size(); i++) {
            int compared = Boolean.compare(left.get(i), right.get(i));
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static int compareCanonicalRecord(
            Record left, Record right, CanonicalOrder order) {
        java.util.ArrayList<String> leftKeys =
                new java.util.ArrayList<>(left.members().keySet());
        java.util.ArrayList<String> rightKeys =
                new java.util.ArrayList<>(right.members().keySet());
        leftKeys.sort(String::compareTo);
        rightKeys.sort(String::compareTo);
        int size = Integer.compare(leftKeys.size(), rightKeys.size());
        if (size != 0) return size;
        for (int i = 0; i < leftKeys.size(); i++) {
            String leftKey = leftKeys.get(i);
            String rightKey = rightKeys.get(i);
            int compared = leftKey.compareTo(rightKey);
            if (compared != 0) return compared;
            compared = compareCanonical(
                    left.members().get(leftKey), right.members().get(rightKey), order);
            if (compared != 0) return compared;
        }
        return 0;
    }

    public static boolean isNumeric(Type type) {
        return type == Primitive.INT || type == Primitive.FLOAT || type == Primitive.DECIMAL || type == Primitive.COMPLEX;
    }
}
