package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Closed-world admission and deterministic signature IDs for nominal enum
 * overloads. This is a compiler policy helper, NOT a runtime multimethod.
 *
 * The Java evaluator still has arity-only tables. Until those tables accept
 * prebound targets, callers must not admit same-name/same-arity declarations.
 */
public final class NominalEnumOverloads {
    private NominalEnumOverloads() {}

    /** Explicit compiler work bounds; backend signatures must agree. */
    public static final int MAX_SAME_ARITY_CANDIDATES = 128;
    public static final int MAX_OVERLOAD_ARITY = 64;

    private static final Set<String> SCALARS = Set.of(
            "bool", "int", "uint", "string", "String", "i8", "u8",
            "i16", "u16", "i32", "u32", "i64", "u64", "f64");

    public enum Kind { PRIMITIVE, ENUM }

    /** The type identity is the resolved enum DECLARATION, not its cases. */
    public record Position(Kind kind, String id) {
        public Position {
            Objects.requireNonNull(kind, "overload position kind");
            Objects.requireNonNull(id, "overload position identity");
            if (id.isBlank()) {
                throw new IllegalArgumentException("blank overload position identity");
            }
            if (kind == Kind.PRIMITIVE) {
                if (!SCALARS.contains(id)) {
                    throw new IllegalArgumentException("unsupported overload primitive: " + id);
                }
                // Directly constructed keys must agree with signature() aliases.
                if (id.equals("String")) id = "string";
            } else if (!id.contains("::") || id.startsWith("::")
                    || id.endsWith("::") || id.contains("::::")) {
                throw new IllegalArgumentException(
                        "nominal enum overload ID must be declaration-qualified: " + id);
            }
        }
    }

    /** Not a value-dependent selection key: all enum IDs are compiler known. */
    public record Signature(String name, List<Position> positions) {
        public Signature {
            Objects.requireNonNull(name, "overload name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("blank overload callable name");
            }
            positions = List.copyOf(positions);
            // Enforce limits at the immutable key boundary, not just the
            // signature() factory. select()/compare() accept public records.
            if (positions.size() > MAX_OVERLOAD_ARITY) {
                throw new IllegalArgumentException("nominal enum overload exceeds arity limit "
                        + MAX_OVERLOAD_ARITY);
            }
        }
        public int arity() { return positions.size(); }
    }

    public enum PairStatus {
        DISTINCT_ARITY,
        DISTINCT_ENUMS,
        SAME_SIGNATURE,
        INVALID_SAME_ARITY
    }

    /**
     * Primitive-only payload fields in every case. Tuple is admitted only
     * as a fixed product of scalar fields. No structural shape comparison.
     */
    public static boolean eligibleDiscriminator(Ast.EnumDecl declaration) {
        return declaration != null && !declaration.cases().isEmpty()
                && declaration.cases().stream().allMatch(c ->
                        c.payload() == null || primitiveProduct(c.payload()));
    }

    private static boolean primitiveProduct(Ast.TypeRef type) {
        if (type == null || type.inferArguments() || type.isBorrow()
                || type.isRecordType() || type.isUnion() || type.isSequenceShape()
                || type.isRepeatExact() || type.isRepeatMany()) return false;
        if (type.arguments().isEmpty()) return SCALARS.contains(type.name());
        if (!(type.isTupleType() || type.name().equals("Tuple"))
                || type.arguments().isEmpty()) return false;
        // Fields must be primitive, not arbitrarily nested product types.
        return type.arguments().stream().allMatch(field ->
                !field.inferArguments() && field.arguments().isEmpty()
                        && SCALARS.contains(field.name()));
    }

    /**
     * A declaration is resolved to a stable per-program symbol, even if
     * multiple modules each declare a same-spelled enum name.
     */
    public static Map<String, String> eligibleEnumSymbols(Ast.Program program) {
        Map<String, String> names = new HashMap<>();
        Set<String> ambiguous = new java.util.HashSet<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.EnumDecl enumeration)
                        || !eligibleDiscriminator(enumeration)) continue;
                // Namespace is part of nominal identity. Cross-code-unit imports
                // need a linker-assigned symbol ID before overloads can be enabled.
                String prefix = program.namespace() == null || program.namespace().isBlank()
                        ? "" : program.namespace() + "::";
                String symbol = prefix + module.name() + "::" + enumeration.name();
                String previous = names.putIfAbsent(enumeration.name(), symbol);
                if (previous != null && !previous.equals(symbol)) {
                    ambiguous.add(enumeration.name());
                }
                names.put(symbol, symbol);
            }
        }
        for (String duplicate : ambiguous) names.remove(duplicate);
        return Map.copyOf(names);
    }

    /**
     * Recover only the statically proven nominal enum declaration from a
     * compiler-resolved argument type. A constructed Color.Red value has
     * nominal owner Color; its case/wire tag/payload never enters the key.
     * Unknown, unions, generics and unverified names provide NO proof.
     */
    public static String staticEnumArgumentSymbol(
            Types.Type argument,
            TaggedMessageContracts.Registry tagged,
            Map<String, String> eligibleSymbols) {
        if (!(argument instanceof Types.Named named) || !named.arguments().isEmpty()) {
            return null;
        }
        String ownerName = named.name();
        var owner = tagged.enums().get(ownerName);
        if (owner == null) {
            int separator = ownerName.lastIndexOf('.');
            if (separator < 1) return null;
            String possibleOwner = ownerName.substring(0, separator);
            owner = tagged.enums().get(possibleOwner);
            if (owner == null) return null;
            final String qualifiedCase = ownerName;
            if (owner.cases().stream().noneMatch(c ->
                    c.qualifiedName().equals(qualifiedCase))) {
                return null;
            }
            ownerName = possibleOwner;
        }
        return eligibleSymbols.get(ownerName);
    }

    /**
     * Produce a key only for enum or primitive parameter declarations.
     * Structural, generic, borrowed, union and dynamic types are ineligible.
     */
    public static Signature signature(
            String name, List<Ast.Param> parameters, Map<String, String> enumSymbols) {
        if (parameters.size() > MAX_OVERLOAD_ARITY) {
            throw new IllegalArgumentException("nominal enum overload exceeds arity limit "
                    + MAX_OVERLOAD_ARITY);
        }
        List<Position> positions = new ArrayList<>();
        for (Ast.Param parameter : parameters) {
            if (parameter.structural() || parameter.mutable()) {
                throw new IllegalArgumentException(
                        "structural/mutable parameters cannot select enum overloads");
            }
            Ast.TypeRef type = parameter.type();
            if (type == null || type.inferArguments() || type.isBorrow()
                    || !type.arguments().isEmpty()) {
                throw new IllegalArgumentException(
                        "dynamic, generic, borrowed or structural overload parameter");
            }
            String enumSymbol = enumSymbols.get(type.name());
            if (enumSymbol != null) {
                positions.add(new Position(Kind.ENUM, enumSymbol));
            } else if (SCALARS.contains(type.name())) {
                // Spellings accepted by the parser must not create different
                // overload ABI slots for the SAME primitive.
                String id = switch (type.name()) {
                    case "String" -> "string";
                    case "boolean" -> "bool";
                    default -> type.name();
                };
                positions.add(new Position(Kind.PRIMITIVE, id));
            } else {
                throw new IllegalArgumentException(
                        "parameter '" + type.name()
                                + "' is not an eligible primitive or resolved enum");
            }
        }
        return new Signature(name, positions);
    }

    /**
     * Every same-arity pair must differ by at least one distinct nominal
     * enum ID. Other positions must be identical symbols, not shapes.
     * Primitive differences NEVER distinguish overloads.
     */
    public static PairStatus compare(Signature left, Signature right) {
        if (!left.name().equals(right.name())) {
            throw new IllegalArgumentException("comparison requires same callable name");
        }
        if (left.arity() != right.arity()) return PairStatus.DISTINCT_ARITY;
        boolean distinctEnums = false;
        for (int i = 0; i < left.arity(); i++) {
            Position a = left.positions().get(i);
            Position b = right.positions().get(i);
            if (a.equals(b)) continue;
            if (a.kind() != Kind.ENUM || b.kind() != Kind.ENUM) {
                return PairStatus.INVALID_SAME_ARITY;
            }
            distinctEnums = true;
        }
        return distinctEnums ? PairStatus.DISTINCT_ENUMS : PairStatus.SAME_SIGNATURE;
    }

    /**
     * Resolve by static enum identity, not runtime values.
     * The caller supplies only the statically proven enum IDs for positions
     * that discriminate this overload family. Missing/unknown proofs fail.
     */
    public static Signature select(
            List<Signature> candidates, Map<Integer, String> provenEnumAtPosition) {
        if (candidates.isEmpty()) throw new IllegalArgumentException("empty overload set");
        if (candidates.size() > MAX_SAME_ARITY_CANDIDATES) {
            throw new IllegalArgumentException("nominal enum overload family exceeds candidate limit "
                    + MAX_SAME_ARITY_CANDIDATES);
        }
        int arity = candidates.getFirst().arity();
        String name = candidates.getFirst().name();
        for (Signature candidate : candidates) {
            if (!candidate.name().equals(name) || candidate.arity() != arity) {
                throw new IllegalArgumentException("mixed name/arity in overload family");
            }
        }
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                if (compare(candidates.get(i), candidates.get(j))
                        != PairStatus.DISTINCT_ENUMS) {
                    throw new IllegalArgumentException(
                            "ambiguous or ineligible same-arity overload family");
                }
            }
        }
        // Require a complete compiler-proven enum type for *every* position
        // that distinguishes any two candidates. Selecting from just one
        // supplied enum ID could bind the wrong target when other positions
        // are still Unknown, union-typed, or erased by interop.
        Set<Integer> discriminators = new java.util.HashSet<>();
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                Signature left = candidates.get(i);
                Signature right = candidates.get(j);
                for (int p = 0; p < arity; p++) {
                    if (!left.positions().get(p).equals(right.positions().get(p))) {
                        discriminators.add(p);
                    }
                }
            }
        }
        if (!provenEnumAtPosition.keySet().equals(discriminators)) {
            throw new IllegalArgumentException(
                    "incomplete or extraneous static nominal enum overload proof; "
                            + "all discriminator positions require a resolved enum identity");
        }
        for (Map.Entry<Integer, String> proof : provenEnumAtPosition.entrySet()) {
            if (proof.getValue() == null || proof.getValue().isBlank()) {
                throw new IllegalArgumentException("missing nominal enum symbol proof");
            }
        }
        List<Signature> matches = new ArrayList<>();
        for (Signature candidate : candidates) {
            boolean matched = true;
            for (Map.Entry<Integer, String> proof : provenEnumAtPosition.entrySet()) {
                int position = proof.getKey();
                if (position < 0 || position >= arity) {
                    throw new IllegalArgumentException("invalid enum discriminator index");
                }
                Position parameter = candidate.positions().get(position);
                if (parameter.kind() != Kind.ENUM
                        || !parameter.id().equals(proof.getValue())) {
                    matched = false;
                    break;
                }
            }
            if (matched) matches.add(candidate);
        }
        if (matches.size() != 1) {
            throw new IllegalArgumentException(
                    "cannot statically select a unique nominal enum overload");
        }
        return matches.getFirst();
    }
}
