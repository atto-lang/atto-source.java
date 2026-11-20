package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fail-closed v1 checker for {@code pure fnc} (docs/PURE.md).
 *
 * <p>A pure body may only use an allowlisted, effect-free subset: literals,
 * locals and parameters, arithmetic/logical/comparison operators, local
 * bindings and assignments to locals, if/match/block/return, value matches,
 * conditional expressions, and direct calls to other {@code pure} functions.
 * Every other construct (I/O, actors, tasks, channels, await, method and host
 * calls, closures, field mutation, ...) is rejected with E_PURE_EFFECT, so the
 * guarantee is transitive without trusting unknown callees.
 */
public final class PureEffectChecker {
    private final Set<String> pureFunctions;
    private final String owner;
    private final Set<String> locals = new HashSet<>();

    private PureEffectChecker(Set<String> pureFunctions, String owner) {
        this.pureFunctions = pureFunctions;
        this.owner = owner;
    }

    /** Verify every pure function; {@code pureFunctions} are all names declared pure. */
    public static void check(List<Ast.FunctionDecl> functions, Set<String> pureFunctions) {
        for (Ast.FunctionDecl fn : functions) {
            if (fn.async() || fn.suspends() || fn.generator()) {
                throw new IllegalArgumentException("E_PURE_EFFECT: pure function '" + fn.name()
                        + "' cannot be async, suspends or a generator");
            }
            PureEffectChecker checker = new PureEffectChecker(pureFunctions, fn.name());
            for (Ast.Param param : fn.parameters()) checker.locals.add(param.name());
            checker.statements(fn.body());
        }
    }

    private IllegalArgumentException reject(String what) {
        return new IllegalArgumentException("E_PURE_EFFECT: pure function '" + owner
                + "' cannot " + what);
    }

    private void statements(List<Ast.Stmt> body) {
        for (Ast.Stmt statement : body) statement(statement);
    }

    private void statement(Ast.Stmt statement) {
        if (statement instanceof Ast.ReturnStmt returned) {
            if (returned.value() != null) expr(returned.value());
        } else if (statement instanceof Ast.BindingStmt binding) {
            if (binding.initializer() != null) expr(binding.initializer());
            locals.add(binding.name());
        } else if (statement instanceof Ast.ExprStmt expression) {
            if (expression.expression() instanceof Ast.AssignExpr assign) {
                if (!(assign.target() instanceof Ast.NameExpr name) || !locals.contains(name.name())) {
                    throw reject("assign to anything but its own locals");
                }
                expr(assign.value());
            } else {
                expr(expression.expression());
            }
        } else if (statement instanceof Ast.IfStmt conditional) {
            for (Ast.IfBranch branch : conditional.branches()) {
                expr(branch.condition());
                statements(branch.body());
            }
            statements(conditional.elseBody());
        } else if (statement instanceof Ast.BlockStmt block) {
            statements(block.body());
        } else if (statement instanceof Ast.MatchStmt matched) {
            expr(matched.subject());
            for (Ast.MatchArm arm : matched.arms()) {
                bindPattern(arm.pattern());
                if (arm.guard() != null) expr(arm.guard());
                statements(arm.body());
            }
        } else {
            throw reject("use statement " + statement.getClass().getSimpleName());
        }
    }

    private void bindPattern(Ast.Pattern pattern) {
        if (pattern instanceof Ast.BindingPattern binding) locals.add(binding.name());
        if (pattern instanceof Ast.TypePattern typed && typed.binding() != null) locals.add(typed.binding());
        if (pattern instanceof Ast.ConstructorPattern constructor) {
            for (Ast.Pattern inner : constructor.arguments()) bindPattern(inner);
        }
    }

    private void expr(Ast.Expr expr) {
        if (expr instanceof Ast.LiteralExpr) return;
        if (expr instanceof Ast.NameExpr name) {
            if (!locals.contains(name.name())) {
                throw reject("read ambient name '" + name.name() + "'");
            }
            return;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            expr(binary.left());
            expr(binary.right());
            return;
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            if (unary.operator().startsWith("&")) throw reject("take loans");
            expr(unary.operand());
            return;
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            expr(conditional.condition());
            expr(conditional.whenTrue());
            expr(conditional.whenFalse());
            return;
        }
        if (expr instanceof Ast.MatchExpr matched) {
            expr(matched.subject());
            for (Ast.MatchValueArm arm : matched.arms()) {
                bindPattern(arm.pattern());
                if (arm.guard() != null) expr(arm.guard());
                statements(arm.prefixStatements());
                expr(arm.value());
            }
            return;
        }
        if (expr instanceof Ast.CallExpr call) {
            if (!(call.callee() instanceof Ast.NameExpr callee)
                    || !pureFunctions.contains(callee.name())
                    || locals.contains(callee.name())) {
                throw reject("call anything but other pure functions");
            }
            for (Ast.Expr argument : call.arguments()) expr(argument);
            return;
        }
        throw reject("use expression " + expr.getClass().getSimpleName());
    }
}
