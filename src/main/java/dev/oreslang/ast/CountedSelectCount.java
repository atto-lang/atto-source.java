package dev.oreslang.ast;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.ToLongFunction;

/** Restricted pure i64 counted-select expression, checked before admission. */
public final class CountedSelectCount {
    public static final String SNAPSHOT = "$counted_select_count";
    private CountedSelectCount() {}

    public static Set<String> names(Ast.Expr expression) {
        Set<String> names = new LinkedHashSet<>();
        visit(expression, names, new int[]{64});
        return Set.copyOf(names);
    }

    private static void visit(Ast.Expr expression, Set<String> names, int[] budget) {
        if (--budget[0] < 0) throw new IllegalArgumentException("count expression budget");
        if (expression instanceof Ast.LiteralExpr literal && literal.value() instanceof Long) return;
        if (expression instanceof Ast.NameExpr name && !name.name().startsWith("$")) {
            names.add(name.name());
            return;
        }
        if (expression instanceof Ast.BinaryExpr binary
                && (binary.operator().equals("+") || binary.operator().equals("-") || binary.operator().equals("*"))) {
            visit(binary.left(), names, budget);
            visit(binary.right(), names, budget);
            return;
        }
        throw new IllegalArgumentException("unsupported counted select count expression");
    }

    public static long evaluate(Ast.Expr expression, ToLongFunction<String> lookup) {
        names(expression);
        return evaluateChecked(expression, lookup);
    }

    private static long evaluateChecked(Ast.Expr expression, ToLongFunction<String> lookup) {
        if (expression instanceof Ast.LiteralExpr literal) return (Long) literal.value();
        if (expression instanceof Ast.NameExpr name) return lookup.applyAsLong(name.name());
        Ast.BinaryExpr binary = (Ast.BinaryExpr) expression;
        long left = evaluateChecked(binary.left(), lookup);
        long right = evaluateChecked(binary.right(), lookup);
        return switch (binary.operator()) {
            case "+" -> Math.addExact(left, right);
            case "-" -> Math.subtractExact(left, right);
            case "*" -> Math.multiplyExact(left, right);
            default -> throw new IllegalArgumentException("unsupported count operator");
        };
    }
}
