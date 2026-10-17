package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable proof and checked-value substrate for built-in refinement intervals.
 *
 * This does NOT expose Oreslang try_from or admit newtypes in compiled programs:
 * TypeChecker still rejects all refined declarations until type/ownership,
 * FFI/serialization, actor and hot-reload boundaries enforce the invariants.
 */
public final class RefinementRules {
    private RefinementRules() { }

    public record Interval(String property, long min, long max, Set<Long> excluded) {
        public Interval {
            excluded = Set.copyOf(excluded);
            if (min > max || min == max && excluded.contains(min))
                throw new IllegalArgumentException("unsatisfiable refinement interval");
            if (max >= min && max - min >= 0 && max - min < excluded.size() && max - min < 100_000) {
                boolean member = false;
                for (long i = min; ; i++) {
                    if (!excluded.contains(i)) { member = true; break; }
                    if (i == max) break; // avoid wraparound at Long.MAX_VALUE
                }
                if (!member) throw new IllegalArgumentException("unsatisfiable refinement exclusions");
            }
        }
        public boolean contains(long value) {
            return value >= min && value <= max && !excluded.contains(value);
        }
        public boolean entails(Interval weaker) {
            if (!property.equals(weaker.property) || min < weaker.min || max > weaker.max)
                return false;
            for (long missing : weaker.excluded) {
                if (missing >= min && missing <= max && !excluded.contains(missing))
                    return false;
            }
            return true;
        }
    }

    /** Interval implication is decidable for this closed built-in comparison fragment. */
    public static Interval normalize(Ast.TypeRef base, List<Ast.RefinementBound> clauses) {
        Objects.requireNonNull(base);
        Objects.requireNonNull(clauses);
        String prop = switch (base.name()) {
            case "string", "String" -> "length";
            case "int" -> "value";
            default -> throw new IllegalArgumentException("unsupported refinement base " + base.name());
        };
        long min = prop.equals("length") ? 0 : Long.MIN_VALUE;
        long max = Long.MAX_VALUE;
        Set<Long> excluded = new HashSet<>();
        for (Ast.RefinementBound b : clauses) {
            if (!prop.equals(b.property()))
                throw new IllegalArgumentException("invalid " + b.property() + " property on " + base.name());
            long n = b.constant();
            switch (b.comparator()) {
                case "eq" -> { min = Math.max(min, n); max = Math.min(max, n); }
                case "ne" -> excluded.add(n);
                case "gte" -> min = Math.max(min, n);
                case "lte" -> max = Math.min(max, n);
                case "gt" -> {
                    if (n == Long.MAX_VALUE)
                        throw new IllegalArgumentException("unsatisfiable gt max");
                    min = Math.max(min, n + 1);
                }
                case "lt" -> {
                    if (n == Long.MIN_VALUE)
                        throw new IllegalArgumentException("unsatisfiable lt min");
                    max = Math.min(max, n - 1);
                }
                default -> throw new IllegalArgumentException("unsupported comparison " + b.comparator());
            }
        }
        return new Interval(prop, min, max, excluded);
    }

    /** A constrained alias may flow into a weaker alias; nominal identities must match. */
    public static boolean entails(String fromIdentity, Ast.TypeRef fromType, Ast.RefinementSpec from,
                                   String toIdentity, Ast.TypeRef toType, Ast.RefinementSpec to) {
        if (!fromType.name().equals(toType.name())) return false;
        if (from == null || to == null) return false;
        if (from.nominal() || to.nominal()) {
            if (!from.nominal() || !to.nominal() || !Objects.equals(fromIdentity, toIdentity))
                return false;
        }
        // General where predicates have no implication proof until purity, a
        // typed verifier and a restricted solver are integrated.
        if (to.whereExpression() != null
                && !Objects.equals(from.whereExpression(), to.whereExpression())) return false;
        return normalize(fromType, from.bounds()).entails(normalize(toType, to.bounds()));
    }

    public static long unicodeScalarCount(String value) {
        Objects.requireNonNull(value);
        long count = 0;
        for (int index = 0; index < value.length();) {
            char ch = value.charAt(index);
            if (Character.isHighSurrogate(ch)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1)))
                    throw new IllegalArgumentException("isolated high surrogate in string");
                index += 2;
            } else if (Character.isLowSurrogate(ch)) {
                throw new IllegalArgumentException("isolated low surrogate in string");
            } else {
                index++;
            }
            count++;
        }
        return count;
    }

    /**
     * Tested validator primitive. No reflective conversion, side effects,
     * or checking of untrusted arbitrary where methods.
     */
    public static boolean accepts(Ast.TypeRef base, Ast.RefinementSpec spec, Object raw) {
        if (spec == null) throw new IllegalArgumentException("missing refinement");
        if (spec.whereExpression() != null) {
            throw new UnsupportedOperationException("where purity and runtime lowering not implemented");
        }
        long value;
        if (base.name().equals("string") || base.name().equals("String")) {
            if (!(raw instanceof String s)) return false;
            try {
                value = unicodeScalarCount(s);
            } catch (IllegalArgumentException invalidUnicodeScalar) {
                // Explicit checked construction must report failure, not throw
                // on untrusted host strings containing unpaired surrogates.
                return false;
            }
        } else if (base.name().equals("int")) {
            if (!(raw instanceof Long l)) return false;
            value = l;
        } else throw new IllegalArgumentException("unsupported checked-conversion base");
        return normalize(base, spec.bounds()).contains(value);
    }

    /** Non-public constructor: no unchecked nominal wrapper manufacture. */
    public static final class NominalValue {
        private final String qualifiedTypeName;
        private final Object underlying;
        private NominalValue(String qualifiedTypeName, Object underlying) {
            if (qualifiedTypeName == null || qualifiedTypeName.isBlank())
                throw new IllegalArgumentException("nominal identity is required");
            this.qualifiedTypeName = qualifiedTypeName;
            this.underlying = Objects.requireNonNull(underlying);
        }
        public String qualifiedTypeName() { return qualifiedTypeName; }
        public Object intoInner() { return underlying; }
    }
    public record CheckedValue(boolean ok, Object value, String error) { }

    /** Host-side reference operation; NOT yet the guest-language Result<T,E> API. */
    public static CheckedValue tryFrom(String identity, Ast.TypeRef base,
                                       Ast.RefinementSpec spec, Object raw) {
        if (!accepts(base, spec, raw))
            return new CheckedValue(false, null, "constraint violation for " + identity);
        return new CheckedValue(true,
                spec.nominal() ? new NominalValue(identity, raw) : raw, null);
    }
}
