package dev.oreslang.types;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiler-only reference proof for a PROPOSED enum-case overload extension.
 *
 * <p>Not wired into Parser, TypeChecker, OwnershipChecker, callable slots,
 * Truffle dispatch, or LLVM. See docs/ENUM_CASE_OVERLOAD_SPECIALIZATION.md.
 * Inputs MUST come from a trusted lexical resolver, never guest-supplied IDs.
 * The current executable compiler remains arity-only.
 */
public final class EnumCaseOverloadPolicy {
    private EnumCaseOverloadPolicy() { }

    public static final int MAX_ARITY = 64;
    public static final int MAX_CANDIDATES = 128;
    public static final int MAX_CASES = 1024;
    /** Initially admitted non-discriminator values are proven scalar primitives only. */
    private static final Set<String> FIXED_SCALARS =
            Set.of("bool", "int", "string", "float");

    public enum Domain { MODULE, INSTANCE, STATIC }
    public enum Kind { FIXED, ENUM, CASE }
    private enum Relation { EQUAL, LEFT_NARROWER, RIGHT_NARROWER, DISJOINT, INVALID }

    /** Immutable, prevalidated registry entry; payload eligibility is a trusted typechecker proof. */
    public record EnumInfo(String identity, List<String> cases, boolean payloadEligible) {
        public EnumInfo {
            qualified(identity);
            Objects.requireNonNull(cases, "cases");
            cases = List.copyOf(cases);
            if (cases.isEmpty() || cases.size() > MAX_CASES
                    || new HashSet<>(cases).size() != cases.size()) {
                throw new IllegalArgumentException("invalid enum case set");
            }
            for (String value : cases) identifier(value);
        }
    }

    /** FIXED is a shared, exactly identical non-discriminator; ENUM/CASE are nominal. */
    public record Parameter(Kind kind, String identity, String caseName) {
        public Parameter {
            Objects.requireNonNull(kind, "kind");
            identifier(identity);
            if (kind == Kind.FIXED) {
                if (caseName != null || !FIXED_SCALARS.contains(identity)) {
                    throw new IllegalArgumentException(
                            "unproven or noncanonical fixed parameter: " + identity);
                }
            } else {
                qualified(identity);
                if (kind == Kind.ENUM && caseName != null) {
                    throw new IllegalArgumentException("whole enum cannot name a case");
                }
                if (kind == Kind.CASE) identifier(caseName);
            }
        }

        public static Parameter fixed(String id) {
            return new Parameter(Kind.FIXED, id, null);
        }

        public static Parameter enumType(String resolvedEnumId) {
            return new Parameter(Kind.ENUM, resolvedEnumId, null);
        }

        public static Parameter enumCase(String resolvedEnumId, String caseId) {
            return new Parameter(Kind.CASE, resolvedEnumId, caseId);
        }
    }

    /** Explicit self is already removed by the caller; fnc/routine share MODULE. */
    public record Signature(Domain domain, String owner, String name, List<Parameter> params) {
        public Signature {
            Objects.requireNonNull(domain, "domain");
            qualified(owner);
            identifier(name);
            Objects.requireNonNull(params, "params");
            params = List.copyOf(params);
            if (params.size() > MAX_ARITY) {
                throw new IllegalArgumentException("overload arity limit exceeded");
            }
        }

        public int arity() { return params.size(); }

        /**
         * Injective, deterministic compiler/linker slot identity.
         * Enums are keyed by resolved declarations and CASE symbols, never
         * source spellings from guest values or on-wire numeric tags.
         */
        public String symbolId() {
            StringBuilder out = new StringBuilder("eos2");
            encode(out, domain.name());
            encode(out, owner);
            encode(out, name);
            out.append('a').append(arity()).append('_');
            for (Parameter parameter : params) {
                out.append(switch (parameter.kind()) {
                    case FIXED -> 'F';
                    case ENUM -> 'E';
                    case CASE -> 'C';
                });
                encode(out, parameter.identity());
                if (parameter.kind() == Kind.CASE) encode(out, parameter.caseName());
            }
            return out.toString();
        }
    }

    /**
     * Returns an immutable family only when EVERY candidate pair is disjoint or
     * strictly nested. Deliberately rejects overlapping, incomparable methods.
     * Normal callable argument typing/effects/ownership are separate checks.
     */
    public static List<Signature> validateFamily(
            List<Signature> candidates, Map<String, EnumInfo> certifiedEnums) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(certifiedEnums, "certifiedEnums");
        List<Signature> copy = List.copyOf(candidates);
        Map<String, EnumInfo> registry = Map.copyOf(certifiedEnums);
        if (copy.isEmpty() || copy.size() > MAX_CANDIDATES) {
            throw new IllegalArgumentException("empty or oversize overload family");
        }
        Signature first = copy.get(0);
        for (Map.Entry<String, EnumInfo> entry : registry.entrySet()) {
            if (!entry.getKey().equals(entry.getValue().identity())) {
                throw new IllegalArgumentException("enum registry identity mismatch");
            }
        }
        for (Signature signature : copy) {
            if (signature.domain() != first.domain()
                    || !signature.owner().equals(first.owner())
                    || !signature.name().equals(first.name())
                    || signature.arity() != first.arity()) {
                throw new IllegalArgumentException("different owner/domain/name/arity");
            }
            for (Parameter parameter : signature.params()) checked(parameter, registry);
        }
        for (int i = 0; i < copy.size(); i++) {
            for (int j = i + 1; j < copy.size(); j++) {
                Relation relation = relation(copy.get(i), copy.get(j));
                if (relation == Relation.EQUAL || relation == Relation.INVALID) {
                    throw new IllegalArgumentException("duplicate or ambiguous same-arity overload");
                }
            }
        }
        return copy;
    }

    /**
     * Selects a SINGLE declaration from a complete static type proof.
     * A static Foo is NOT proof of Foo.A, even if its runtime value is A.
     * This method is not runtime dispatch and must never inspect guest values.
     */
    public static Signature selectStatic(
            List<Signature> candidates, Map<String, EnumInfo> certifiedEnums,
            List<Parameter> staticallyProvenArgs) {
        List<Signature> family = validateFamily(candidates, certifiedEnums);
        Objects.requireNonNull(staticallyProvenArgs, "staticallyProvenArgs");
        List<Parameter> args = List.copyOf(staticallyProvenArgs);
        if (args.size() != family.get(0).arity()) {
            throw new IllegalArgumentException("wrong argument arity");
        }
        Map<String, EnumInfo> registry = Map.copyOf(certifiedEnums);
        for (Parameter arg : args) checked(arg, registry);
        List<Signature> applicable = new ArrayList<>();
        for (Signature signature : family) {
            boolean all = true;
            for (int i = 0; i < args.size(); i++) {
                if (!accepts(signature.params().get(i), args.get(i))) {
                    all = false;
                    break;
                }
            }
            if (all) applicable.add(signature);
        }
        if (applicable.isEmpty()) {
            throw new IllegalArgumentException("no callable selected by static argument types");
        }
        Signature winner = null;
        for (Signature candidate : applicable) {
            boolean strictlyMostSpecific = true;
            for (Signature other : applicable) {
                if (candidate == other) continue;
                if (relation(candidate, other) != Relation.LEFT_NARROWER) {
                    strictlyMostSpecific = false;
                    break;
                }
            }
            if (strictlyMostSpecific) {
                if (winner != null) throw new IllegalArgumentException("ambiguous static selection");
                winner = candidate;
            }
        }
        if (winner == null) throw new IllegalArgumentException("ambiguous static selection");
        return winner;
    }

    private static boolean accepts(Parameter wanted, Parameter proven) {
        if (wanted.kind() == Kind.FIXED || proven.kind() == Kind.FIXED) {
            return wanted.equals(proven);
        }
        if (!wanted.identity().equals(proven.identity())) return false;
        if (wanted.kind() == Kind.ENUM) return true;
        return proven.kind() == Kind.CASE && wanted.caseName().equals(proven.caseName());
    }

    private static Relation relation(Signature left, Signature right) {
        boolean leftSubset = true;
        boolean rightSubset = true;
        boolean disjoint = false;
        for (int i = 0; i < left.arity(); i++) {
            Parameter a = left.params().get(i);
            Parameter b = right.params().get(i);
            if (a.kind() == Kind.FIXED || b.kind() == Kind.FIXED) {
                // An ordinary type cannot be a discriminator, even if other
                // positions are disjoint.
                if (!a.equals(b)) return Relation.INVALID;
                continue;
            }
            if (!a.identity().equals(b.identity())) {
                disjoint = true;
                continue;
            }
            if (a.kind() == Kind.ENUM && b.kind() == Kind.CASE) leftSubset = false;
            else if (a.kind() == Kind.CASE && b.kind() == Kind.ENUM) rightSubset = false;
            else if (a.kind() == Kind.CASE && b.kind() == Kind.CASE
                    && !a.caseName().equals(b.caseName())) disjoint = true;
        }
        if (disjoint) return Relation.DISJOINT;
        if (leftSubset && rightSubset) return Relation.EQUAL;
        if (leftSubset) return Relation.LEFT_NARROWER;
        if (rightSubset) return Relation.RIGHT_NARROWER;
        return Relation.INVALID;
    }

    private static void checked(Parameter param, Map<String, EnumInfo> registry) {
        // Parameter records validate at construction; this additional check
        // keeps this helper conservative if records evolve in a later ABI.
        if (param.kind() == Kind.FIXED) {
            if (!FIXED_SCALARS.contains(param.identity())) {
                throw new IllegalArgumentException("unproven fixed scalar");
            }
            return;
        }
        EnumInfo declaration = registry.get(param.identity());
        if (declaration == null || !declaration.payloadEligible()) {
            throw new IllegalArgumentException("unproven or ineligible enum declaration");
        }
        if (param.kind() == Kind.CASE && !declaration.cases().contains(param.caseName())) {
            throw new IllegalArgumentException("unknown case for resolved enum declaration");
        }
    }

    private static void encode(StringBuilder out, String value) {
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        out.append(utf8.length).append('_').append(HexFormat.of().formatHex(utf8));
    }

    private static void qualified(String id) {
        identifier(id);
        if (!id.contains(".") || id.startsWith(".") || id.endsWith(".")
                || id.contains("..")) {
            throw new IllegalArgumentException("expected qualified nominal identity");
        }
    }

    private static void identifier(String value) {
        Objects.requireNonNull(value, "identity");
        if (value.isBlank()) throw new IllegalArgumentException("blank identity");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                throw new IllegalArgumentException("control/whitespace in canonical identity");
            }
            if (Character.isHighSurrogate(c)) {
                if (i + 1 == value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("malformed UTF-16 identity");
                }
                int codepoint = Character.toCodePoint(c, value.charAt(++i));
                if (bidiOrInvisible(codepoint)) {
                    throw new IllegalArgumentException("bidi/invisible control in canonical identity");
                }
            } else if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("malformed UTF-16 identity");
            } else if (bidiOrInvisible(c)) {
                throw new IllegalArgumentException("bidi/invisible control in canonical identity");
            }
        }
    }

    private static boolean bidiOrInvisible(int cp) {
        // Explicit, version-independent denylist kept byte-identical in the
        // native companion; no host Unicode database/version disagreement.
        return cp == 0x061c || (cp >= 0x200b && cp <= 0x200f)
                || (cp >= 0x202a && cp <= 0x202e)
                || cp == 0x2060 || (cp >= 0x2066 && cp <= 0x2069)
                || cp == 0xfeff;
    }
}
