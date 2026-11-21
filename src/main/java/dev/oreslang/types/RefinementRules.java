package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.TreeSet;
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
    // Bounded checked-schema admission. Individual host Interval records
    // remain useful for large formal proofs, but untrusted source clauses
    // must never cause unbounded compiler work or serialized ABI payloads.
    private static final int MAX_SOURCE_REFINEMENT_CLAUSES = 4096;

    private RefinementRules() { }

    public record Interval(String property, long min, long max, Set<Long> excluded) {
        public Interval {
            excluded = Set.copyOf(excluded);
            // Public host proof records must not manufacture a metric outside
            // the closed scalar refinement kernel or negative Unicode length.
            if (!"value".equals(property) && !"length".equals(property))
                throw new IllegalArgumentException("unsupported refinement metric");
            if (min > max || (property.equals("length") && min < 0))
                throw new IllegalArgumentException("unsatisfiable refinement interval");
            for (long hole : excluded) {
                if (hole < min || hole > max)
                    throw new IllegalArgumentException("refinement exclusion outside interval");
            }
            // A set of unique in-range holes covers this *entire* inclusive
            // interval exactly when max - min == holes.size() - 1.
            // This modulo-long subtraction is safe here: every representable
            // set size is <= Integer.MAX_VALUE, while intervals wider than
            // Long.MAX_VALUE wrap to a NEGATIVE difference and cannot match.
            // No O(width) enumeration or arbitrary 100k cutoff is needed.
            if (!excluded.isEmpty() && max - min == (long) excluded.size() - 1L)
                throw new IllegalArgumentException("unsatisfiable refinement exclusions");
        }
        public boolean contains(long value) {
            return withinBounds(value, min, max, excluded);
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

    /**
     * The sole scalar comparison kernel. Both static interval proofs and
     * checked runtime values call this method; no second evaluator or
     * implicit numeric coercion is permitted.
     */
    private static boolean withinBounds(long value, long min, long max,
                                        java.util.Collection<Long> exclusions) {
        return value >= min && value <= max && !exclusions.contains(value);
    }

    private static Long scalarMetric(String canonicalBase, Object raw) {
        if (canonicalBase.equals("int")) {
            // Oreslang int64 values never enter via float/Integer/BigInteger
            // host coercions, even when the value could be represented exactly.
            return raw instanceof Long value ? value : null;
        }
        if (!canonicalBase.equals("string")) {
            throw new IllegalArgumentException("unsupported scalar schema base " + canonicalBase);
        }
        if (!(raw instanceof String value)) return null;
        try {
            return unicodeScalarCount(value);
        } catch (IllegalArgumentException invalidUnicode) {
            return null;
        }
    }

    private static String canonicalBaseName(Ast.TypeRef base) {
        Objects.requireNonNull(base);
        // Refinement schemas are a closed scalar vocabulary. Never erase
        // generic arguments or inferred-argument state merely because the
        // outer spelling happens to be "int" or "string": that would let a
        // malformed/parameterized type acquire a trusted scalar descriptor.
        if (!base.arguments().isEmpty() || base.inferArguments()) {
            throw new IllegalArgumentException(
                    "refinement base must be a concrete unparameterized scalar");
        }
        return switch (base.name()) {
            case "string", "String" -> "string";
            case "int" -> "int";
            default -> throw new IllegalArgumentException("unsupported refinement base " + base.name());
        };
    }

    private static boolean qualifiedIdentity(String identity) {
        // Descriptor hashing and nominal wrapper creation may be reached by
        // untrusted schema input. Reject oversized identities before regex,
        // digest construction or unbounded module-path storage.
        if (identity == null || identity.length() > 256) return false;
        int components = 1;
        for (int i = 0; i < identity.length(); i++)
            if (identity.charAt(i) == '.' && ++components > 16) return false;
        return identity.matches(
                "[A-Za-z_][A-Za-z_0-9]*(\\.[A-Za-z_][A-Za-z_0-9]*)+");
    }

    /** Interval implication is decidable for this closed built-in comparison fragment. */
    public static Interval normalize(Ast.TypeRef base, List<Ast.RefinementBound> clauses) {
        Objects.requireNonNull(clauses);
        int suppliedCount = clauses.size();
        if (suppliedCount > MAX_SOURCE_REFINEMENT_CLAUSES) {
            throw new IllegalArgumentException(
                    "source refinement clause limit exceeded (4096)");
        }
        // A hostile host list may mutate between admission and iteration.
        // Freeze the bounded source schema before interpreting any clause.
        List<Ast.RefinementBound> sourceClauses = List.copyOf(clauses);
        if (sourceClauses.size() != suppliedCount)
            throw new IllegalArgumentException("refinement clauses changed during snapshot");
        String canonicalBase = canonicalBaseName(base);
        String prop = canonicalBase.equals("string") ? "length" : "value";
        long min = prop.equals("length") ? 0 : Long.MIN_VALUE;
        long max = Long.MAX_VALUE;
        Set<Long> excluded = new HashSet<>();
        for (Ast.RefinementBound b : sourceClauses) {
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
        // Exclusions outside the admissible interval have no semantic effect.
        // Remove them so logically equivalent constraints share one schema
        // identity and do not trigger an unimplemented Zorde exclusion path.
        long lower = min;
        long upper = max;
        excluded.removeIf(n -> n < lower || n > upper);
        return new Interval(prop, min, max, excluded);
    }

    /**
     * Canonical, reflection-free scalar descriptor shared by proof and runtime
     * validation. Versioned encoding is intentionally independent of source
     * annotation spelling and source-order. This is a host/compiler contract,
     * not yet the guest-language zorde.schema<T>() implementation.
     */
    public record Descriptor(int version, String base, String property,
                             long min, long max, List<Long> excluded,
                             String nominalIdentity, String fingerprint) {
        public Descriptor {
            Objects.requireNonNull(excluded, "excluded");
            int suppliedCount = excluded.size();
            // Reject before allocating a second, potentially unbounded list.
            if (suppliedCount > MAX_SOURCE_REFINEMENT_CLAUSES) {
                throw new IllegalArgumentException(
                        "refinement descriptor exclusion quota exceeded (4096)");
            }
            excluded = List.copyOf(excluded);
            if (excluded.size() != suppliedCount)
                throw new IllegalArgumentException(
                        "refinement exclusions changed during snapshot");
            if (version != 1 || base == null || property == null
                    || (!base.equals("int") && !base.equals("string"))
                    || !(base.equals("int") ? property.equals("value")
                                          : property.equals("length"))
                    || min > max || (base.equals("string") && min < 0)
                    || nominalIdentity == null
                    || (!nominalIdentity.isEmpty() && !qualifiedIdentity(nominalIdentity))) {
                throw new IllegalArgumentException("invalid refinement descriptor shape");
            }
            List<Long> canonicalHoles = List.copyOf(new TreeSet<>(excluded));
            if (!excluded.equals(canonicalHoles)) {
                throw new IllegalArgumentException("noncanonical refinement descriptor exclusions");
            }
            for (long excludedValue : excluded) {
                if (excludedValue < min || excludedValue > max) {
                    throw new IllegalArgumentException("refinement descriptor exclusion outside interval");
                }
            }
            new Interval(property, min, max, new HashSet<>(excluded));
            // A descriptor is a reference-data integrity check, not proof
            // that the compiler has admitted or constructed this type.
            if (!fingerprintFor(base, property, min, max, excluded, nominalIdentity)
                    .equals(fingerprint)) {
                throw new IllegalArgumentException("refinement descriptor fingerprint mismatch");
            }
        }
        public boolean accepts(Object raw) {
            Long metric = scalarMetric(base, raw);
            return metric != null && withinBounds(metric, min, max, excluded);
        }

        /**
         * The descriptor—not source annotation order—is authoritative for
         * the inferred output type. Equivalent schemas produce IDENTICAL
         * normalized Ast.RefinementSpec values as well as matching runtime
         * acceptance sets and ABI fingerprints.
         *
         * An unbounded structural schema retains the original primitive
         * type (null refinement). An unbounded nominal schema remains a
         * distinct identity-bearing newtype.
         */
        public Ast.RefinementSpec inferredRefinement() {
            String metricName = base.equals("int") ? "value" : "length";
            long baseMinimum = base.equals("int") ? Long.MIN_VALUE : 0L;
            List<Ast.RefinementBound> canonical = new java.util.ArrayList<>();
            if (min == max) {
                canonical.add(new Ast.RefinementBound(metricName, "eq", min));
            } else {
                if (min != baseMinimum)
                    canonical.add(new Ast.RefinementBound(metricName, "gte", min));
                if (max != Long.MAX_VALUE)
                    canonical.add(new Ast.RefinementBound(metricName, "lte", max));
            }
            for (long hole : excluded)
                canonical.add(new Ast.RefinementBound(metricName, "ne", hole));
            if (canonical.isEmpty() && nominalIdentity.isEmpty()) return null;
            return new Ast.RefinementSpec(canonical, null, !nominalIdentity.isEmpty());
        }
    }

    private static String fingerprintFor(String base, String property,
                                         long min, long max,
                                         List<Long> exclusions, String identity) {
        String canonical = "oreslang.refinement.v1\n" + base + "\n"
                + property + "\n" + min + "\n"
                + max + "\n" + exclusions + "\n" + identity;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static Descriptor descriptor(String qualifiedIdentity, Ast.TypeRef base,
                                        Ast.RefinementSpec spec) {
        Objects.requireNonNull(spec, "refinement");
        if (spec.whereExpression() != null)
            throw new UnsupportedOperationException("where predicates cannot be emitted as scalar schemas");
        String identity = "";
        if (spec.nominal()) {
            if (!qualifiedIdentity(qualifiedIdentity))
                throw new IllegalArgumentException("nominal schema requires qualified identifier");
            identity = qualifiedIdentity;
        }
        Interval interval = normalize(base, spec.bounds());
        String canonicalBase = canonicalBaseName(base);
        List<Long> exclusions = List.copyOf(new TreeSet<>(interval.excluded()));
        String canonical = "oreslang.refinement.v1\n" + canonicalBase + "\n"
                + interval.property() + "\n" + interval.min() + "\n"
                + interval.max() + "\n" + exclusions + "\n" + identity;
        String hash;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            hash = HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        return new Descriptor(1, canonicalBase, interval.property(), interval.min(),
                interval.max(), exclusions, identity, hash);
    }

    /** A constrained alias may flow into a weaker alias; nominal identities must match. */
    public static boolean entails(String fromIdentity, Ast.TypeRef fromType, Ast.RefinementSpec from,
                                   String toIdentity, Ast.TypeRef toType, Ast.RefinementSpec to) {
        if (from == null || to == null) return false;
        if (!canonicalBaseName(fromType).equals(canonicalBaseName(toType))) return false;
        if (from.nominal() || to.nominal()) {
            if (!from.nominal() || !to.nominal()
                    || !qualifiedIdentity(fromIdentity) || !qualifiedIdentity(toIdentity)
                    || !Objects.equals(fromIdentity, toIdentity)) {
                return false;
            }
        }
        // General where predicates have no implication proof until purity, a
        // typed verifier and a restricted solver are integrated. Identical
        // source text is not proof: capture/binding context and effects are
        // not part of this host-side representation yet.
        if (from.whereExpression() != null || to.whereExpression() != null) return false;
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
        Long metric = scalarMetric(canonicalBaseName(base), raw);
        return metric != null && normalize(base, spec.bounds()).contains(metric);
    }

    /** Non-public constructor: no unchecked nominal wrapper manufacture. */
    public static final class NominalValue {
        private final String qualifiedTypeName;
        private final String schemaFingerprint;
        private Object underlying;
        private NominalValue(String qualifiedTypeName, String schemaFingerprint,
                             Object underlying) {
            if (!qualifiedIdentity(qualifiedTypeName))
                throw new IllegalArgumentException("qualified nominal identity is required");
            if (schemaFingerprint == null
                    || !schemaFingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("canonical schema fingerprint is required");
            if (!(underlying instanceof Long) && !(underlying instanceof String))
                throw new IllegalArgumentException("nominal scalar must be immutable int64/String");
            this.qualifiedTypeName = qualifiedTypeName;
            this.schemaFingerprint = schemaFingerprint;
            this.underlying = Objects.requireNonNull(underlying);
        }
        public String qualifiedTypeName() { return qualifiedTypeName; }
        public String schemaFingerprint() { return schemaFingerprint; }

        // Same one-shot ownership rule as the schema-first CheckedScalar
        // preview. Metadata remains diagnostic, not an authoritative proof.
        public synchronized Object intoInner() {
            if (underlying == null)
                throw new IllegalStateException("Zorde nominal scalar was already consumed");
            Object taken = underlying;
            underlying = null;
            return taken;
        }
    }
    public record CheckedValue(boolean ok, Object value, String error) { }

    /** Host-side reference operation; NOT yet the guest-language Result<T,E> API. */
    public static CheckedValue tryFrom(String identity, Ast.TypeRef base,
                                       Ast.RefinementSpec spec, Object raw) {
        if (spec == null) throw new IllegalArgumentException("missing refinement");
        if (spec.nominal() && !qualifiedIdentity(identity)) {
            throw new IllegalArgumentException(
                    "nominal checked construction requires qualified identifier");
        }
        // Checked construction is descriptor-driven so the value wrapper is
        // bound to the exact normalized constraint ABI that admitted it.
        Descriptor schema = descriptor(spec.nominal() ? identity : null, base, spec);
        if (!schema.accepts(raw))
            return new CheckedValue(false, null, "constraint violation for " + identity);
        return new CheckedValue(true,
                spec.nominal()
                        ? new NominalValue(identity, schema.fingerprint(), raw)
                        : raw,
                null);
    }
}
