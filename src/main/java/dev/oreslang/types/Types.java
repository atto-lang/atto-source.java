package dev.oreslang.types;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Types {
    private Types() { }

    public sealed interface Type permits Primitive, Named, SelfType, Borrow, ClassNamespace, Record, Function, ListType, Tuple, Union, Generic, StringLiteral, Any, UnknownValue, Unknown { }

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

    /**
     * A statically known Oreslang value whose concrete type is hidden from the
     * current scope. Any is NOT a dynamically unchecked operation permission.
     * Values must be checked/narrowed before type-dependent access.
     */
    public enum Any implements Type { INSTANCE }

    /**
     * Source-level Unknown: a value without a verified Oreslang type contract.
     * No implicit conversion into Any or a concrete type is permitted.
     */
    public enum UnknownValue implements Type { INSTANCE }

    /**
     * INTERNAL only: incomplete type information in inference and legacy
     * builtins. Must never be exposed as the source-level Unknown type.
     * TODO: replace permissive legacy sentinels with explicit inference holes.
     */
    public enum Unknown implements Type { INSTANCE }

    /**
     * Until the runtime carries full type/ownership witnesses, hiding borrowed,
     * callable, receiver-polymorphic or unresolved values behind a top type
     * would allow the compiler to lose enforcement evidence.
     */
    private static boolean safelyHideConcreteType(Type type) {
        if (type == Primitive.VOID || type == Primitive.NULL
                || type == Unknown.INSTANCE || type == UnknownValue.INSTANCE
                || type instanceof Generic || type instanceof Borrow
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
        if (type == Any.INSTANCE || type == UnknownValue.INSTANCE) return true;
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

    public static boolean isAssignable(Type from, Type to) {
        Objects.requireNonNull(from);
        Objects.requireNonNull(to);
        if (from.equals(to)) return true;
        // Unknown is a safe, opaque destination; only an explicit runtime
        // validation can establish the stronger Any/concrete invariant.
        if (to == UnknownValue.INSTANCE) return safelyHideConcreteType(from);
        if (from == UnknownValue.INSTANCE) return false;
        if (to == Any.INSTANCE) return safelyHideConcreteType(from);
        if (from == Any.INSTANCE) return false;
        // Legacy incomplete inference types are a separate compiler concern;
        // they must not be confused with the named source type Unknown.
        if (from == Unknown.INSTANCE || to == Unknown.INSTANCE) return true;
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
            // A readonly *view* may safely widen readable members, but an
            // explicitly writable destination may not offer broader writes.
            // In particular, {foo:int} must not become writable {foo:Any}.
            if (source.readOnly() && !target.readOnly()) return false;
            for (Map.Entry<String, Type> required : target.members().entrySet()) {
                Type actual = source.members().get(required.getKey());
                if (actual == null || !isAssignable(actual, required.getValue())) return false;
                if (!target.readOnly() && !isCompilerMethodContract(required.getKey())
                        && !isAssignable(required.getValue(), actual)) return false;
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

    /**
     * Only compiler-emitted instance method slots carry the method
     * contravariance/covariance contract. Source record field identifiers are
     * not synthesized into these slots.
     */
    private static boolean isCompilerMethodContract(String key) {
        return key.matches("instance\\$[^\\s]+\\$arity[0-9]+\\$generics[0-9]+");
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

    public static boolean isNumeric(Type type) {
        return type == Primitive.INT || type == Primitive.FLOAT || type == Primitive.DECIMAL || type == Primitive.COMPLEX;
    }
}
