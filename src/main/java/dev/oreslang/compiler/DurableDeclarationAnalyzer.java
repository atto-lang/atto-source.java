package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.TypeChecker;
import dev.oreslang.types.OwnershipChecker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * First semantic admission phase for class-shaped durable declarations.
 *
 * Activity member bodies are ordinary typed Java-host boundary implementations,
 * but durable dispatch remains closed. Workflow member bodies are restricted
 * to deterministic language computation and compiler-owned command nodes.
 */
public final class DurableDeclarationAnalyzer {
    private static final Set<String> FORBIDDEN_WORKFLOW_CALLS = Set.of(
            "print", "sleep", "random", "now", "clock", "read", "write",
            "open", "fetch", "http", "execute", "spawn", "send", "receive",
            "readch", "writech", "select", "system", "process");

    private DurableDeclarationAnalyzer() { }

    public static DurableExecutionManifest analyze(Ast.Program program) {
        java.util.Objects.requireNonNull(program, "durable declaration program");
        List<Ast.ModuleDecl> rewrittenModules = new ArrayList<>();
        boolean found = false;
        for (Ast.ModuleDecl module : program.modules()) {
            List<Ast.Decl> declarations = new ArrayList<>();
            Set<String> localDurableNames = new HashSet<>();
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ActivityDecl activity) {
                    found = true;
                    validateActivity(activity, module.name());
                    if (!localDurableNames.add(activity.name())) {
                        throw new IllegalArgumentException("duplicate durable declaration '" + module.name() + "." + activity.name() + "'");
                    }
                    declarations.add(activity.body());
                } else if (declaration instanceof Ast.WorkflowDecl workflow) {
                    found = true;
                    validateWorkflow(workflow, module.name());
                    if (!localDurableNames.add(workflow.name())) {
                        throw new IllegalArgumentException("duplicate durable declaration '" + module.name() + "." + workflow.name() + "'");
                    }
                    declarations.add(workflow.body());
                } else {
                    declarations.add(declaration);
                }
            }
            rewrittenModules.add(new Ast.ModuleDecl(
                    module.name(), module.annotations(), module.contracts(), declarations));
        }
        if (!found) return DurableExecutionManifest.empty();
        validateCommandTargets(program);
        // Re-enter the normal checker using the exact class bodies, not wrappers.
        // This validates every activity/workflow member's parameter, return,
        // expression and control-flow types through the ordinary class pipeline.
        TypeChecker.TypeCheckResult memberChecks = TypeChecker.checkTypesWithDiagnostics(
                new Ast.Program(program.namespace(), program.imports(), rewrittenModules));
        OwnershipChecker.check(memberChecks.program(), memberChecks.shortInferredAliases());
        return DurableExecutionManifest.fromValidated(program);
    }

    private static void validateActivity(Ast.ActivityDecl activity, String module) {
        Ast.ClassDecl body = activity.body();
        requireStatelessContainer(body, "activity", activity.name());
        if (!activity.annotations().isEmpty()) {
            throw new IllegalArgumentException(
                    "ACTIVITY_ANNOTATIONS_UNSUPPORTED: durable annotation contracts are not yet versioned");
        }
        if (body.methods().isEmpty()) {
            throw new IllegalArgumentException(
                    "ACTIVITY_MEMBERS_REQUIRED: '" + module + "." + activity.name()
                            + "' must declare at least one external operation");
        }
        Set<String> identities = new HashSet<>();
        for (Ast.MethodDecl method : body.methods()) {
            String identity = method.name() + "#" + method.parameters().size();
            if (!identities.add(identity)) {
                throw new IllegalArgumentException(
                        "ACTIVITY_DUPLICATE_OPERATION: duplicate operation " + identity);
            }
            if (method.isAbstract() || method.genericParameters().size() > 0
                    || method.isStatic()) {
                throw new IllegalArgumentException(
                        "ACTIVITY_OPERATION_SHAPE_UNSUPPORTED: operation '" + method.name()
                                + "' must be a concrete, non-generic instance method");
            }
            if (!method.effects().isEmpty()) {
                throw new IllegalArgumentException(
                        "ACTIVITY_CHECKED_EFFECTS_UNSUPPORTED: checked error rows need the durable activity result ABI");
            }
        }
    }

    private static void validateWorkflow(Ast.WorkflowDecl workflow, String module) {
        Ast.ClassDecl body = workflow.body();
        requireStatelessContainer(body, "workflow", workflow.name());
        if (!workflow.annotations().isEmpty()) {
            throw new IllegalArgumentException(
                    "WORKFLOW_ANNOTATIONS_UNSUPPORTED: durable annotation contracts are not yet versioned");
        }
        long runCount = body.methods().stream().filter(m -> m.name().equals("run")).count();
        if (runCount != 1) {
            throw new IllegalArgumentException(
                    "WORKFLOW_ENTRYPOINT_REQUIRED: '" + module + "." + workflow.name()
                            + "' must declare exactly one run(...) entry point");
        }
        Set<String> memberNames = new LinkedHashSet<>();
        for (Ast.MethodDecl method : body.methods()) memberNames.add(method.name());
        for (Ast.MethodDecl method : body.methods()) {
            if (method.isAbstract() || method.async() || method.suspends()
                    || !method.genericParameters().isEmpty() || !method.effects().isEmpty()) {
                throw new IllegalArgumentException(
                        "WORKFLOW_MEMBER_EFFECT_UNSUPPORTED: workflow member '" + method.name()
                                + "' must be a concrete, non-suspending, non-generic deterministic fnc");
            }
            checkStatements(method.body(), memberNames);
        }
        Set<String> commandIds = new HashSet<>();
        for (Ast.WorkflowCommand command : workflow.commands()) {
            if (command instanceof Ast.ActivityStepCommand c) c.arguments().forEach(e -> checkExpr(e, memberNames));
            else if (command instanceof Ast.TimerCommand c) checkExpr(c.delay(), memberNames);
            else if (command instanceof Ast.SignalCommand c) c.arguments().forEach(e -> checkExpr(e, memberNames));
            else if (command instanceof Ast.UpdateCommand c) c.arguments().forEach(e -> checkExpr(e, memberNames));
            else if (command instanceof Ast.ChildWorkflowCommand c) checkExpr(c.input(), memberNames);
            else if (command instanceof Ast.ContinueAsNewCommand c) checkExpr(c.input(), memberNames);
            else if (command instanceof Ast.CompensationCommand c) c.arguments().forEach(e -> checkExpr(e, memberNames));
            String id = commandId(command);
            if (id != null && !commandIds.add(id)) {
                throw new IllegalArgumentException(
                        "WORKFLOW_DUPLICATE_COMMAND_ID: duplicate stable command ID '" + id + "'");
            }
        }
    }

    private static void requireStatelessContainer(
            Ast.ClassDecl body, String kind, String name) {
        if (body.actorKind() != Ast.ActorKind.NONE || body.isAbstract()
                || !body.genericParameters().isEmpty() || !body.parents().isEmpty()
                || !body.interfaces().isEmpty() || !body.fields().isEmpty()
                || body.constructor() != null) {
            throw new IllegalArgumentException(
                    kind.toUpperCase() + "_STATE_SCHEMA_UNSUPPORTED: '" + name
                            + "' currently requires an explicit versioned state-schema lowering; "
                            + "live class fields, constructors, inheritance and generic containers are denied");
        }
    }

    private static void validateCommandTargets(Ast.Program program) {
        java.util.Map<String, Ast.ActivityDecl> activities = new java.util.LinkedHashMap<>();
        java.util.Map<String, Ast.WorkflowDecl> workflows = new java.util.LinkedHashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ActivityDecl activity) {
                    activities.put(module.name() + "." + activity.name(), activity);
                    activities.putIfAbsent(activity.name(), activity);
                } else if (declaration instanceof Ast.WorkflowDecl workflow) {
                    workflows.put(module.name() + "." + workflow.name(), workflow);
                    workflows.putIfAbsent(workflow.name(), workflow);
                }
            }
        }
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.WorkflowDecl workflow)) continue;
                Set<String> endpoints = new HashSet<>();
                workflow.body().methods().forEach(m -> endpoints.add(m.name()));
                for (Ast.WorkflowCommand command : workflow.commands()) {
                    if (command instanceof Ast.ActivityStepCommand step) {
                        requireActivityOperation(activities, step.activityName(), step.operation(),
                                step.arguments().size(), "activity step");
                    } else if (command instanceof Ast.CompensationCommand compensation) {
                        requireActivityOperation(activities, compensation.activityName(), compensation.operation(),
                                compensation.arguments().size(), "compensation");
                    } else if (command instanceof Ast.ChildWorkflowCommand child) {
                        if (!workflows.containsKey(child.workflowName())
                                && !workflows.containsKey(module.name() + "." + child.workflowName())) {
                            throw new IllegalArgumentException(
                                    "WORKFLOW_CHILD_TARGET_UNKNOWN: '" + child.workflowName() + "' is not a declared workflow");
                        }
                    } else if (command instanceof Ast.SignalCommand signal) {
                        requireEndpoint(endpoints, signal.endpoint(), "signal");
                    } else if (command instanceof Ast.QueryCommand query) {
                        requireEndpoint(endpoints, query.endpoint(), "query");
                    } else if (command instanceof Ast.UpdateCommand update) {
                        requireEndpoint(endpoints, update.endpoint(), "update");
                    }
                }
            }
        }
    }

    private static void requireActivityOperation(
            java.util.Map<String, Ast.ActivityDecl> activities,
            String activityName, String operation, int arity, String context) {
        Ast.ActivityDecl activity = activities.get(activityName);
        if (activity == null) {
            throw new IllegalArgumentException(
                    "WORKFLOW_ACTIVITY_TARGET_UNKNOWN: " + context + " references undeclared activity '" + activityName + "'");
        }
        boolean found = activity.body().methods().stream().anyMatch(method ->
                method.name().equals(operation) && method.parameters().size() == arity);
        if (!found) {
            throw new IllegalArgumentException(
                    "WORKFLOW_ACTIVITY_OPERATION_UNKNOWN: " + context + " references missing operation '"
                            + activityName + "." + operation + "#" + arity + "'");
        }
    }

    private static void requireEndpoint(Set<String> endpoints, String name, String kind) {
        if (!endpoints.contains(name)) {
            throw new IllegalArgumentException(
                    "WORKFLOW_" + kind.toUpperCase() + "_ENDPOINT_UNKNOWN: no workflow member '" + name + "'");
        }
    }

    private static String commandId(Ast.WorkflowCommand command) {
        if (command instanceof Ast.ActivityStepCommand c) return c.commandId();
        if (command instanceof Ast.TimerCommand c) return c.commandId();
        if (command instanceof Ast.WaitForEventCommand c) return c.commandId();
        if (command instanceof Ast.SignalCommand c) return c.commandId();
        if (command instanceof Ast.UpdateCommand c) return c.commandId();
        if (command instanceof Ast.ChildWorkflowCommand c) return c.commandId();
        if (command instanceof Ast.ContinueAsNewCommand c) return c.commandId();
        if (command instanceof Ast.CompensationCommand c) return c.commandId();
        return null; // Queries are read-only and do not append history.
    }

    private static void checkStatements(List<Ast.Stmt> statements, Set<String> members) {
        for (Ast.Stmt statement : statements) {
            if (statement instanceof Ast.BindingStmt s) checkExpr(s.initializer(), members);
            else if (statement instanceof Ast.DestructureStmt s) checkExpr(s.initializer(), members);
            else if (statement instanceof Ast.ReturnStmt s) checkExpr(s.value(), members);
            else if (statement instanceof Ast.YieldStmt) {
                throw new IllegalArgumentException("WORKFLOW_YIELD_FORBIDDEN: generators are not replayable workflow members");
            } else if (statement instanceof Ast.ExprStmt s) checkExpr(s.expression(), members);
            else if (statement instanceof Ast.DeferStmt s) checkExpr(s.expression(), members);
            else if (statement instanceof Ast.BlockStmt s) checkStatements(s.body(), members);
            else if (statement instanceof Ast.IfStmt s) {
                for (Ast.IfBranch branch : s.branches()) {
                    checkExpr(branch.condition(), members);
                    checkStatements(branch.body(), members);
                }
                checkStatements(s.elseBody(), members);
            } else if (statement instanceof Ast.MatchStmt s) {
                checkExpr(s.subject(), members);
                for (Ast.MatchArm arm : s.arms()) {
                    checkExpr(arm.guard(), members);
                    checkStatements(arm.body(), members);
                }
            } else if (statement instanceof Ast.SwitchStmt s) {
                checkExpr(s.subject(), members);
                for (Ast.SwitchCase arm : s.cases()) {
                    arm.constants().forEach(e -> checkExpr(e, members));
                    checkStatements(arm.body(), members);
                }
                checkStatements(s.defaultBody(), members);
            } else if (statement instanceof Ast.TryStmt s) {
                checkStatements(s.body(), members);
                checkStatements(s.catchBody(), members);
                checkStatements(s.finallyBody(), members);
            } else if (statement instanceof Ast.ForStmt s) {
                if (s.initializer() != null) checkStatements(List.of(s.initializer()), members);
                checkExpr(s.condition(), members);
                checkExpr(s.update(), members);
                checkStatements(s.body(), members);
            } else if (statement instanceof Ast.ForOfStmt s) {
                if (s.asyncIteration()) throw new IllegalArgumentException("WORKFLOW_ASYNC_ITERATION_FORBIDDEN");
                checkExpr(s.iterable(), members);
                checkStatements(s.body(), members);
            } else if (statement instanceof Ast.ForOfDestructureStmt s) {
                if (s.asyncIteration()) throw new IllegalArgumentException("WORKFLOW_ASYNC_ITERATION_FORBIDDEN");
                checkExpr(s.iterable(), members);
                checkStatements(s.body(), members);
            } else if (statement instanceof Ast.DoWhileStmt s) {
                checkStatements(s.body(), members);
                checkExpr(s.condition(), members);
            } else if (statement instanceof Ast.LoopStmt s) {
                checkStatements(s.body(), members);
            } else if (statement instanceof Ast.TaskScopeStmt || statement instanceof Ast.UsingBorrowStmt
                    || statement instanceof Ast.SelectStmt) {
                throw new IllegalArgumentException(
                        "WORKFLOW_CONCURRENCY_FORBIDDEN: use typed durable command nodes, not runtime task/channel suspension");
            }
        }
    }

    private static void checkExpr(Ast.Expr expression, Set<String> members) {
        if (expression == null) return;
        if (expression instanceof Ast.LiteralExpr || expression instanceof Ast.NameExpr) return;
        if (expression instanceof Ast.RuntimeCallExpr || expression instanceof Ast.AwaitExpr
                || expression instanceof Ast.TaskExpr || expression instanceof Ast.ChannelOpExpr
                || expression instanceof Ast.DynamicSelectExpr || expression instanceof Ast.LambdaExpr
                || expression instanceof Ast.NewExpr) {
            throw new IllegalArgumentException(
                    "WORKFLOW_UNRECORDED_EFFECT_FORBIDDEN: runtime, task, channel, allocation, closure and await expressions are not replayable");
        }
        if (expression instanceof Ast.CallExpr call) {
            if (!(call.callee() instanceof Ast.NameExpr name)
                    || FORBIDDEN_WORKFLOW_CALLS.contains(name.name())
                    || !members.contains(name.name())) {
                throw new IllegalArgumentException(
                        "WORKFLOW_UNRECORDED_CALL_FORBIDDEN: calls from workflow code must target a declared deterministic workflow helper");
            }
            call.arguments().forEach(arg -> checkExpr(arg, members));
            return;
        }
        if (expression instanceof Ast.MemberExpr || expression instanceof Ast.IndexExpr
                || expression instanceof Ast.CastExpr || expression instanceof Ast.TypeTestExpr
                || expression instanceof Ast.PatternTestExpr || expression instanceof Ast.MatchExpr) {
            throw new IllegalArgumentException(
                    "WORKFLOW_DYNAMIC_OR_UNCHECKED_EXPRESSION_UNSUPPORTED: expression requires explicit replay-safe lowering");
        }
        if (expression instanceof Ast.UnaryExpr e) checkExpr(e.operand(), members);
        else if (expression instanceof Ast.BinaryExpr e) {
            checkExpr(e.left(), members); checkExpr(e.right(), members);
        } else if (expression instanceof Ast.AssignExpr e) {
            checkExpr(e.target(), members); checkExpr(e.value(), members);
        } else if (expression instanceof Ast.ConditionalExpr e) {
            checkExpr(e.condition(), members); checkExpr(e.whenTrue(), members); checkExpr(e.whenFalse(), members);
        } else if (expression instanceof Ast.ListExpr e) e.elements().forEach(x -> checkExpr(x, members));
        else if (expression instanceof Ast.TupleExpr e) e.elements().forEach(x -> checkExpr(x, members));
        else if (expression instanceof Ast.ObjectExpr e) {
            if (e.kind() == Ast.ObjectKind.EXPLICIT_STRUCT) throw new IllegalArgumentException("WORKFLOW_EXPLICIT_STRUCT_STATE_UNSUPPORTED");
            e.fields().forEach(field -> checkExpr(field.value(), members));
        } else if (expression instanceof Ast.SpreadExpr e) checkExpr(e.expression(), members);
        else if (expression instanceof Ast.TrapExpr e) checkExpr(e.expression(), members);
        else {
            throw new IllegalArgumentException(
                    "WORKFLOW_UNKNOWN_AST_NODE_FORBIDDEN: " + expression.getClass().getSimpleName());
        }
    }
}
