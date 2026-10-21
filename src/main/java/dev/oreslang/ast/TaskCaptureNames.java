package dev.oreslang.ast;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Syntactic free-name inventory for an already checked Task body.
 *
 * This is intentionally NOT an authorization or a substitute for the
 * ownership checker: it only controls which already-checked parent slots
 * are detached by the runtime at eager Task submission. The walk is
 * lexical-scope aware so Task-local bindings and pattern variables do not
 * cause unrelated parent values with the same name to be copied.
 *
 * Nested deferred bodies are traversed transitively. Unknown future AST
 * constructs fail closed rather than silently making a Task resume with an
 * omitted captured binding.
 */
public final class TaskCaptureNames {
    private TaskCaptureNames() { }

    public static Set<String> referencedIn(List<Ast.Stmt> body) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        visitBody(body, Set.of(), names);
        return Set.copyOf(names);
    }

    private static void visitBody(
            List<Ast.Stmt> body,
            Set<String> inheritedLocals,
            Set<String> names) {
        LinkedHashSet<String> blockLocals = new LinkedHashSet<>(inheritedLocals);
        for (Ast.Stmt stmt : body) {
            visitStmt(stmt, blockLocals, names);
        }
    }

    private static void visitStmt(
            Ast.Stmt stmt,
            Set<String> locals,
            Set<String> names) {
        if (stmt == null) return;

        if (stmt instanceof Ast.BindingStmt s) {
            // Lambda bindings are recursive: the binding name is visible in
            // its own lambda body, but not in an arbitrary non-lambda
            // initializer.
            if (s.initializer() instanceof Ast.LambdaExpr) {
                LinkedHashSet<String> recursive = new LinkedHashSet<>(locals);
                recursive.add(s.name());
                visitExpr(s.initializer(), recursive, names);
            } else {
                visitExpr(s.initializer(), locals, names);
            }
            locals.add(s.name());
        } else if (stmt instanceof Ast.DestructureStmt s) {
            visitExpr(s.initializer(), locals, names);
            for (Ast.DestructureBinding binding : s.bindings()) {
                if (!binding.isDiscard()) locals.add(binding.name());
            }
        } else if (stmt instanceof Ast.ReturnStmt s) {
            visitExpr(s.value(), locals, names);
        } else if (stmt instanceof Ast.YieldStmt s) {
            visitExpr(s.value(), locals, names);
        } else if (stmt instanceof Ast.ExprStmt s) {
            visitExpr(s.expression(), locals, names);
        } else if (stmt instanceof Ast.DeferStmt s) {
            visitExpr(s.expression(), locals, names);
        } else if (stmt instanceof Ast.BlockStmt s) {
            visitBody(s.body(), locals, names);
        } else if (stmt instanceof Ast.IfStmt s) {
            for (Ast.IfBranch branch : s.branches()) {
                visitExpr(branch.condition(), locals, names);
                LinkedHashSet<String> branchLocals = new LinkedHashSet<>(locals);
                collectConditionBindingNames(branch.condition(), branchLocals);
                visitBody(branch.body(), branchLocals, names);
            }
            visitBody(s.elseBody(), locals, names);
        } else if (stmt instanceof Ast.MatchStmt s) {
            visitExpr(s.subject(), locals, names);
            for (Ast.MatchArm arm : s.arms()) {
                LinkedHashSet<String> armLocals = new LinkedHashSet<>(locals);
                collectPatternBindingNames(arm.pattern(), armLocals);
                visitExpr(arm.guard(), armLocals, names);
                visitBody(arm.body(), armLocals, names);
            }
        } else if (stmt instanceof Ast.SwitchStmt s) {
            visitExpr(s.subject(), locals, names);
            for (Ast.SwitchCase arm : s.cases()) {
                for (Ast.Expr value : arm.constants()) {
                    visitExpr(value, locals, names);
                }
                visitBody(arm.body(), locals, names);
            }
            visitBody(s.defaultBody(), locals, names);
        } else if (stmt instanceof Ast.TryStmt s) {
            visitBody(s.body(), locals, names);
            LinkedHashSet<String> caught = new LinkedHashSet<>(locals);
            caught.add(s.errorName());
            visitBody(s.catchBody(), caught, names);
            visitBody(s.finallyBody(), locals, names);
        } else if (stmt instanceof Ast.ForOfStmt s) {
            visitExpr(s.iterable(), locals, names);
            LinkedHashSet<String> loopLocals = new LinkedHashSet<>(locals);
            loopLocals.add(s.bindingName());
            visitBody(s.body(), loopLocals, names);
        } else if (stmt instanceof Ast.ForOfDestructureStmt s) {
            visitExpr(s.iterable(), locals, names);
            LinkedHashSet<String> loopLocals = new LinkedHashSet<>(locals);
            for (Ast.DestructureBinding binding : s.bindings()) {
                if (!binding.isDiscard()) loopLocals.add(binding.name());
            }
            visitBody(s.body(), loopLocals, names);
        } else if (stmt instanceof Ast.ForStmt s) {
            LinkedHashSet<String> loopLocals = new LinkedHashSet<>(locals);
            if (s.initializer() != null) {
                visitStmt(s.initializer(), loopLocals, names);
            }
            visitExpr(s.condition(), loopLocals, names);
            visitExpr(s.update(), loopLocals, names);
            visitBody(s.body(), loopLocals, names);
        } else if (stmt instanceof Ast.DoWhileStmt s) {
            visitBody(s.body(), locals, names);
            visitExpr(s.condition(), locals, names);
        } else if (stmt instanceof Ast.LoopStmt s) {
            visitBody(s.body(), locals, names);
        } else if (stmt instanceof Ast.SelectStmt s) {
            for (Ast.SelectArm arm : s.arms()) {
                visitExpr(arm.channel(), locals, names);
                visitExpr(arm.value(), locals, names);
                LinkedHashSet<String> armLocals = new LinkedHashSet<>(locals);
                if (arm.bindingName() != null) armLocals.add(arm.bindingName());
                visitBody(arm.body(), armLocals, names);
            }
        } else if (!(stmt instanceof Ast.BreakStmt
                || stmt instanceof Ast.ContinueStmt)) {
            throw new IllegalStateException(
                    "unsupported Task capture statement: "
                            + stmt.getClass().getName());
        }
    }

    private static void visitExpr(
            Ast.Expr expr,
            Set<String> locals,
            Set<String> names) {
        if (expr == null || expr instanceof Ast.LiteralExpr) return;

        if (expr instanceof Ast.NameExpr s) {
            if (!locals.contains(s.name())) names.add(s.name());
        } else if (expr instanceof Ast.BinaryExpr s) {
            visitExpr(s.left(), locals, names);
            visitExpr(s.right(), locals, names);
        } else if (expr instanceof Ast.UnaryExpr s) {
            visitExpr(s.operand(), locals, names);
        } else if (expr instanceof Ast.AssignExpr s) {
            visitExpr(s.target(), locals, names);
            visitExpr(s.value(), locals, names);
        } else if (expr instanceof Ast.ConditionalExpr s) {
            visitExpr(s.condition(), locals, names);
            visitExpr(s.whenTrue(), locals, names);
            visitExpr(s.whenFalse(), locals, names);
        } else if (expr instanceof Ast.MatchExpr s) {
            visitExpr(s.subject(), locals, names);
            for (Ast.MatchValueArm arm : s.arms()) {
                LinkedHashSet<String> armLocals = new LinkedHashSet<>(locals);
                collectPatternBindingNames(arm.pattern(), armLocals);
                visitExpr(arm.guard(), armLocals, names);
                visitExpr(arm.value(), armLocals, names);
            }
        } else if (expr instanceof Ast.TypeTestExpr s) {
            visitExpr(s.value(), locals, names);
        } else if (expr instanceof Ast.PatternTestExpr s) {
            visitExpr(s.value(), locals, names);
        } else if (expr instanceof Ast.CastExpr s) {
            visitExpr(s.value(), locals, names);
        } else if (expr instanceof Ast.SpreadExpr s) {
            visitExpr(s.expression(), locals, names);
        } else if (expr instanceof Ast.CallExpr s) {
            visitExpr(s.callee(), locals, names);
            for (Ast.Expr arg : s.arguments()) visitExpr(arg, locals, names);
        } else if (expr instanceof Ast.RuntimeCallExpr s) {
            for (Ast.Expr arg : s.arguments()) visitExpr(arg, locals, names);
        } else if (expr instanceof Ast.MemberExpr s) {
            visitExpr(s.receiver(), locals, names);
        } else if (expr instanceof Ast.IndexExpr s) {
            visitExpr(s.receiver(), locals, names);
            visitExpr(s.index(), locals, names);
        } else if (expr instanceof Ast.NewExpr s) {
            for (Ast.Expr arg : s.arguments()) visitExpr(arg, locals, names);
        } else if (expr instanceof Ast.AwaitExpr s) {
            visitExpr(s.expression(), locals, names);
        } else if (expr instanceof Ast.TaskExpr s) {
            // A nested Task may in turn capture a value from the parent Task.
            // Such a value must already have been detached into the parent
            // Task's environment at the original submission boundary.
            visitBody(s.body(), locals, names);
        } else if (expr instanceof Ast.TrapExpr s) {
            visitExpr(s.expression(), locals, names);
        } else if (expr instanceof Ast.ChannelOpExpr s) {
            visitExpr(s.channel(), locals, names);
            visitExpr(s.value(), locals, names);
            visitBody(s.callbackBody(), locals, names);
        } else if (expr instanceof Ast.DynamicSelectExpr s) {
            visitExpr(s.cases(), locals, names);
        } else if (expr instanceof Ast.ListExpr s) {
            for (Ast.Expr item : s.elements()) visitExpr(item, locals, names);
        } else if (expr instanceof Ast.TupleExpr s) {
            for (Ast.Expr item : s.elements()) visitExpr(item, locals, names);
        } else if (expr instanceof Ast.ObjectExpr s) {
            for (Ast.ObjectField field : s.fields()) {
                visitExpr(field.value(), locals, names);
            }
        } else if (expr instanceof Ast.LambdaExpr s) {
            if (s.nonLexical()) return;
            LinkedHashSet<String> lambdaLocals = new LinkedHashSet<>(locals);
            for (Ast.Param parameter : s.parameters()) {
                lambdaLocals.add(parameter.name());
            }
            lambdaLocals.add("arguments");
            visitExpr(s.expressionBody(), lambdaLocals, names);
            if (s.blockBody() != null) {
                visitBody(s.blockBody(), lambdaLocals, names);
            }
        } else {
            throw new IllegalStateException(
                    "unsupported Task capture expression: "
                            + expr.getClass().getName());
        }
    }

    private static void collectConditionBindingNames(
            Ast.Expr condition,
            Set<String> names) {
        if (condition instanceof Ast.TypeTestExpr test
                && test.binding() != null) {
            names.add(test.binding());
        } else if (condition instanceof Ast.PatternTestExpr test) {
            collectPatternBindingNames(test.pattern(), names);
        } else if (condition instanceof Ast.BinaryExpr binary
                && binary.operator().equals("&&")) {
            collectConditionBindingNames(binary.left(), names);
            collectConditionBindingNames(binary.right(), names);
        }
    }

    private static void collectPatternBindingNames(
            Ast.Pattern pattern,
            Set<String> names) {
        if (pattern instanceof Ast.BindingPattern binding) {
            names.add(binding.name());
        } else if (pattern instanceof Ast.TypePattern typed
                && typed.binding() != null) {
            names.add(typed.binding());
        } else if (pattern instanceof Ast.StructuralPattern structural
                && structural.binding() != null) {
            names.add(structural.binding());
        } else if (pattern instanceof Ast.TuplePattern tuple) {
            for (Ast.Pattern nested : tuple.elements()) {
                collectPatternBindingNames(nested, names);
            }
        } else if (pattern instanceof Ast.ConstructorPattern constructor) {
            for (Ast.Pattern nested : constructor.arguments()) {
                collectPatternBindingNames(nested, names);
            }
        }
    }
}
