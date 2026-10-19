package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared, syntax-only free-variable analysis for deferred Task/lambda bodies.
 *
 * <p>This class never grants Send/Sync permission. It only identifies which
 * outer bindings a deferred activation depends on, including whether any use
 * writes through the captured binding. OwnershipChecker and TypeChecker must
 * consume the same capture set so semantic admission cannot omit a value that
 * the affine ownership pass later moves.
 */
final class TaskCaptureAnalysis {
    private TaskCaptureAnalysis() { }

    record Use(String name, boolean write) { }

    static List<Use> lambda(Ast.LambdaExpr lambda, String recursiveBinding) {
        LinkedHashMap<String, Boolean> uses = new LinkedHashMap<>();
        Set<String> locals = new HashSet<>();
        for (Ast.Param param : lambda.parameters()) locals.add(param.name());
        locals.add("arguments");
        if (lambda.expressionBody() != null) {
            scanExpr(lambda.expressionBody(), locals, recursiveBinding, uses, false);
        } else {
            scanStatements(lambda.blockBody(), locals, recursiveBinding, uses);
        }
        return uses.entrySet().stream()
                .map(entry -> new Use(entry.getKey(), entry.getValue()))
                .toList();
    }

    static List<Use> taskBody(List<Ast.Stmt> body) {
        LinkedHashMap<String, Boolean> uses = new LinkedHashMap<>();
        scanStatements(body, Set.of(), null, uses);
        return uses.entrySet().stream()
                .map(entry -> new Use(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static void addUse(
            String name, Set<String> locals, String recursiveBinding,
            Map<String, Boolean> uses, boolean write) {
        if (name == null || name.equals(recursiveBinding) || locals.contains(name)) return;
        uses.merge(name, write, Boolean::logicalOr);
    }

    private static void scanStatements(
            List<Ast.Stmt> statements,
            Set<String> locals,
            String recursiveBinding,
            Map<String, Boolean> uses) {
        Set<String> blockLocals = new HashSet<>(locals);
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) {
                scanExpr(binding.initializer(), blockLocals, recursiveBinding, uses, false);
                blockLocals.add(binding.name());
            } else if (stmt instanceof Ast.DestructureStmt destructure) {
                scanExpr(destructure.initializer(), blockLocals, recursiveBinding, uses, false);
                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    if (!binding.isDiscard()) blockLocals.add(binding.name());
                }
            } else if (stmt instanceof Ast.ReturnStmt ret && ret.value() != null) {
                scanExpr(ret.value(), blockLocals, recursiveBinding, uses, false);
            } else if (stmt instanceof Ast.YieldStmt yielded) {
                scanExpr(yielded.value(), blockLocals, recursiveBinding, uses, false);
            } else if (stmt instanceof Ast.ExprStmt e) {
                scanExpr(e.expression(), blockLocals, recursiveBinding, uses, false);
            } else if (stmt instanceof Ast.DeferStmt e) {
                scanExpr(e.expression(), blockLocals, recursiveBinding, uses, false);
            } else if (stmt instanceof Ast.TaskBlockStmt s) {
                // Nested deferred work contributes transitive outer captures.
                scanStatements(s.body(), blockLocals, recursiveBinding, uses);
            } else if (stmt instanceof Ast.BlockStmt s) {
                scanStatements(s.body(), blockLocals, recursiveBinding, uses);
            } else if (stmt instanceof Ast.LoopStmt s) {
                scanStatements(s.body(), blockLocals, recursiveBinding, uses);
            } else if (stmt instanceof Ast.IfStmt s) {
                for (Ast.IfBranch branch : s.branches()) {
                    scanExpr(branch.condition(), blockLocals, recursiveBinding, uses, false);
                    Set<String> branchLocals = new HashSet<>(blockLocals);
                    collectConditionBindingNames(branch.condition(), branchLocals);
                    scanStatements(branch.body(), branchLocals, recursiveBinding, uses);
                }
                scanStatements(s.elseBody(), blockLocals, recursiveBinding, uses);
            } else if (stmt instanceof Ast.MatchStmt s) {
                scanExpr(s.subject(), blockLocals, recursiveBinding, uses, false);
                for (Ast.MatchArm arm : s.arms()) {
                    Set<String> armLocals = new HashSet<>(blockLocals);
                    collectPatternBindingNames(arm.pattern(), armLocals);
                    if (arm.guard() != null) {
                        scanExpr(arm.guard(), armLocals, recursiveBinding, uses, false);
                    }
                    scanStatements(arm.body(), armLocals, recursiveBinding, uses);
                }
            } else if (stmt instanceof Ast.SwitchStmt s) {
                scanExpr(s.subject(), blockLocals, recursiveBinding, uses, false);
                for (Ast.SwitchCase arm : s.cases()) {
                    for (Ast.Expr constant : arm.constants()) {
                        scanExpr(constant, blockLocals, recursiveBinding, uses, false);
                    }
                    scanStatements(arm.body(), blockLocals, recursiveBinding, uses);
                }
                scanStatements(s.defaultBody(), blockLocals, recursiveBinding, uses);
            } else if (stmt instanceof Ast.TryStmt s) {
                scanStatements(s.body(), blockLocals, recursiveBinding, uses);
                Set<String> caught = new HashSet<>(blockLocals);
                caught.add(s.errorName());
                scanStatements(s.catchBody(), caught, recursiveBinding, uses);
                scanStatements(s.finallyBody(), blockLocals, recursiveBinding, uses);
            } else if (stmt instanceof Ast.SelectStmt s) {
                for (Ast.SelectArm arm : s.arms()) {
                    Set<String> armLocals = new HashSet<>(blockLocals);
                    if (arm.operation() != Ast.ChannelOperation.DEFAULT) {
                        scanExpr(arm.channel(), blockLocals, recursiveBinding, uses, false);
                    }
                    if (arm.operation() == Ast.ChannelOperation.WRITE) {
                        scanExpr(arm.value(), blockLocals, recursiveBinding, uses, false);
                    }
                    if (arm.bindingName() != null) armLocals.add(arm.bindingName());
                    scanStatements(arm.body(), armLocals, recursiveBinding, uses);
                }
            } else if (stmt instanceof Ast.ForOfDestructureStmt s) {
                scanExpr(s.iterable(), blockLocals, recursiveBinding, uses, false);
                Set<String> loop = new HashSet<>(blockLocals);
                for (Ast.DestructureBinding binding : s.bindings()) {
                    if (!binding.isDiscard()) loop.add(binding.name());
                }
                scanStatements(s.body(), loop, recursiveBinding, uses);
            } else if (stmt instanceof Ast.ForOfStmt s) {
                scanExpr(s.iterable(), blockLocals, recursiveBinding, uses, false);
                Set<String> loop = new HashSet<>(blockLocals);
                loop.add(s.bindingName());
                scanStatements(s.body(), loop, recursiveBinding, uses);
            } else if (stmt instanceof Ast.ForStmt s) {
                Set<String> loop = new HashSet<>(blockLocals);
                if (s.initializer() instanceof Ast.BindingStmt binding) {
                    scanExpr(binding.initializer(), loop, recursiveBinding, uses, false);
                    loop.add(binding.name());
                } else if (s.initializer() instanceof Ast.ExprStmt e) {
                    scanExpr(e.expression(), loop, recursiveBinding, uses, false);
                }
                if (s.condition() != null) {
                    scanExpr(s.condition(), loop, recursiveBinding, uses, false);
                }
                if (s.update() != null) {
                    scanExpr(s.update(), loop, recursiveBinding, uses, false);
                }
                scanStatements(s.body(), loop, recursiveBinding, uses);
            }
        }
    }

    private static void scanExpr(
            Ast.Expr expr,
            Set<String> locals,
            String recursiveBinding,
            Map<String, Boolean> uses,
            boolean write) {
        if (expr == null) return;
        if (expr instanceof Ast.NameExpr name) {
            addUse(name.name(), locals, recursiveBinding, uses, write);
            return;
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            scanExpr(assignment.target(), locals, recursiveBinding, uses, true);
            scanExpr(assignment.value(), locals, recursiveBinding, uses, false);
        } else if (expr instanceof Ast.TypeTestExpr e) {
            scanExpr(e.value(), locals, recursiveBinding, uses, false);
        } else if (expr instanceof Ast.PatternTestExpr e) {
            scanExpr(e.value(), locals, recursiveBinding, uses, false);
        } else if (expr instanceof Ast.CastExpr e) {
            scanExpr(e.value(), locals, recursiveBinding, uses, false);
        } else if (expr instanceof Ast.BinaryExpr e) {
            scanExpr(e.left(), locals, recursiveBinding, uses, false);
            scanExpr(e.right(), locals, recursiveBinding, uses, false);
        } else if (expr instanceof Ast.TrapExpr e) {
            scanExpr(e.expression(), locals, recursiveBinding, uses, false);
        } else if (expr instanceof Ast.UnaryExpr e) {
            scanExpr(e.operand(), locals, recursiveBinding, uses, false);
        } else if (expr instanceof Ast.ConditionalExpr e) {
            scanExpr(e.condition(), locals, recursiveBinding, uses, false);
            scanExpr(e.whenTrue(), locals, recursiveBinding, uses, false);
            scanExpr(e.whenFalse(), locals, recursiveBinding, uses, false);
        } else if (expr instanceof Ast.MatchExpr e) {
            scanExpr(e.subject(), locals, recursiveBinding, uses, false);
            for (Ast.MatchValueArm arm : e.arms()) {
                Set<String> armLocals = new HashSet<>(locals);
                collectPatternBindingNames(arm.pattern(), armLocals);
                if (arm.guard() != null) {
                    scanExpr(arm.guard(), armLocals, recursiveBinding, uses, false);
                }
                scanExpr(arm.value(), armLocals, recursiveBinding, uses, false);
            }
        } else if (expr instanceof Ast.RuntimeCallExpr e) {
            for (Ast.Expr arg : e.arguments()) {
                scanExpr(arg, locals, recursiveBinding, uses, false);
            }
        } else if (expr instanceof Ast.CallExpr e) {
            scanExpr(e.callee(), locals, recursiveBinding, uses, false);
            for (Ast.Expr arg : e.arguments()) {
                scanExpr(arg, locals, recursiveBinding, uses, false);
            }
        } else if (expr instanceof Ast.SpreadExpr e) {
            scanExpr(e.expression(), locals, recursiveBinding, uses, false);
        } else if (expr instanceof Ast.MemberExpr e) {
            scanExpr(e.receiver(), locals, recursiveBinding, uses, write);
        } else if (expr instanceof Ast.IndexExpr e) {
            scanExpr(e.receiver(), locals, recursiveBinding, uses, write);
            scanExpr(e.index(), locals, recursiveBinding, uses, false);
        } else if (expr instanceof Ast.NewExpr e) {
            for (Ast.Expr arg : e.arguments()) {
                scanExpr(arg, locals, recursiveBinding, uses, false);
            }
        } else if (expr instanceof Ast.AwaitExpr e) {
            scanExpr(e.expression(), locals, recursiveBinding, uses, false);
        } else if (expr instanceof Ast.ChannelOpExpr e) {
            scanExpr(e.channel(), locals, recursiveBinding, uses, false);
            if (e.value() != null) {
                scanExpr(e.value(), locals, recursiveBinding, uses, false);
            }
            if (e.callback()) {
                scanStatements(e.callbackBody(), locals, recursiveBinding, uses);
            }
        } else if (expr instanceof Ast.DynamicSelectExpr e) {
            scanExpr(e.cases(), locals, recursiveBinding, uses, false);
        } else if (expr instanceof Ast.ListExpr e) {
            for (Ast.Expr item : e.elements()) {
                scanExpr(item, locals, recursiveBinding, uses, false);
            }
        } else if (expr instanceof Ast.TupleExpr e) {
            for (Ast.Expr item : e.elements()) {
                scanExpr(item, locals, recursiveBinding, uses, false);
            }
        } else if (expr instanceof Ast.ObjectExpr e) {
            for (Ast.ObjectField field : e.fields()) {
                scanExpr(field.value(), locals, recursiveBinding, uses, false);
            }
        } else if (expr instanceof Ast.TaskExpr task) {
            scanStatements(task.body(), locals, recursiveBinding, uses);
        } else if (expr instanceof Ast.TaskLambdaExpr taskLambda) {
            scanExpr(taskLambda.lambda(), locals, recursiveBinding, uses, false);
        } else if (expr instanceof Ast.AsyncLambdaExpr asyncLambda) {
            scanExpr(asyncLambda.lambda(), locals, recursiveBinding, uses, false);
        } else if (expr instanceof Ast.SuspendsLambdaExpr suspendsLambda) {
            scanExpr(suspendsLambda.lambda(), locals, recursiveBinding, uses, false);
        } else if (expr instanceof Ast.LambdaExpr lambda) {
            if (lambda.nonLexical()) return;
            Set<String> nestedLocals = new HashSet<>(locals);
            for (Ast.Param parameter : lambda.parameters()) {
                nestedLocals.add(parameter.name());
            }
            nestedLocals.add("arguments");
            if (lambda.expressionBody() != null) {
                scanExpr(
                        lambda.expressionBody(), nestedLocals,
                        recursiveBinding, uses, false);
            } else {
                scanStatements(
                        lambda.blockBody(), nestedLocals,
                        recursiveBinding, uses);
            }
        }
    }

    private static void collectConditionBindingNames(Ast.Expr condition, Set<String> names) {
        if (condition instanceof Ast.TypeTestExpr test && test.binding() != null) {
            names.add(test.binding());
        } else if (condition instanceof Ast.PatternTestExpr test) {
            collectPatternBindingNames(test.pattern(), names);
        } else if (condition instanceof Ast.BinaryExpr binary
                && binary.operator().equals("&&")) {
            collectConditionBindingNames(binary.left(), names);
            collectConditionBindingNames(binary.right(), names);
        }
    }

    private static void collectPatternBindingNames(Ast.Pattern pattern, Set<String> names) {
        if (pattern instanceof Ast.BindingPattern binding) {
            names.add(binding.name());
        } else if (pattern instanceof Ast.TypePattern typed && typed.binding() != null) {
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
