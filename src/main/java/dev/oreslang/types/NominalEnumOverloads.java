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

    private static final Set<String> SCALARS = Set.of(
            "bool", "int", "uint", "string", "String", "i8", "u8",
            "i16", "u16", "i32", "u32", "i64", "u64", "f64");

    public enum Kind { PRIMITIVE, ENUM }

    /** The type identity is the resolved enum DECLARATION, not its cases. */
    public record Position(Kind kind, String id) {
        public Position {
            Objects.requireNonNull(kind);
            Objects.requireNonNull(id);
        }
    }

    /** Not a value-dependent selection key: all enum IDs are compiler known. */
    public record Signature(String name, List<Position> positions) {
        public Signature {
            Objects.requireNonNull(name);
            positions = List.copyOf(positions);
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
                String symbol = module.name() + "::" + enumeration.name();
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
     * Produce a key only for enum or primitive parameter declarations.
     * Structural, generic, borrowed, union and dynamic types are ineligible.
     */
    public static Signature signature(
            String name, List<Ast.Param> parameters, Map<String, String> enumSymbols) {
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
                positions.add(new Position(Kind.PRIMITIVE, type.name()));
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
