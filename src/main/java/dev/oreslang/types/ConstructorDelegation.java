package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.lang.reflect.RecordComponent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed validation of sibling constructor delegation.
 *
 * <p>The only legal delegation is an unconditional first statement in a class
 * constructor. Resolution is by effective (caller-visible) arity, never by
 * parameter types or the optional leading self binder. Arguments are currently
 * limited to pure scalar parameter/literal expressions so they cannot publish
 * partial state, suspend, mutate, or allocate while the initializing receiver
 * is unavailable. This restriction can be relaxed only with checked effects.</p>
 */
public final class ConstructorDelegation {
    private ConstructorDelegation() { }

    public static Ast.ConstructorDelegateStmt first(Ast.ConstructorDecl ctor) {
        if (!ctor.body().isEmpty()
                && ctor.body().getFirst() instanceof Ast.ConstructorDelegateStmt delegation) {
            return delegation;
        }
        return null;
    }

    public static boolean isDelegating(Ast.ConstructorDecl ctor) {
        return first(ctor) != null;
    }

    public static List<Ast.Stmt> tail(Ast.ConstructorDecl ctor) {
        return isDelegating(ctor) ? ctor.body().subList(1, ctor.body().size()) : ctor.body();
    }

    public static void validate(Ast.ClassDecl klass) {
        if (klass.actorKind() != Ast.ActorKind.NONE) {
            if (!klass.constructors().isEmpty()) {
                throw new IllegalArgumentException("actors cannot declare constructors");
            }
            return;
        }
        Map<Integer, Integer> edges = new HashMap<>();
        for (Ast.ConstructorDecl ctor : klass.constructors()) {
            Ast.ConstructorDelegateStmt delegation = first(ctor);
            List<Ast.Stmt> body = ctor.body();
            for (int i = delegation == null ? 0 : 1; i < body.size(); i++) {
                rejectNested(body.get(i), klass.name());
            }
            if (delegation == null) continue;
            for (Ast.Expr argument : delegation.arguments()) {
                if (!safeScalarArgument(argument, ctor.parameters())) {
                    throw new IllegalArgumentException(
                            "constructor delegation in " + klass.name() + "/" + ctor.arity()
                                    + " requires pure scalar parameter/literal expressions; "
                                    + "self, calls, allocations, mutation and suspension are forbidden");
                }
            }
            int targetArity = delegation.arguments().size();
            if (klass.constructorForArity(targetArity) == null) {
                throw new IllegalArgumentException(
                        "constructor delegation from " + klass.name() + "/" + ctor.arity()
                                + " has no target constructor with arity " + targetArity);
            }
            edges.put(ctor.arity(), targetArity);
        }
        // Three-color DFS detects self- and mutual-delegation cycles.
        Set<Integer> visiting = new HashSet<>();
        Set<Integer> done = new HashSet<>();
        for (Ast.ConstructorDecl ctor : klass.constructors()) {
            visit(ctor.arity(), edges, visiting, done, klass.name());
        }
    }

    private static void visit(int arity, Map<Integer, Integer> edges,
                              Set<Integer> visiting, Set<Integer> done, String owner) {
        if (done.contains(arity)) return;
        if (!visiting.add(arity)) {
            throw new IllegalArgumentException(
                    "constructor delegation cycle in " + owner + " at arity " + arity);
        }
        Integer next = edges.get(arity);
        if (next != null) visit(next, edges, visiting, done, owner);
        visiting.remove(arity);
        done.add(arity);
    }

    private static boolean safeScalarArgument(Ast.Expr expr, List<Ast.Param> parameters) {
        if (expr instanceof Ast.LiteralExpr) return true;
        if (expr instanceof Ast.NameExpr name) {
            if (name.name().equals("self") || name.name().equals("arguments")) return false;
            for (Ast.Param param : parameters) {
                if (!param.name().equals(name.name())) continue;
                Ast.TypeRef type = param.type();
                return !type.isBorrow() && type.arguments().isEmpty()
                        && !param.structural()
                        && (type.name().equals("int") || type.name().equals("bool")
                            || type.name().equals("float") || type.name().equals("string"));
            }
            return false;
        }
        if (expr instanceof Ast.UnaryExpr op) {
            return (op.operator().equals("-") || op.operator().equals("+"))
                    && safeScalarArgument(op.operand(), parameters);
        }
        if (expr instanceof Ast.BinaryExpr op) {
            return (op.operator().equals("+") || op.operator().equals("-")
                        || op.operator().equals("*"))
                    && safeScalarArgument(op.left(), parameters)
                    && safeScalarArgument(op.right(), parameters);
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            return safeScalarArgument(conditional.condition(), parameters)
                    && safeScalarArgument(conditional.whenTrue(), parameters)
                    && safeScalarArgument(conditional.whenFalse(), parameters);
        }
        return false;
    }

    private static void rejectNested(Object node, String owner) {
        if (node == null) return;
        if (node instanceof Ast.ConstructorDelegateStmt) {
            throw new IllegalArgumentException(
                    "constructor delegation in " + owner
                            + " must be the first top-level constructor statement");
        }
        if (node instanceof Iterable<?> items) {
            for (Object item : items) rejectNested(item, owner);
            return;
        }
        if (!(node instanceof Record record) || node.getClass().getEnclosingClass() != Ast.class) return;
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            try {
                rejectNested(component.getAccessor().invoke(record), owner);
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException("cannot inspect constructor delegation statement", ex);
            }
        }
    }
}
