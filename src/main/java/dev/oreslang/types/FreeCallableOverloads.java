package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.TaggedMessageCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic same-arity free-callable selector for primitive or named-enum
 * tags. Structural types, arbitrary classes, return types, and enum numeric
 * values never participate in overload identity.
 *
 * The pairwise-disjointness check rejects overlapping families eagerly.
 * The static call checker selects exactly one declaration before ordinary
 * argument/effect/ownership validation. The interpreter mirrors the proven
 * tag decision for execution; native codegen must bind that selection to a
 * compile-time symbol, not generate a runtime structural search.
 */
public final class FreeCallableOverloads {
    private FreeCallableOverloads() { }

    private static String primitive(String name) {
        return switch (name) {
            case "int", "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "uint", "bigint" -> "p:int";
            case "bool", "boolean", "Bool" -> "p:bool";
            case "string", "String" -> "p:string";
            case "float", "f32", "f64" -> "p:float";
            // Decimal/complex native representations have not been reconciled
            // with boxed guest runtime values. They may be ordinary params,
            // but cannot currently distinguish same-arity overload slots.
            default -> null;
        };
    }

    private static String declaredTag(Ast.TypeRef type, Set<String> enumOwners) {
        if (type == null || type.inferArguments() || !type.arguments().isEmpty()
                || type.isBorrow() || type.isRecordType() || type.isUnion()) return null;
        String primitive = primitive(type.name());
        if (primitive != null) return primitive;
        return enumOwners.contains(type.name()) ? "e:" + type.name() : null;
    }

    private static String staticTag(Types.Type type, Map<String, TaggedMessageContracts.EnumType> enums) {
        if (type instanceof Types.Primitive value) {
            return switch (value) {
                case INT -> "p:int";
                case BOOL -> "p:bool";
                case STRING -> "p:string";
                case FLOAT -> "p:float";
                // No same-arity decimal/complex tags until runtime ABI parity.
                default -> null;
            };
        }
        if (type instanceof Types.StringLiteral) return "p:string";
        if (type instanceof Types.Named named && named.arguments().isEmpty()) {
            if (enums.containsKey(named.name())) return "e:" + named.name();
            // A dotted nominal *is not* an enum merely because its prefix
            // matches one. Require an exact registry case (no lookalike,
            // fabricated variant, class or nested dotted type).
            int dot = named.name().indexOf('.');
            if (dot > 0 && dot == named.name().lastIndexOf('.')) {
                TaggedMessageContracts.EnumType owner =
                        enums.get(named.name().substring(0, dot));
                if (owner != null) {
                    String variant = named.name().substring(dot + 1);
                    for (TaggedMessageContracts.Case entry : owner.cases()) {
                        if (entry.name().equals(variant)) return "e:" + owner.name();
                    }
                }
            }
        }
        // In particular Unknown, Generic, and Union do not select an overload.
        return null;
    }

    private static String runtimeTag(Object value, Set<String> enumOwners) {
        if (value instanceof TaggedMessageCodec.TaggedValue tagged
                && enumOwners.contains(tagged.enumName())) return "e:" + tagged.enumName();
        if (value instanceof Boolean) return "p:bool";
        if (value instanceof String) return "p:string";
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
            return "p:int";
        if (value instanceof Float || value instanceof Double) return "p:float";
        return null;
    }

    private static boolean disjoint(Ast.FunctionDecl left, Ast.FunctionDecl right, Set<String> enumOwners) {
        for (int i = 0; i < left.parameters().size(); i++) {
            String a = declaredTag(left.parameters().get(i).type(), enumOwners);
            String b = declaredTag(right.parameters().get(i).type(), enumOwners);
            if (a != null && b != null && !a.equals(b)) return true;
        }
        return false;
    }

    private static void requirePair(
            Ast.FunctionDecl prior, Ast.FunctionDecl next,
            Set<String> enumOwners, String name) {
        // The evaluator's preemption gate must not inspect only one variant
        // when declared scheduling/effect envelopes differ.
        if (prior.async() != next.async() || prior.suspends() != next.suspends()
                || prior.generator() != next.generator()
                || prior.trapped() != next.trapped()
                || prior.actorKind() != next.actorKind()
                || prior.returnType().name().equals("Future")
                        != next.returnType().name().equals("Future")) {
            throw new IllegalArgumentException(
                    "nominal overload family '" + name
                            + "' requires identical async/suspend/generator/trap/actor scheduling envelopes");
        }
        if (prior.parameters().size() != next.parameters().size()) {
            throw new IllegalArgumentException("invalid mixed-arity overload bucket for " + name);
        }
        if (!disjoint(prior, next, enumOwners)) {
            throw new IllegalArgumentException(
                    "callable '" + name + "' already has arity " + next.parameters().size()
                            + "; same-arity overloads require distinct exact primitive or nominal enum tags"
                            + " at a common parameter position (no structural fallback)");
        }
    }

    /**
     * Validate one declaration before appending to an already admitted slot.
     * Type-checker and evaluator indexes grow monotonically; comparing all
     * earlier pairs on every append was O(N^3) cumulative work for an
     * adversarial but bounded family of 256 variants. Now O(N^2) total.
     */
    public static void requireAppendOnly(
            List<Ast.FunctionDecl> previous, Ast.FunctionDecl next,
            Set<String> enumOwners, String name) {
        Objects.requireNonNull(previous);
        Objects.requireNonNull(next);
        Objects.requireNonNull(enumOwners);
        if (previous.size() >= 256) {
            throw new IllegalArgumentException(
                    "nominal overload family '" + name + "' exceeds 256 declarations");
        }
        if (next.parameters().size() > 64) {
            throw new IllegalArgumentException(
                    "nominal overload family '" + name + "' exceeds 64 explicit parameters");
        }
        for (Ast.FunctionDecl prior : previous) {
            if (prior.parameters().size() > 64) {
                throw new IllegalArgumentException(
                        "nominal overload family '" + name + "' exceeds 64 explicit parameters");
            }
            requirePair(prior, next, enumOwners, name);
        }
    }

    public static void requireUnambiguous(
            List<Ast.FunctionDecl> overloads, Set<String> enumOwners, String name) {
        Objects.requireNonNull(overloads);
        if (overloads.size() > 256) {
            throw new IllegalArgumentException(
                    "nominal overload family '" + name + "' exceeds 256 declarations");
        }
        for (int i = 0; i < overloads.size(); i++) {
            requireAppendOnly(overloads.subList(0, i), overloads.get(i),
                    enumOwners, name);
        }
    }

    private static Ast.FunctionDecl choose(List<Ast.FunctionDecl> candidates, List<String> tags,
                                            Set<String> enumOwners, String display) {
        Ast.FunctionDecl selected = null;
        for (Ast.FunctionDecl candidate : candidates) {
            if (candidate.parameters().size() != tags.size()) continue;
            boolean matches = true;
            for (int i = 0; i < tags.size(); i++) {
                String declared = declaredTag(candidate.parameters().get(i).type(), enumOwners);
                // An unconstrained position is irrelevant to overload selection.
                if (declared != null && !declared.equals(tags.get(i))) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                if (selected != null) throw new IllegalArgumentException(
                        "ambiguous nominal overload call '" + display + "'; narrow the argument type explicitly");
                selected = candidate;
            }
        }
        if (selected == null) throw new IllegalArgumentException(
                "no matching nominal overload for '" + display + "'; explicit static type narrowing required");
        return selected;
    }

    public static Ast.FunctionDecl chooseStatic(List<Ast.FunctionDecl> candidates,
            List<Types.Type> argumentTypes,
            Map<String, TaggedMessageContracts.EnumType> enums, String display) {
        if (candidates.size() == 1) {
            if (candidates.getFirst().parameters().size() != argumentTypes.size()) {
                throw new IllegalArgumentException(
                        "call arity mismatch for '" + display + "'");
            }
            return candidates.getFirst();
        }
        List<String> tags = new ArrayList<>(argumentTypes.size());
        for (Types.Type type : argumentTypes) tags.add(staticTag(type, enums));
        return choose(candidates, tags, enums.keySet(), display);
    }

    public static Ast.FunctionDecl chooseRuntime(List<Ast.FunctionDecl> candidates,
            List<?> values, Set<String> enumOwners, String display) {
        if (candidates.size() == 1) {
            if (candidates.getFirst().parameters().size() != values.size()) {
                throw new IllegalArgumentException(
                        "call arity mismatch for '" + display + "'");
            }
            return candidates.getFirst();
        }
        List<String> tags = new ArrayList<>(values.size());
        for (Object value : values) tags.add(runtimeTag(value, enumOwners));
        return choose(candidates, tags, enumOwners, display);
    }
}
