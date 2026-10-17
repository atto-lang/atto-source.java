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
            if (!Set.of("Size", "Length", "NotEmpty").contains(annotation.name())) {
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
                            case "min" -> "ge";
                            case "max" -> "le";
                            case "eq" -> {
                                if (!annotation.name().equals("Length")) bad("@Size does not support eq");
                                yield "eq";
                            }
                            default -> throw new IllegalArgumentException("unknown @" + annotation.name() + " bound " + key);
                        };
                        bounds.add(new Ast.RefinementBound("length", op, argument.namedParameterValue().intLiteralValue()));
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
                String digits = cursor.take().replace("_", "");
                if (!digits.matches("[0-9]+")) bad("refinement bound must be an integer constant");
                long value;
                try {
                    value = Long.parseLong(digits);
                    if (neg) value = Math.negateExact(value);
                } catch (ArithmeticException exc) {
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

        verifyBounds(base, bounds);
        if (bounds.isEmpty() && where == null && !nominal) return null;
        return new Ast.RefinementSpec(bounds, where, nominal);
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
            case "ne", "neq", "!=" -> "ne";
            case "gt", ">" -> "gt";
            case "ge", ">=" -> "ge";
            case "lt", "<" -> "lt";
            case "le", "<=" -> "le";
            default -> throw new IllegalArgumentException("unknown refinement comparator " + token);
        };
    }

    private static void verifyBounds(Ast.TypeRef base, List<Ast.RefinementBound> bounds) {
        long min = property(base).equals("length") ? 0 : Long.MIN_VALUE;
        long max = Long.MAX_VALUE;
        Set<Long> excluded = new HashSet<>();
        for (Ast.RefinementBound bound : bounds) {
            long n = bound.constant();
            switch (bound.comparator()) {
                case "eq" -> { min = Math.max(min, n); max = Math.min(max, n); }
                case "ne" -> excluded.add(n);
                case "ge" -> min = Math.max(min, n);
                case "le" -> max = Math.min(max, n);
                case "gt" -> {
                    if (n == Long.MAX_VALUE) bad("unsatisfiable gt bound");
                    min = Math.max(min, n + 1);
                }
                case "lt" -> {
                    if (n == Long.MIN_VALUE) bad("unsatisfiable lt bound");
                    max = Math.min(max, n - 1);
                }
                default -> bad("invalid constraint comparator");
            }
        }
        if (min > max || min == max && excluded.contains(min)) bad("unsatisfiable refinement bounds");
    }

    private static void bad(String message) { throw new IllegalArgumentException("refinement: " + message); }
}
