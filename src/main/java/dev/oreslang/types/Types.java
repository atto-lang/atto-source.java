package dev.oreslang.types;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class Types {
    private Types() { }

    public sealed interface Type permits Primitive, Named, SelfType, Borrow, ClassNamespace, Record, Function, ListType, Tuple, Union, Generic, StringLiteral, BoolLiteral, Not, Unknown, CheckedScalarType { }

    /**
     * A compiler-internal type marker for a checked scalar in one *specific*
     * schema identity. This is a preview of the type carrier, NOT minting or
     * guest-code support for Valid<T, SchemaId>. The class and constructor are
     * private, and must never become a user-defined Named/Record alias.
     *
     * Once an admitted-schema registry and owned/frozen lowering exist,
     * compiler code may use this representation for checked constructions.
     * Host-side descriptors and fingerprints ALONE are not authorization.
     */
    private static final class CheckedScalarType implements Type {
        private final Primitive scalar;
        private final String qualifiedSchemaName;
        private final String schemaFingerprint;
        // Null denotes legacy unadmitted compiler analysis. A staged schema
        // registry has a fresh per-compilation seal that cannot be recreated
        // from its digest/name/generation metadata.
        private final Object compilationSeal;

        private CheckedScalarType(
                Primitive scalar, String qualifiedSchemaName,
                String schemaFingerprint, Object compilationSeal) {
            this.scalar = Objects.requireNonNull(scalar, "scalar");
            this.qualifiedSchemaName = Objects.requireNonNull(qualifiedSchemaName, "schema identity");
            this.schemaFingerprint = Objects.requireNonNull(schemaFingerprint, "fingerprint");
            this.compilationSeal = compilationSeal;
        }

        @Override public boolean equals(Object other) {
            return other instanceof CheckedScalarType p
                    && scalar == p.scalar
                    && qualifiedSchemaName.equals(p.qualifiedSchemaName)
                    && schemaFingerprint.equals(p.schemaFingerprint)
                    && compilationSeal == p.compilationSeal;
        }

        @Override public int hashCode() {
            return Objects.hash(scalar, qualifiedSchemaName,
                    schemaFingerprint, System.identityHashCode(compilationSeal));
        }

        @Override public String toString() {
            return "compiler-checked<" + scalar + ", " + qualifiedSchemaName + ">";
        }
    }

    /**
     * Only same-package compiler components/tests can request this host
     * reference type, never Oreslang source or imported guest metadata.
     * Does not issue a deployable or transferable proof: schema *approval*
     * is intentionally absent and source-level Valid remains rejected.
     */
    static Type checkedScalarTypeForCompilerAnalysis(
            Primitive scalar, String schemaName,
            RefinementRules.Descriptor descriptor, Object candidate) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (scalar != Primitive.INT && scalar != Primitive.STRING)
            throw new IllegalArgumentException("Zorde proof type requires int64/string scalar");
        if (schemaName == null
                || !schemaName.matches("[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)+"))
            throw new IllegalArgumentException("Zorde type requires qualified schema identity");
        if (!descriptor.base().equals(scalar == Primitive.INT ? "int" : "string"))
            throw new IllegalArgumentException("Zorde schema base does not match scalar type");
        if (!descriptor.nominalIdentity().isEmpty()
                && !descriptor.nominalIdentity().equals(schemaName))
            throw new IllegalArgumentException("Zorde nominal schema identity mismatch");
        if (!descriptor.accepts(candidate))
            throw new IllegalArgumentException("Zorde checked scalar did not satisfy schema");
        return new CheckedScalarType(scalar, schemaName, descriptor.fingerprint(), null);
    }

    /**
     * Compiler-only issuance from an immutable, per-compilation registry.
     * This is type-system *analysis* evidence, NOT a source-level Valid proof.
     * The descriptor must be the same admitted object from that registry,
     * rather than merely matching a caller-controlled fingerprint.
     */
    static Type checkedScalarTypeFromRegistry(
            Primitive scalar, String schemaName,
            RefinementRules.Descriptor descriptor,
            CompilerZordeSchemaRegistry registry) {
        Objects.requireNonNull(registry, "Zorde compiler registry");
        Objects.requireNonNull(descriptor, "Zorde descriptor");
        if (registry.descriptor(schemaName) != descriptor)
            throw new IllegalArgumentException("Zorde descriptor is not registry-admitted");
        if (scalar != Primitive.INT && scalar != Primitive.STRING)
            throw new IllegalArgumentException("invalid Zorde registered scalar type");
        if (!descriptor.base().equals(scalar == Primitive.INT ? "int" : "string"))
            throw new IllegalArgumentException("Zorde registry scalar type mismatch");
        return new CheckedScalarType(scalar, schemaName, descriptor.fingerprint(),
                registry.compilationSeal());
    }

    /** Whether a type (including nested generic/record/function) carries proof. */
    static boolean containsCompilerCheckedScalar(Type type) {
        if (type instanceof CheckedScalarType) return true;
        if (type instanceof Named n)
            return n.arguments().stream().anyMatch(Types::containsCompilerCheckedScalar);
        if (type instanceof Borrow b) return containsCompilerCheckedScalar(b.target());
        if (type instanceof SelfType s) return containsCompilerCheckedScalar(s.bound());
        if (type instanceof ListType l) return containsCompilerCheckedScalar(l.element());
        if (type instanceof Tuple t)
            return t.elements().stream().anyMatch(Types::containsCompilerCheckedScalar);
        if (type instanceof Record r)
            return r.members().values().stream().anyMatch(Types::containsCompilerCheckedScalar);
        if (type instanceof Union u)
            return u.options().stream().anyMatch(Types::containsCompilerCheckedScalar);
        if (type instanceof Function fn)
            return fn.parameters().stream().anyMatch(Types::containsCompilerCheckedScalar)
                    || containsCompilerCheckedScalar(fn.result());
        if (type instanceof Not n) return containsCompilerCheckedScalar(n.excluded());
        return false;
    }

    /**
     * Stale linked metadata must not smuggle a user-defined nominal named
     * Valid through a typechecker that has reserved that source spelling.
     */
    static boolean containsForgedZordeProofName(Type type) {
        if (type instanceof Named n) {
            if (n.name().equals("Valid") || n.name().endsWith(".Valid")) return true;
            return n.arguments().stream().anyMatch(Types::containsForgedZordeProofName);
        }
        if (type instanceof Borrow b) return containsForgedZordeProofName(b.target());
        if (type instanceof SelfType s) return containsForgedZordeProofName(s.bound());
        if (type instanceof ListType l) return containsForgedZordeProofName(l.element());
        if (type instanceof Tuple t)
            return t.elements().stream().anyMatch(Types::containsForgedZordeProofName);
        if (type instanceof Record r)
            return r.members().values().stream().anyMatch(Types::containsForgedZordeProofName);
        if (type instanceof Union u)
            return u.options().stream().anyMatch(Types::containsForgedZordeProofName);
        if (type instanceof Function fn)
            return fn.parameters().stream().anyMatch(Types::containsForgedZordeProofName)
                    || containsForgedZordeProofName(fn.result());
        if (type instanceof Not n) return containsForgedZordeProofName(n.excluded());
        return false;
    }

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
        if (containsCompilerCheckedScalar(type))
            throw new IllegalArgumentException("Zorde proof types cannot be negated or reified as runtime patterns");
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
        if (first == Unknown.INSTANCE || second == Unknown.INSTANCE
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
        if (type == Unknown.INSTANCE || type instanceof Generic) return true;
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
        if (containsForgedZordeProofName(from) || containsForgedZordeProofName(to))
            return false;
        // Proof-bearing objects must never be widened to structural super-
        // records (or generic wrappers) that omit the proof field. Until
        // ownership/type-preserving lowering is complete, only exact nested
        // type identities are assignable, plus explicit union alternatives.
        // This check comes BEFORE the general permissive Unknown/Generic path.
        boolean fromProof = containsCompilerCheckedScalar(from);
        boolean toProof = containsCompilerCheckedScalar(to);
        if (fromProof || toProof) {
            if (from.equals(to)) return true;
            if (from == Unknown.INSTANCE || to == Unknown.INSTANCE
                    || from instanceof Generic || to instanceof Generic)
                return false;
            if (from instanceof Union source) {
                // Each source alternative must pass the same strict proof
                // check; no uncertain source arm may mint proof authority.
                return source.options().stream().allMatch(option -> isAssignable(option, to));
            }
            if (to instanceof Union target) {
                return target.options().stream().anyMatch(option -> isAssignable(from, option));
            }
            return false;
        }

        // Complements must be checked before permissive Unknown/Generic rules.
        // No compiler pass may turn uncertainty into a negative-type proof.
        if (to instanceof Not negative) {
            if (from instanceof Not source) return source.excluded().equals(negative.excluded());
            return areProvablyDisjoint(from, negative.excluded());
        }
        if (from instanceof Not) return to == Unknown.INSTANCE;
        if (from instanceof BoolLiteral && to == Primitive.BOOL) return true;
        if (from == Unknown.INSTANCE || to == Unknown.INSTANCE) return true;
        if (to instanceof Generic || from instanceof Generic) return true;
        if (from.equals(to)) return true;
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
            for (Map.Entry<String, Type> required : target.members().entrySet()) {
                Type actual = source.members().get(required.getKey());
                if (actual == null || !isAssignable(actual, required.getValue())) return false;
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
        // Never silently erase a checked schema identity into Unknown.
        if (types.contains(Unknown.INSTANCE)
                && types.stream().anyMatch(Types::containsCompilerCheckedScalar))
            throw new IllegalArgumentException("union cannot erase a Zorde proof into Unknown");
        for (Type type : types) {
            if (type == Unknown.INSTANCE) return Unknown.INSTANCE;
            if (type instanceof Union union) {
                for (Type option : union.options()) if (!flattened.contains(option)) flattened.add(option);
            } else if (!flattened.contains(type)) flattened.add(type);
        }
        if (flattened.isEmpty()) return Unknown.INSTANCE;
        if (flattened.size() == 1) return flattened.getFirst();
        flattened.sort(java.util.Comparator.comparing(Object::toString));
        return new Union(flattened);
    }

    public static boolean isNumeric(Type type) {
        return type == Primitive.INT || type == Primitive.FLOAT || type == Primitive.DECIMAL || type == Primitive.COMPLEX;
    }
}
