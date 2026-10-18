package dev.oreslang.types;

import dev.oreslang.ast.Ast;

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
        // Iterative DFS, so long constructor chains cannot overflow the Java
        // resolver stack. 'done' also avoids quadratic repeated traversals.
        Set<Integer> done = new HashSet<>();
        for (Ast.ConstructorDecl ctor : klass.constructors()) {
            if (done.contains(ctor.arity())) continue;
            Set<Integer> visiting = new HashSet<>();
            int current = ctor.arity();
            while (!done.contains(current)) {
                if (!visiting.add(current)) {
                    throw new IllegalArgumentException(
                            "constructor delegation cycle in " + klass.name()
                                    + " at arity " + current);
                }
                Integer next = edges.get(current);
                if (next == null) break;
                current = next;
            }
            done.addAll(visiting);
        }
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

    /**
     * Explicit AOT-safe walk. Constructor delegation is a statement; visit
     * every syntactic child that can contain statements, including lambda
     * bodies, registered channel callbacks, cleanup, select arms and matches.
     * Unknown statement/expression shapes fail closed instead of silently
     * permitting nested construction dispatch.
     */
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
        if (node instanceof Ast.LiteralExpr || node instanceof Ast.NameExpr
                || node instanceof Ast.BreakStmt || node instanceof Ast.ContinueStmt
                || node instanceof Ast.Pattern) return;

        if (node instanceof Ast.BinaryExpr item) {
            rejectNested(item.left(), owner);
            rejectNested(item.right(), owner);
        } else if (node instanceof Ast.UnaryExpr item) {
            rejectNested(item.operand(), owner);
        } else if (node instanceof Ast.AssignExpr item) {
            rejectNested(item.target(), owner);
            rejectNested(item.value(), owner);
        } else if (node instanceof Ast.ConditionalExpr item) {
            rejectNested(item.condition(), owner);
            rejectNested(item.whenTrue(), owner);
            rejectNested(item.whenFalse(), owner);
        } else if (node instanceof Ast.MatchExpr item) {
            rejectNested(item.subject(), owner);
            rejectNested(item.arms(), owner);
        } else if (node instanceof Ast.MatchValueArm item) {
            rejectNested(item.guard(), owner);
            rejectNested(item.value(), owner);
        } else if (node instanceof Ast.TypeTestExpr item) {
            rejectNested(item.value(), owner);
        } else if (node instanceof Ast.PatternTestExpr item) {
            rejectNested(item.value(), owner);
        } else if (node instanceof Ast.CastExpr item) {
            rejectNested(item.value(), owner);
        } else if (node instanceof Ast.SpreadExpr item) {
            rejectNested(item.expression(), owner);
        } else if (node instanceof Ast.CallExpr item) {
            rejectNested(item.callee(), owner);
            rejectNested(item.arguments(), owner);
        } else if (node instanceof Ast.RuntimeCallExpr item) {
            rejectNested(item.arguments(), owner);
        } else if (node instanceof Ast.MemberExpr item) {
            rejectNested(item.receiver(), owner);
        } else if (node instanceof Ast.IndexExpr item) {
            rejectNested(item.receiver(), owner);
            rejectNested(item.index(), owner);
        } else if (node instanceof Ast.NewExpr item) {
            rejectNested(item.arguments(), owner);
        } else if (node instanceof Ast.AwaitExpr item) {
            rejectNested(item.expression(), owner);
        } else if (node instanceof Ast.TrapExpr item) {
            rejectNested(item.expression(), owner);
        } else if (node instanceof Ast.ChannelOpExpr item) {
            rejectNested(item.channel(), owner);
            rejectNested(item.value(), owner);
            rejectNested(item.callbackBody(), owner);
        } else if (node instanceof Ast.DynamicSelectExpr item) {
            rejectNested(item.cases(), owner);
        } else if (node instanceof Ast.ListExpr item) {
            rejectNested(item.elements(), owner);
        } else if (node instanceof Ast.TupleExpr item) {
            rejectNested(item.elements(), owner);
        } else if (node instanceof Ast.ObjectExpr item) {
            rejectNested(item.fields(), owner);
        } else if (node instanceof Ast.ObjectField item) {
            rejectNested(item.value(), owner);
        } else if (node instanceof Ast.LambdaExpr item) {
            rejectNested(item.expressionBody(), owner);
            rejectNested(item.blockBody(), owner);
        } else if (node instanceof Ast.BindingStmt item) {
            rejectNested(item.initializer(), owner);
        } else if (node instanceof Ast.DestructureStmt item) {
            rejectNested(item.initializer(), owner);
        } else if (node instanceof Ast.ExprStmt item) {
            rejectNested(item.expression(), owner);
        } else if (node instanceof Ast.ReturnStmt item) {
            rejectNested(item.value(), owner);
        } else if (node instanceof Ast.YieldStmt item) {
            rejectNested(item.value(), owner);
        } else if (node instanceof Ast.DeferStmt item) {
            rejectNested(item.expression(), owner);
        } else if (node instanceof Ast.BlockStmt item) {
            rejectNested(item.body(), owner);
        } else if (node instanceof Ast.IfStmt item) {
            rejectNested(item.branches(), owner);
            rejectNested(item.elseBody(), owner);
        } else if (node instanceof Ast.IfBranch item) {
            rejectNested(item.condition(), owner);
            rejectNested(item.body(), owner);
        } else if (node instanceof Ast.MatchStmt item) {
            rejectNested(item.subject(), owner);
            rejectNested(item.arms(), owner);
        } else if (node instanceof Ast.MatchArm item) {
            rejectNested(item.guard(), owner);
            rejectNested(item.body(), owner);
        } else if (node instanceof Ast.SwitchStmt item) {
            rejectNested(item.subject(), owner);
            rejectNested(item.cases(), owner);
            rejectNested(item.defaultBody(), owner);
        } else if (node instanceof Ast.SwitchCase item) {
            rejectNested(item.constants(), owner);
            rejectNested(item.body(), owner);
        } else if (node instanceof Ast.TryStmt item) {
            rejectNested(item.body(), owner);
            rejectNested(item.catchBody(), owner);
            rejectNested(item.finallyBody(), owner);
        } else if (node instanceof Ast.ForOfStmt item) {
            rejectNested(item.iterable(), owner);
            rejectNested(item.body(), owner);
        } else if (node instanceof Ast.ForOfDestructureStmt item) {
            rejectNested(item.iterable(), owner);
            rejectNested(item.body(), owner);
        } else if (node instanceof Ast.ForStmt item) {
            rejectNested(item.initializer(), owner);
            rejectNested(item.condition(), owner);
            rejectNested(item.update(), owner);
            rejectNested(item.body(), owner);
        } else if (node instanceof Ast.LoopStmt item) {
            rejectNested(item.body(), owner);
        } else if (node instanceof Ast.SelectStmt item) {
            rejectNested(item.arms(), owner);
        } else if (node instanceof Ast.SelectArm item) {
            rejectNested(item.channel(), owner);
            rejectNested(item.value(), owner);
            rejectNested(item.body(), owner);
        } else if (node instanceof Ast.Expr || node instanceof Ast.Stmt) {
            throw new IllegalArgumentException(
                    "unhandled constructor delegation AST node: " + node.getClass().getName());
        }
    }
}
