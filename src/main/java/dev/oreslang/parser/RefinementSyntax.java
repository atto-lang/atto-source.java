package dev.oreslang.parser;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Side-effect-free syntactic normalization for refined declarations.
 * IMPORTANT: the compiler must reject this metadata before execution until
 * checked constructors, subtyping, purity and actor boundaries are enforced.
 */
public final class RefinementSyntax {
    private RefinementSyntax() {}

    private static final class Cursor {
        final List<Token> tokens;
        int index;
        Cursor(List<Token> tokens) { this.tokens = List.copyOf(tokens); }
        boolean has() { return index < tokens.size(); }
        String peek() { return has() ? tokens.get(index).lexeme() : "<end>"; }
        String take() {
            if (!has()) throw new IllegalArgumentException("refinement: unexpected end of clause");
            return tokens.get(index++).lexeme();
        }
        boolean accept(String word) {
            if (peek().equals(word)) { index++; return true; }
            return false;
        }
    }

    public static Ast.RefinementSpec parse(
            Ast.TypeRef base, List<Ast.Annotation> annotations, boolean nominal, List<Token> suffix) {
        // Non-refined aliases (including generic, tuple and structural aliases)
        // must follow the preexisting compiler path unchanged.
        if (annotations.isEmpty() && suffix.isEmpty() && !nominal) return null;
        for (Ast.Annotation annotation : annotations) {
            if (!Set.of("Size", "Length", "NotEmpty", "MinLength", "MaxLength", "Min", "Max", "Range").contains(annotation.name())) {
                bad("@" + annotation.name()
                        + " on type alias would be silently discarded; unsupported constraint annotation");
            }
        }
        List<Ast.RefinementBound> bounds = new ArrayList<>();
        String expected = property(base);
        for (Ast.Annotation annotation : annotations) {
            switch (annotation.name()) {
                case "NotEmpty" -> {
                    if (!annotation.arguments().isEmpty()) bad("@NotEmpty takes no arguments");
                    if (!expected.equals("length")) bad("@NotEmpty requires string");
                    bounds.add(new Ast.RefinementBound("length", "gt", 0));
                }
                case "MinLength", "MaxLength" -> {
                    if (!expected.equals("length")) bad("@" + annotation.name() + " requires string");
                    long bound = positionalBound(annotation);
                    if (bound < 0) bad("@" + annotation.name() + " length must be nonnegative");
                    bounds.add(new Ast.RefinementBound("length",
                            annotation.name().equals("MinLength") ? "gte" : "lte", bound));
                }
                case "Min", "Max" -> {
                    if (!expected.equals("value")) bad("@" + annotation.name() + " requires int");
                    long bound = positionalBound(annotation);
                    bounds.add(new Ast.RefinementBound("value",
                            annotation.name().equals("Min") ? "gte" : "lte", bound));
                }
                case "Range" -> {
                    if (!expected.equals("value")) bad("@Range requires int");
                    if (annotation.arguments().isEmpty()) bad("@Range requires min and/or max");
                    Set<String> seen = new HashSet<>();
                    for (Ast.TypeRef argument : annotation.arguments()) {
                        if (!argument.isNamedParameter() || !argument.namedParameterValue().isIntLiteral())
                            bad("@Range requires named integer min/max bounds");
                        String key = argument.namedParameterName();
                        if (!Set.of("min", "max").contains(key)) bad("@Range supports only min/max");
                        if (!seen.add(key)) bad("duplicate @Range bound " + key);
                        bounds.add(new Ast.RefinementBound("value",
                                key.equals("min") ? "gte" : "lte",
                                argument.namedParameterValue().intLiteralValue()));
                    }
                }
                case "Size", "Length" -> {
                    if (!expected.equals("length")) bad("@" + annotation.name() + " requires string");
                    if (annotation.arguments().isEmpty()) bad("@" + annotation.name() + " requires constraints");
                    Set<String> seen = new HashSet<>();
                    for (Ast.TypeRef argument : annotation.arguments()) {
                        if (!argument.isNamedParameter() || !argument.namedParameterValue().isIntLiteral()) {
                            bad("@" + annotation.name() + " expects named integer bounds");
                        }
                        String key = argument.namedParameterName();
                        if (!seen.add(key)) bad("duplicate annotation bound " + key);
                        String op = switch (key) {
                            case "min" -> "gte";
                            case "max" -> "lte";
                            case "eq" -> {
                                if (!annotation.name().equals("Length")) bad("@Size does not support eq");
                                yield "eq";
                            }
                            default -> throw new IllegalArgumentException("unknown @" + annotation.name() + " bound " + key);
                        };
                        long bound = argument.namedParameterValue().intLiteralValue();
                        if (bound < 0) bad("@" + annotation.name() + " length must be nonnegative");
                        bounds.add(new Ast.RefinementBound("length", op, bound));
                    }
                }
                default -> bad("@" + annotation.name() + " on type alias would be silently discarded; unsupported constraint annotation");
            }
        }

        Cursor cursor = new Cursor(suffix);
        String where = null;
        if (cursor.accept("with")) {
            do {
                String prop = cursor.take();
                if (!prop.equals(expected)) bad("property " + prop + " not valid for " + base.name());
                String cmp = operator(cursor.take());
                boolean neg = cursor.accept("-");
                String literal = cursor.take();
                if (!literal.matches("[0-9]+(?:_[0-9]+)*")) {
                    bad("refinement bound must be a well-formed integer constant");
                }
                // Parse the sign together with the magnitude: 9223372036854775808
                // is not a positive long, but -9223372036854775808 is valid.
                long value;
                try {
                    value = Long.parseLong((neg ? "-" : "") + literal.replace("_", ""));
                } catch (NumberFormatException exc) {
                    throw new IllegalArgumentException("refinement bound overflow", exc);
                }
                bounds.add(new Ast.RefinementBound(prop, cmp, value));
            } while (cursor.accept("and"));
            if (cursor.has()) bad("with accepts only 'and' of built-in comparisons; use where for expressions");
        } else if (cursor.accept("where")) {
            if (!cursor.has()) bad("where requires a predicate");
            StringBuilder predicate = new StringBuilder();
            while (cursor.has()) {
                if (!predicate.isEmpty()) predicate.append(' ');
                predicate.append(cursor.take());
                if (predicate.length() > 4096) bad("where predicate too long");
            }
            where = predicate.toString();
        } else if (cursor.has()) {
            bad("expected with/where after type expression");
        }

        bounds.sort(java.util.Comparator.comparing(Ast.RefinementBound::property)
                .thenComparingInt(b -> switch (b.comparator()) {
                    case "eq" -> 0;
                    case "gte" -> 1;
                    case "gt" -> 2;
                    case "lte" -> 3;
                    case "lt" -> 4;
                    default -> 5;
                }).thenComparingLong(Ast.RefinementBound::constant));
        verifyBounds(base, bounds);
        if (bounds.isEmpty() && where == null && !nominal) return null;
        return new Ast.RefinementSpec(bounds, where, nominal);
    }

    private static long positionalBound(Ast.Annotation annotation) {
        if (annotation.arguments().size() != 1 || !annotation.arguments().getFirst().isIntLiteral()) {
            bad("@" + annotation.name() + " requires exactly one constant integer argument");
        }
        return annotation.arguments().getFirst().intLiteralValue();
    }

    private static String property(Ast.TypeRef base) {
        return switch (base.name()) {
            case "string", "String" -> "length";
            case "int" -> "value";
            default -> throw new IllegalArgumentException(
                    "refinement prototype supports string and int only: " + base.name());
        };
    }

    private static String operator(String token) {
        return switch (token) {
            case "eq", "==" -> "eq";
            case "ne", "!=" -> "ne";
            case "gt", ">" -> "gt";
            case "gte", ">=" -> "gte";
            case "lt", "<" -> "lt";
            case "lte", "<=" -> "lte";
            default -> throw new IllegalArgumentException("unknown refinement comparator " + token);
        };
    }

    private static void verifyBounds(Ast.TypeRef base, List<Ast.RefinementBound> bounds) {
        // One normalizer for static parser diagnostics, implication and checked values.
        dev.oreslang.types.RefinementRules.normalize(base, bounds);
    }

    private static void bad(String message) { throw new IllegalArgumentException("refinement: " + message); }
}
