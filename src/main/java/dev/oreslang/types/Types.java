package dev.oreslang.types;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

public final class Types {
    private Types() { }
    private static final AtomicLong NEXT_HOLE_ID = new AtomicLong(1);

    public sealed interface Type permits Primitive, Named, SelfType, Borrow, ClassNamespace, Record, Function, ListType, Tuple, Union, Generic, StringLiteral, BoolLiteral, Not, Any, UnknownValue, Unknown, InferenceHole { }

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
     * Closed structural record type. readOnly is a value capability that
     * restricts both writes and assignability. Read-only target views support
     * width/covariance; writable target members require invariant types so
     * references cannot regain mutation authority through aliases.
     */
    public record Record(Map<String, Type> members, boolean readOnly,
                         java.util.Set<String> methodKeys) implements Type {
        public Record {
            members = Map.copyOf(members);
            methodKeys = java.util.Set.copyOf(methodKeys);
            for (String key : methodKeys) {
                if (!(members.get(key) instanceof Function)) {
                    throw new IllegalArgumentException(
                            "compiler method slot '" + key + "' must contain a callable contract");
                }
            }
        }
        public Record(Map<String, Type> members, boolean readOnly) {
            this(members, readOnly, java.util.Set.of());
        }
        public Record(Map<String, Type> members) { this(members, false); }
        /** Preserve compiler-proven method identity during type transformations. */
        public Record withMembers(Map<String, Type> updated, boolean readonly) {
            java.util.Set<String> surviving = new java.util.HashSet<>(methodKeys);
            surviving.retainAll(updated.keySet());
            return new Record(updated, readonly, surviving);
        }
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
    /**
     * A statically known Oreslang value whose concrete type is hidden from the
     * current scope. Any is NOT a dynamically unchecked operation permission.
     * Values must be checked/narrowed before type-dependent access.
     */
    public enum Any implements Type {
        INSTANCE;
        @Override public String toString() { return "Any"; }
    }

    /**
     * Source-level Unknown: a value without a verified Oreslang type contract.
     * No implicit conversion into Any or a concrete type is permitted.
     */
    public enum UnknownValue implements Type {
        INSTANCE;
        @Override public String toString() { return "Unknown"; }
    }

    /**
     * INTERNAL only: incomplete type information in inference and legacy
     * builtins. Must never be exposed as the source-level Unknown type.
     * TODO: replace permissive legacy sentinels with explicit inference holes.
     */
    public enum Unknown implements Type {
        INSTANCE;
        @Override public String toString() { return "<incomplete compiler inference>"; }
    }

    /**
     * An identifiable unresolved compiler constraint, not an assignable value.
     * The EMPTY_ARRAY origin can be satisfied by an explicitly known expected
     * element type because the literal has no elements to contradict it.
     * Other origins will be migrated separately from legacy Unknown.INSTANCE.
     */
    public enum HoleOrigin { EMPTY_ARRAY, OPTION_NONE, RESULT_OK_ERROR, RESULT_ERR_OK }

    public record InferenceHole(long id, HoleOrigin origin) implements Type {
        public InferenceHole {
            if (id <= 0) throw new IllegalArgumentException("inference hole id must be positive");
            Objects.requireNonNull(origin, "inference hole origin");
        }
        @Override public String toString() {
            return "<unresolved " + origin + " #" + id + ">";
        }
    }

    public static InferenceHole newInferenceHole(HoleOrigin origin) {
        return new InferenceHole(NEXT_HOLE_ID.getAndIncrement(), origin);
    }

    /**
     * Until the runtime carries full type/ownership witnesses, hiding borrowed,
     * callable, receiver-polymorphic or unresolved values behind a top type
     * would allow the compiler to lose enforcement evidence.
     */
    private static boolean safelyHideConcreteType(Type type) {
        if (type == Primitive.VOID || type == Primitive.NULL
                || type == Unknown.INSTANCE || type == UnknownValue.INSTANCE
                || type instanceof Generic || type instanceof InferenceHole || type instanceof Not
                || type instanceof Borrow
                || type instanceof SelfType || type instanceof Function
                || type instanceof ClassNamespace) return false;
        if (type instanceof ListType list) return safelyHideConcreteType(list.element());
        if (type instanceof Tuple tuple) return tuple.elements().stream().allMatch(Types::safelyHideConcreteType);
        if (type instanceof Union union) return union.options().stream().allMatch(Types::safelyHideConcreteType);
        if (type instanceof Record record) return record.members().values().stream().allMatch(Types::safelyHideConcreteType);
        if (type instanceof Named named) {
            // These types carry execution-domain, external-resource, mutable
            // synchronization or actor authority. A plain type-erased object
            // wrapper cannot currently preserve their affine/capability proof.
            // Only introduce universal boxing for them alongside reified
            // ownership witnesses and a transport-aware runtime.
            String leafName = named.name();
            int qualifier = leafName.lastIndexOf('.');
            if (qualifier >= 0) leafName = leafName.substring(qualifier + 1);
            if (switch (leafName) {
                case "ActorRef", "ActorMail", "ActorOutput", "ActorOutputStream",
                     "Channel", "SelectCase", "SelectSet", "SelectResult",
                     "Mutex", "MutexGuard", "SharedMutex", "Future",
                     "Generator", "AsyncGenerator", "Iterator", "AsyncIterator",
                     "TcpConnection", "HttpExchange", "HttpServer", "HttpHandler",
                     "File", "Socket" -> true;
                default -> false;
            }) return false;
            return named.arguments().stream().allMatch(Types::safelyHideConcreteType);
        }
        return true;
    }

    public static boolean containsOpaqueTop(Type type) {
        if (type == Any.INSTANCE || type == UnknownValue.INSTANCE
                || type instanceof InferenceHole) return true;
        if (type instanceof Not not) return containsOpaqueTop(not.excluded());
        if (type instanceof SelfType self) return containsOpaqueTop(self.bound());
        if (type instanceof Borrow borrow) return containsOpaqueTop(borrow.target());
        if (type instanceof ListType list) return containsOpaqueTop(list.element());
        if (type instanceof Tuple tuple) return tuple.elements().stream().anyMatch(Types::containsOpaqueTop);
        if (type instanceof Union union) return union.options().stream().anyMatch(Types::containsOpaqueTop);
        if (type instanceof Record record) return record.members().values().stream().anyMatch(Types::containsOpaqueTop);
        if (type instanceof Named named) return named.arguments().stream().anyMatch(Types::containsOpaqueTop);
        if (type instanceof Function fn) {
            return fn.parameters().stream().anyMatch(Types::containsOpaqueTop)
                    || containsOpaqueTop(fn.result());
        }
        return false;
    }

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
    public static boolean areProvablyDisjoint(Type first, Type second) {
        if (first == Any.INSTANCE || second == Any.INSTANCE
                || first == UnknownValue.INSTANCE || second == UnknownValue.INSTANCE
                || first instanceof InferenceHole || second instanceof InferenceHole
                || first == Unknown.INSTANCE || second == Unknown.INSTANCE
                || first instanceof Generic || second instanceof Generic) return false;
        if (first.equals(second)) return false;
        if (first instanceof Union union) {
            return union.options().stream().allMatch(option -> areProvablyDisjoint(option, second));
        }
        if (second instanceof Union union) {
            return union.options().stream().allMatch(option -> areProvablyDisjoint(first, option));
        }
        if (first instanceof Not not) {
            return isAssignable(second, not.excluded()) && !containsUncertainType(second);
        }
        if (second instanceof Not not) {
            return isAssignable(first, not.excluded()) && !containsUncertainType(first);
        }
        if (first instanceof BoolLiteral a && second instanceof BoolLiteral b) return a.value() != b.value();
        if (first instanceof BoolLiteral) return second != Primitive.BOOL
                && second != Primitive.VOID && second != Primitive.NULL
                && !(second instanceof Not);
        if (second instanceof BoolLiteral) return areProvablyDisjoint(second, first);
        if (first instanceof StringLiteral && second instanceof StringLiteral) return !first.equals(second);
        if (first instanceof StringLiteral) return second != Primitive.STRING;
        if (second instanceof StringLiteral) return areProvablyDisjoint(second, first);
        if (first instanceof Primitive a && second instanceof Primitive b) {
            // Numeric widening makes primitive numeric domains potentially overlap.
            return a != b && !(isNumeric(a) && isNumeric(b));
        }
        if (first instanceof Record a && second instanceof Record b) {
            // Only conflicting *known* fields prove disjointness. Missing keys do not.
            for (var entry : a.members().entrySet()) {
                Type candidate = b.members().get(entry.getKey());
                if (candidate != null && areProvablyDisjoint(entry.getValue(), candidate)) return true;
            }
            return false;
        }
        if (first instanceof Record && second instanceof Primitive
                || second instanceof Record && first instanceof Primitive) return true;
        if (first instanceof Named && second instanceof Primitive
                || second instanceof Named && first instanceof Primitive) return true;
        if (first instanceof ListType && second instanceof Primitive
                || second instanceof ListType && first instanceof Primitive) return true;
        if (first instanceof Tuple && second instanceof Primitive
                || second instanceof Tuple && first instanceof Primitive) return true;
        return false; // Not knowing if types overlap is NOT proof of exclusion.
    }

    private static boolean containsUncertainType(Type type) {
        if (type == Unknown.INSTANCE || type == Any.INSTANCE || type == UnknownValue.INSTANCE
                || type instanceof InferenceHole || type instanceof Generic) return true;
        if (type instanceof Union union) return union.options().stream().anyMatch(Types::containsUncertainType);
        if (type instanceof Not not) return containsUncertainType(not.excluded());
        if (type instanceof Named named) return named.arguments().stream().anyMatch(Types::containsUncertainType);
        if (type instanceof Record record) return record.members().values().stream().anyMatch(Types::containsUncertainType);
        if (type instanceof ListType list) return containsUncertainType(list.element());
        if (type instanceof Tuple tuple) return tuple.elements().stream().anyMatch(Types::containsUncertainType);
        if (type instanceof Function function) return function.parameters().stream().anyMatch(Types::containsUncertainType)
                || containsUncertainType(function.result());
        if (type instanceof Borrow borrow) return containsUncertainType(borrow.target());
        if (type instanceof SelfType self) return containsUncertainType(self.bound());
        return false;
    }

    public static boolean isAssignable(Type from, Type to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        // Complements must be checked before permissive Unknown/Generic rules.
        // No compiler pass may turn uncertainty into a negative-type proof.
        if (to instanceof Not negative) {
            if (from instanceof Not source) return source.excluded().equals(negative.excluded());
            return areProvablyDisjoint(from, negative.excluded());
        }
        if (from instanceof Not) return to == Unknown.INSTANCE;
        if (from instanceof BoolLiteral && to == Primitive.BOOL) return true;
        if (from.equals(to)) return true;
        // Inference identities never act as untyped values or wildcard type
        // witnesses. Contextual resolution is performed by the checker, not
        // through this general assignability relation.
        if (from instanceof InferenceHole || to instanceof InferenceHole) return false;
        // Unknown is a safe, opaque destination; only an explicit runtime
        // validation can establish the stronger Any/concrete invariant.
        if (to == UnknownValue.INSTANCE) return safelyHideConcreteType(from);
        if (from == UnknownValue.INSTANCE) return false;
        if (to == Any.INSTANCE) return safelyHideConcreteType(from);
        if (from == Any.INSTANCE) return false;
        // Legacy unresolved inference is now expected-only. A value whose
        // type is incomplete cannot prove an arbitrary concrete contract.
        if (from == Unknown.INSTANCE) return false;
        if (to == Unknown.INSTANCE) return true;
        // Unbound generic parameters are rigid, not an implicit any.
        if (to instanceof Generic || from instanceof Generic) return false;
        if (from instanceof SelfType source) {
            if (to instanceof SelfType target) {
                return isAssignable(source.bound(), target.bound())
                        && isAssignable(target.bound(), source.bound());
            }
            return isAssignable(source.bound(), to);
        }
        // A nominal base value cannot manufacture the receiver-polymorphic proof.
        if (to instanceof SelfType) return false;
        if (from instanceof StringLiteral && to == Primitive.STRING) return true;

        if (from instanceof Union source) {
            return source.options().stream().allMatch(option -> isAssignable(option, to));
        }
        if (to instanceof Union target) {
            return target.options().stream().anyMatch(option -> isAssignable(from, option));
        }

        if (from instanceof Borrow source && to instanceof Borrow target) {
            if (target.mutable() && !source.mutable()) return false;
            return isAssignable(source.target(), target.target()) && isAssignable(target.target(), source.target());
        }

        if (from instanceof Named source && to instanceof Named target && source.name().equals(target.name())) {
            if (source.arguments().size() != target.arguments().size()) return false;
            for (int i = 0; i < source.arguments().size(); i++) {
                Type a = source.arguments().get(i), b = target.arguments().get(i);
                if (!isAssignable(a, b) || !isAssignable(b, a)) return false;
            }
            return true;
        }

        if (from instanceof Record source && to instanceof Record target) {
            // A readonly view cannot recover write permission via a structural alias.
            if (source.readOnly() && !target.readOnly()) return false;
            for (Map.Entry<String, Type> required : target.members().entrySet()) {
                Type actual = source.members().get(required.getKey());
                if (actual == null) return false;
                // A callable-valued data field is not an instance method.
                if (source.methodKeys().contains(required.getKey())
                        != target.methodKeys().contains(required.getKey())) return false;
                if (!isAssignable(actual, required.getValue())) return false;
                // Writable data slots require equal resolved types. Bidirectional
                // assignability is not enough: Unknown/Generic can match both
                // directions and would allow writes through an unsafe alias.
                // Compiler-owned callable selector slots describe methods, which
                // are immutable contracts and retain function variance.
                boolean methodSlot = source.methodKeys().contains(required.getKey())
                        && target.methodKeys().contains(required.getKey())
                        && actual instanceof Function && required.getValue() instanceof Function;
                if (!target.readOnly() && !methodSlot && !actual.equals(required.getValue())) {
                    return false;
                }
            }
            return true;
        }

        if (from instanceof ListType source && to instanceof ListType target) {
            return isAssignable(source.element(), target.element()) && isAssignable(target.element(), source.element());
        }

        if (from instanceof Tuple source && to instanceof Tuple target) {
            if (source.kind() != target.kind()) return false;
            if (source.elements().size() != target.elements().size()) return false;
            for (int i = 0; i < source.elements().size(); i++) {
                if (!isAssignable(source.elements().get(i), target.elements().get(i))) return false;
            }
            return true;
        }

        if (from instanceof Function source && to instanceof Function target) {
            if (source.parameters().size() != target.parameters().size()) return false;
            if (source.async() != target.async()) return false;
            // Parameter mutation authority is part of the callable contract.
            // Keep it invariant: callers and implementations must agree exactly
            // on which arguments may be mutated.
            if (!source.mutableParameters().equals(target.mutableParameters())) return false;
            for (int i = 0; i < source.parameters().size(); i++) {
                if (!isAssignable(target.parameters().get(i), source.parameters().get(i))) return false;
            }
            return isAssignable(source.result(), target.result());
        }

        return numericWidening(from, to);
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
        java.util.ArrayList<Type> flattened = new java.util.ArrayList<>();
        for (Type type : types) {
            if (type == Unknown.INSTANCE) return Unknown.INSTANCE;
            if (type instanceof Union union) {
                for (Type option : union.options()) if (!flattened.contains(option)) flattened.add(option);
            } else if (!flattened.contains(type)) flattened.add(type);
        }
        if (flattened.isEmpty()) return Unknown.INSTANCE;
        // Normalize only when every arm can safely widen to the top type.
        // In this first-stage runtime, borrows/functions/unchecked capabilities
        // deliberately are not implicit members of Any or source Unknown.
        if (flattened.contains(UnknownValue.INSTANCE)
                && flattened.stream().allMatch(type -> isAssignable(type, UnknownValue.INSTANCE))) {
            return UnknownValue.INSTANCE;
        }
        if (flattened.contains(Any.INSTANCE)
                && flattened.stream().allMatch(type -> isAssignable(type, Any.INSTANCE))) {
            return Any.INSTANCE;
        }
        if (flattened.size() == 1) return flattened.getFirst();
        flattened.sort(java.util.Comparator.comparing(Object::toString));
        return new Union(flattened);
    }

    /**
     * A deliberately bounded first phase of intersection semantics.
     *
     * No nominal/class/trait implementation is synthesized here. Prove a
     * representable intersection or fail closed, including for Generic/Unknown.
     * In particular, coercive numeric assignability is NOT set intersection:
     * int & float must not accidentally become int.
     */
    public static Type intersectOf(List<Type> types) {
        if (types.isEmpty()) throw new IllegalArgumentException("intersection type requires at least one operand");
        Type result = types.getFirst();
        for (int i = 1; i < types.size(); i++) result = intersectOf(result, types.get(i));
        return result;
    }

    public static Type intersectOf(Type left, Type right) {
        Objects.requireNonNull(left);
        Objects.requireNonNull(right);
        if (left.equals(right)) return left;
        if (left == Unknown.INSTANCE || right == Unknown.INSTANCE
                || left instanceof Generic || right instanceof Generic) {
            throw new IllegalArgumentException(
                    "intersection involving an unresolved generic or unknown type requires a proven runtime contract");
        }
        if (left instanceof Union union) return intersectUnion(union, right);
        if (right instanceof Union union) return intersectUnion(union, left);
        if (left instanceof StringLiteral && right == Primitive.STRING) return left;
        if (right instanceof StringLiteral && left == Primitive.STRING) return right;
        if (left instanceof Record a && right instanceof Record b) {
            if (a.readOnly() != b.readOnly()) {
                throw new IllegalArgumentException(
                        "intersection of readonly and mutable records requires per-member access capabilities");
            }
            java.util.TreeMap<String, Type> merged = new java.util.TreeMap<>(a.members());
            for (Map.Entry<String, Type> field : b.members().entrySet()) {
                Type existing = merged.get(field.getKey());
                if (existing != null && a.methodKeys().contains(field.getKey())
                        != b.methodKeys().contains(field.getKey())) {
                    throw new IllegalArgumentException(
                            "intersection field/method collision at '" + field.getKey() + "'");
                }
                if (existing == null) {
                    merged.put(field.getKey(), field.getValue());
                    continue;
                }
                if (!a.readOnly()) {
                    if (!existing.equals(field.getValue())) {
                        throw new IllegalArgumentException(
                                "intersection conflicting writable field '" + field.getKey()
                                        + "': " + existing + " vs " + field.getValue());
                    }
                } else {
                    try {
                        merged.put(field.getKey(), intersectOf(existing, field.getValue()));
                    } catch (IllegalArgumentException failure) {
                        throw new IllegalArgumentException(
                                "intersection conflicting readonly field '" + field.getKey()
                                        + "': " + failure.getMessage(), failure);
                    }
                }
            }
            java.util.Set<String> methodKeys = new java.util.HashSet<>(a.methodKeys());
            methodKeys.addAll(b.methodKeys());
            return new Record(merged, a.readOnly(), methodKeys);
        }
        throw new IllegalArgumentException(
                "intersection has no proven common value or supported representation: "
                        + left + " & " + right);
    }

    private static Type intersectUnion(Union union, Type other) {
        java.util.ArrayList<Type> common = new java.util.ArrayList<>();
        for (Type option : union.options()) {
            // Only identical alternatives are provably safe in this phase.
            if (option.equals(other)) common.add(option);
            if (other instanceof Union candidate && candidate.options().contains(option)
                    && !common.contains(option)) common.add(option);
        }
        if (common.isEmpty()) {
            throw new IllegalArgumentException("intersection has no common union alternative: "
                    + union + " & " + other);
        }
        return unionOf(common);
    }

    public static boolean isNumeric(Type type) {
        return type == Primitive.INT || type == Primitive.FLOAT || type == Primitive.DECIMAL || type == Primitive.COMPLEX;
    }
}
