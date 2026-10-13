package dev.oreslang.ast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compile-time binding of value argument labels to formal parameter slots.
 *
 * <p>Labels are not part of the runtime ABI and never participate in overload
 * resolution: the caller chooses an existing callable by owner/name/arity,
 * then binds its source-ordered arguments to that callable's formal slots.
 * This class does not evaluate or reorder expressions. Backends must evaluate
 * the source argument expressions first, exactly once, then permute values.
 */
public final class NamedCallBinder {
    private NamedCallBinder() { }

    public static boolean hasNamed(List<Ast.Expr> arguments) {
        return arguments.stream().anyMatch(Ast.NamedArgumentExpr.class::isInstance);
    }

    public static Ast.Expr value(Ast.Expr argument) {
        return argument instanceof Ast.NamedArgumentExpr named
                ? named.expression() : argument;
    }

    public record Plan(List<Integer> sourceToFormal) {
        public Plan {
            sourceToFormal = List.copyOf(sourceToFormal);
            Set<Integer> slots = new HashSet<>(sourceToFormal);
            if (slots.size() != sourceToFormal.size()) {
                throw new IllegalArgumentException("named call binding repeats a formal slot");
            }
            for (int i = 0; i < sourceToFormal.size(); i++) {
                if (!slots.contains(i)) {
                    throw new IllegalArgumentException("named call binding leaves a formal slot unbound");
                }
            }
        }

        /** Permute after evaluation, never evaluate in declaration order. */
        public <T> List<T> toFormalOrder(List<T> sourceValues) {
            if (sourceValues.size() != sourceToFormal.size()) {
                throw new IllegalArgumentException("named call value count mismatch");
            }
            ArrayList<T> ordered = new ArrayList<>(sourceValues);
            for (int i = 0; i < sourceValues.size(); i++) {
                ordered.set(sourceToFormal.get(i), sourceValues.get(i));
            }
            return java.util.Collections.unmodifiableList(ordered);
        }

        public List<Ast.Param> parametersInSourceOrder(List<Ast.Param> formals) {
            if (formals.size() != sourceToFormal.size()) {
                throw new IllegalArgumentException("named call formal count mismatch");
            }
            return sourceToFormal.stream().map(formals::get).toList();
        }

        public List<Ast.Expr> expressionsInFormalOrder(List<Ast.Expr> source) {
            ArrayList<Ast.Expr> values = new ArrayList<>(source.size());
            for (Ast.Expr argument : source) values.add(value(argument));
            return toFormalOrder(values);
        }
    }

    public static Plan bind(
            List<Ast.Expr> sourceArguments,
            List<Ast.Param> formals,
            String callable) {
        if (sourceArguments.size() != formals.size()) {
            throw new IllegalArgumentException(
                    callable + " arity mismatch: expected " + formals.size()
                            + " argument(s), got " + sourceArguments.size());
        }

        Map<String, Integer> byName = new HashMap<>();
        for (int i = 0; i < formals.size(); i++) {
            String name = formals.get(i).name();
            if (byName.putIfAbsent(name, i) != null) {
                throw new IllegalArgumentException(
                        callable + " has duplicate parameter name '" + name + "'");
            }
        }

        boolean namedSeen = false;
        boolean spreadSeen = false;
        boolean[] filled = new boolean[formals.size()];
        ArrayList<Integer> mapping = new ArrayList<>(sourceArguments.size());
        int positional = 0;
        for (Ast.Expr argument : sourceArguments) {
            if (argument instanceof Ast.SpreadExpr) {
                if (hasNamed(sourceArguments)) {
                    throw new IllegalArgumentException(
                            callable + " cannot mix named arguments and tuple spreads");
                }
                spreadSeen = true;
                throw new IllegalArgumentException(
                        callable + " requires static expansion of argument spreads");
            }

            int slot;
            if (argument instanceof Ast.NamedArgumentExpr named) {
                if (spreadSeen) {
                    throw new IllegalArgumentException(
                            callable + " cannot mix named arguments and tuple spreads");
                }
                namedSeen = true;
                Integer target = byName.get(named.name());
                if (target == null) {
                    throw new IllegalArgumentException(
                            callable + " has no parameter named '" + named.name() + "'");
                }
                slot = target;
            } else {
                if (namedSeen) {
                    throw new IllegalArgumentException(
                            callable + " positional argument after named argument");
                }
                slot = positional++;
            }
            if (filled[slot]) {
                throw new IllegalArgumentException(
                        callable + " assigns parameter '" + formals.get(slot).name() + "' more than once");
            }
            filled[slot] = true;
            mapping.add(slot);
        }
        for (int i = 0; i < filled.length; i++) {
            if (!filled[i]) {
                throw new IllegalArgumentException(
                        callable + " missing required parameter '" + formals.get(i).name() + "'");
            }
        }
        return new Plan(mapping);
    }
}
