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
                    declarations.add(withCommandPayloadValidation(
                            program, module, workflow));
                } else {
                    declarations.add(declaration);
                }
            }
            rewrittenModules.add(new Ast.ModuleDecl(
                    module.name(), module.annotations(), module.contracts(), declarations));
        }
        if (!found) return DurableExecutionManifest.empty();
        validateCommandTargets(program);
        // Re-enter the normal checker using exact class bodies plus synthetic
        // checker-only methods that bind each recorded command payload to the
        // target member's declared parameter types. No synthetic method is
        // returned in the caller's AST or executable program.
        TypeChecker.TypeCheckResult memberChecks = TypeChecker.checkTypesWithDiagnostics(
                new Ast.Program(program.namespace(), program.imports(), rewrittenModules));
        OwnershipChecker.check(memberChecks.program(), memberChecks.shortInferredAliases());
        return DurableExecutionManifest.fromValidated(program);
    }

    /**
     * Command payload expressions live in WorkflowDecl rather than in a
     * MethodDecl body. Re-home each payload into a synthetic, non-executable
     * checker-only method with the exact run parameters and destination types.
     * This makes the existing checker validate lexical names, expression types,
     * and overload resolution without introducing a guest/runtime fallback.
     */
    private static Ast.ClassDecl withCommandPayloadValidation(
            Ast.Program program, Ast.ModuleDecl module, Ast.WorkflowDecl workflow) {
        Ast.MethodDecl run = workflow.body().methods().stream()
                .filter(method -> method.name().equals("run"))
                .findFirst().orElseThrow();
        List<Ast.Stmt> checks = new ArrayList<>();
        int ordinal = 0;
        for (Ast.WorkflowCommand command : workflow.commands()) {
            if (command instanceof Ast.ActivityStepCommand step) {
                Ast.ActivityDecl activity = resolveActivityForPayload(
                        program, module.name(), step.activityName());
                Ast.MethodDecl operation = findActivityOperation(
                        activity, step.operation(), step.arguments().size(), "activity step");
                ordinal = appendPayloadChecks(checks, operation.parameters(),
                        step.arguments(), ordinal, "activity step " + step.activityName() + "." + step.operation());
            } else if (command instanceof Ast.CompensationCommand compensation) {
                Ast.ActivityDecl activity = resolveActivityForPayload(
                        program, module.name(), compensation.activityName());
                Ast.MethodDecl operation = findActivityOperation(
                        activity, compensation.operation(), compensation.arguments().size(), "compensation");
                ordinal = appendPayloadChecks(checks, operation.parameters(),
                        compensation.arguments(), ordinal,
                        "compensation " + compensation.activityName() + "." + compensation.operation());
            } else if (command instanceof Ast.SignalCommand signal) {
                Ast.MethodDecl endpoint = findWorkflowEndpoint(
                        workflow, signal.endpoint(), signal.arguments().size(), "signal");
                ordinal = appendPayloadChecks(checks, endpoint.parameters(), signal.arguments(),
                        ordinal, "signal " + signal.endpoint());
            } else if (command instanceof Ast.UpdateCommand update) {
                Ast.MethodDecl endpoint = findWorkflowEndpoint(
                        workflow, update.endpoint(), update.arguments().size(), "update");
                ordinal = appendPayloadChecks(checks, endpoint.parameters(), update.arguments(),
                        ordinal, "update " + update.endpoint());
            } else if (command instanceof Ast.QueryCommand query) {
                Ast.MethodDecl endpoint = findWorkflowEndpoint(
                        workflow, query.endpoint(), query.parameters().size(), "query");
                requireSameSignature(endpoint, query.parameters(), query.returnType(), query.endpoint());
            } else if (command instanceof Ast.ChildWorkflowCommand child) {
                Ast.WorkflowDecl target = resolveWorkflowForPayload(
                        program, module.name(), child.workflowName());
                Ast.MethodDecl childRun = target.body().methods().stream()
                        .filter(method -> method.name().equals("run")).findFirst().orElseThrow();
                if (childRun.parameters().size() != 1) {
                    throw new IllegalArgumentException(
                            "WORKFLOW_CHILD_INPUT_ARITY_UNSUPPORTED: child workflow '"
                                    + child.workflowName() + "' must have exactly one run input for v1");
                }
                ordinal = appendPayloadChecks(checks, childRun.parameters(),
                        List.of(child.input()), ordinal, "child workflow " + child.workflowName());
            } else if (command instanceof Ast.ContinueAsNewCommand next) {
                if (run.parameters().size() != 1) {
                    throw new IllegalArgumentException(
                            "WORKFLOW_CONTINUE_INPUT_ARITY_UNSUPPORTED: continue-as-new requires exactly one run input for v1");
                }
                ordinal = appendPayloadChecks(checks, run.parameters(),
                        List.of(next.input()), ordinal, "continue-as-new");
            } else if (command instanceof Ast.TimerCommand timer) {
                ordinal = appendPayloadChecks(checks,
                        List.of(new Ast.Param(Ast.TypeRef.simple("int"), "delay_ms")),
                        List.of(timer.delay()), ordinal, "timer delay in integer milliseconds");
            }
        }
        if (checks.isEmpty()) return workflow.body();
        checks.add(new Ast.ReturnStmt(null));

        Set<String> occupied = new HashSet<>();
        workflow.body().methods().forEach(method -> occupied.add(method.name()));
        String name = "__atto_durable_command_payloads";
        int suffix = 0;
        while (occupied.contains(name)) name = "__atto_durable_command_payloads_" + (++suffix);

        Ast.MethodDecl validationMethod = new Ast.MethodDecl(
                name,
                Ast.Visibility.PRIVATE,
                false,
                false,
                false,
                false,
                false,
                null,
                List.of(),
                run.parameters(),
                Ast.TypeRef.simple("void"),
                List.of(),
                checks,
                Ast.EffectSpec.none());
        List<Ast.MethodDecl> methods = new ArrayList<>(workflow.body().methods());
        methods.add(validationMethod);
        Ast.ClassDecl body = workflow.body();
        return new Ast.ClassDecl(
                body.name(), body.visibility(), body.isAbstract(), body.actorKind(),
                body.genericParameters(), body.parents(), body.interfaces(), body.fields(),
                body.constructor(), methods, body.actorConcurrency());
    }

    private static int appendPayloadChecks(
            List<Ast.Stmt> checks,
            List<Ast.Param> parameters,
            List<Ast.Expr> arguments,
            int ordinal,
            String context) {
        if (parameters.size() != arguments.size()) {
            throw new IllegalArgumentException(
                    "WORKFLOW_COMMAND_ARGUMENT_ARITY: " + context + " expects "
                            + parameters.size() + " arguments but received " + arguments.size());
        }
        for (int index = 0; index < arguments.size(); index++) {
            Ast.Param parameter = parameters.get(index);
            String binding = "__atto_durable_arg_" + ordinal++;
            checks.add(new Ast.BindingStmt(
                    Ast.BindingKind.CONST,
                    parameter.type(),
                    binding,
                    arguments.get(index),
                    false));
        }
        return ordinal;
    }

    private static void requireSameSignature(
            Ast.MethodDecl endpoint,
            List<Ast.Param> parameters,
            Ast.TypeRef returnType,
            String name) {
        List<Ast.TypeRef> actual = endpoint.parameters().stream().map(Ast.Param::type).toList();
        List<Ast.TypeRef> declared = parameters.stream().map(Ast.Param::type).toList();
        if (!actual.equals(declared) || !java.util.Objects.equals(endpoint.returnType(), returnType)) {
            throw new IllegalArgumentException(
                    "WORKFLOW_QUERY_SIGNATURE_MISMATCH: query endpoint '" + name
                            + "' does not match its method parameter/return signature");
        }
    }

    private static Ast.MethodDecl findWorkflowEndpoint(
            Ast.WorkflowDecl workflow, String name, int arity, String kind) {
        Ast.MethodDecl found = null;
        for (Ast.MethodDecl method : workflow.body().methods()) {
            if (!method.name().equals(name) || method.parameters().size() != arity) continue;
            if (found != null) {
                throw new IllegalArgumentException(
                        "WORKFLOW_ENDPOINT_AMBIGUOUS: " + kind + " endpoint '" + name + "#" + arity + "'");
            }
            found = method;
        }
        if (found == null) {
            throw new IllegalArgumentException(
                    "WORKFLOW_" + kind.toUpperCase() + "_ENDPOINT_UNKNOWN: no workflow member '"
                            + name + "#" + arity + "'");
        }
        return found;
    }

    private static Ast.MethodDecl findActivityOperation(
            Ast.ActivityDecl activity, String operation, int arity, String context) {
        Ast.MethodDecl found = null;
        for (Ast.MethodDecl method : activity.body().methods()) {
            if (!method.name().equals(operation) || method.parameters().size() != arity) continue;
            if (found != null) {
                throw new IllegalArgumentException(
                        "WORKFLOW_ACTIVITY_OPERATION_AMBIGUOUS: " + operation + "#" + arity);
            }
            found = method;
        }
        if (found == null) {
            throw new IllegalArgumentException(
                    "WORKFLOW_ACTIVITY_OPERATION_UNKNOWN: " + context + " references missing operation '"
                            + activity.name() + "." + operation + "#" + arity + "'");
        }
        return found;
    }

    private static Ast.ActivityDecl resolveActivityForPayload(
            Ast.Program program, String currentModule, String requested) {
        List<Ast.ActivityDecl> exact = new ArrayList<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.ActivityDecl activity)) continue;
                String qualified = module.name() + "." + activity.name();
                if (requested.equals(qualified)
                        || (!requested.contains(".") && module.name().equals(currentModule)
                                && requested.equals(activity.name()))) {
                    exact.add(activity);
                }
            }
        }
        if (exact.size() == 1) return exact.getFirst();
        if (requested.contains(".")) {
            throw new IllegalArgumentException("WORKFLOW_ACTIVITY_TARGET_UNKNOWN: " + requested);
        }
        if (exact.isEmpty()) {
            for (Ast.ModuleDecl module : program.modules()) {
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.ActivityDecl activity
                            && activity.name().equals(requested)) exact.add(activity);
                }
            }
        }
        if (exact.size() > 1) throw new IllegalArgumentException(
                "WORKFLOW_ACTIVITY_TARGET_AMBIGUOUS: '" + requested + "' requires a qualified module name");
        if (exact.isEmpty()) throw new IllegalArgumentException(
                "WORKFLOW_ACTIVITY_TARGET_UNKNOWN: " + requested);
        return exact.getFirst();
    }

    private static Ast.WorkflowDecl resolveWorkflowForPayload(
            Ast.Program program, String currentModule, String requested) {
        List<Ast.WorkflowDecl> exact = new ArrayList<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.WorkflowDecl workflow)) continue;
                String qualified = module.name() + "." + workflow.name();
                if (requested.equals(qualified)
                        || (!requested.contains(".") && module.name().equals(currentModule)
                                && requested.equals(workflow.name()))) {
                    exact.add(workflow);
                }
            }
        }
        if (exact.size() == 1) return exact.getFirst();
        if (requested.contains(".")) {
            throw new IllegalArgumentException("WORKFLOW_CHILD_TARGET_UNKNOWN: " + requested);
        }
        if (exact.isEmpty()) {
            for (Ast.ModuleDecl module : program.modules()) {
                for (Ast.Decl declaration : module.declarations()) {
                    if (declaration instanceof Ast.WorkflowDecl workflow
                            && workflow.name().equals(requested)) exact.add(workflow);
                }
            }
        }
        if (exact.size() > 1) throw new IllegalArgumentException(
                "WORKFLOW_CHILD_TARGET_AMBIGUOUS: '" + requested + "' requires a qualified module name");
        if (exact.isEmpty()) throw new IllegalArgumentException(
                "WORKFLOW_CHILD_TARGET_UNKNOWN: " + requested);
        return exact.getFirst();
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
        List<Ast.MethodDecl> entries = body.methods().stream()
                .filter(m -> m.name().equals("run")).toList();
        if (entries.size() != 1) {
            throw new IllegalArgumentException(
                    "WORKFLOW_ENTRYPOINT_REQUIRED: '" + module + "." + workflow.name()
                            + "' must declare exactly one run(...) entry point");
        }
        if (entries.getFirst().isStatic()) {
            throw new IllegalArgumentException(
                    "WORKFLOW_ENTRYPOINT_STATIC: workflow run must be an instance entry point");
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
        java.util.Map<String, java.util.List<String>> activitySimpleNames = new java.util.LinkedHashMap<>();
        java.util.Map<String, java.util.List<String>> workflowSimpleNames = new java.util.LinkedHashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ActivityDecl activity) {
                    String qualified = module.name() + "." + activity.name();
                    if (activities.putIfAbsent(qualified, activity) != null) {
                        throw new IllegalArgumentException("WORKFLOW_DUPLICATE_ACTIVITY: duplicate activity '" + qualified + "'");
                    }
                    activitySimpleNames.computeIfAbsent(activity.name(), ignored -> new ArrayList<>()).add(qualified);
                } else if (declaration instanceof Ast.WorkflowDecl workflow) {
                    String qualified = module.name() + "." + workflow.name();
                    if (workflows.putIfAbsent(qualified, workflow) != null) {
                        throw new IllegalArgumentException("WORKFLOW_DUPLICATE_WORKFLOW: duplicate workflow '" + qualified + "'");
                    }
                    workflowSimpleNames.computeIfAbsent(workflow.name(), ignored -> new ArrayList<>()).add(qualified);
                }
            }
        }

        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.WorkflowDecl workflow)) continue;
                java.util.Map<String, Ast.MethodDecl> endpoints = new java.util.LinkedHashMap<>();
                for (Ast.MethodDecl method : workflow.body().methods()) {
                    String key = method.name() + "#" + method.parameters().size();
                    if (endpoints.putIfAbsent(key, method) != null) {
                        throw new IllegalArgumentException(
                                "WORKFLOW_DUPLICATE_ENDPOINT: duplicate workflow endpoint '" + key + "'");
                    }
                }
                for (Ast.WorkflowCommand command : workflow.commands()) {
                    if (command instanceof Ast.ActivityStepCommand step) {
                        Ast.ActivityDecl target = resolveActivity(
                                activities, activitySimpleNames, module.name(), step.activityName());
                        requireActivityOperation(target, step.activityName(), step.operation(),
                                step.arguments().size(), "activity step");
                    } else if (command instanceof Ast.CompensationCommand compensation) {
                        Ast.ActivityDecl target = resolveActivity(
                                activities, activitySimpleNames, module.name(), compensation.activityName());
                        requireActivityOperation(target, compensation.activityName(), compensation.operation(),
                                compensation.arguments().size(), "compensation");
                    } else if (command instanceof Ast.ChildWorkflowCommand child) {
                        resolveWorkflow(workflows, workflowSimpleNames, module.name(), child.workflowName());
                    } else if (command instanceof Ast.SignalCommand signal) {
                        requireEndpoint(endpoints, signal.endpoint(), signal.arguments().size(), "signal");
                    } else if (command instanceof Ast.UpdateCommand update) {
                        requireEndpoint(endpoints, update.endpoint(), update.arguments().size(), "update");
                    } else if (command instanceof Ast.QueryCommand query) {
                        Ast.MethodDecl endpoint = requireEndpoint(
                                endpoints, query.endpoint(), query.parameters().size(), "query");
                        List<Ast.TypeRef> actual = endpoint.parameters().stream().map(Ast.Param::type).toList();
                        List<Ast.TypeRef> declared = query.parameters().stream().map(Ast.Param::type).toList();
                        if (!actual.equals(declared) || !java.util.Objects.equals(endpoint.returnType(), query.returnType())) {
                            throw new IllegalArgumentException(
                                    "WORKFLOW_QUERY_SIGNATURE_MISMATCH: query endpoint '" + query.endpoint()
                                            + "' does not match its method parameter/return signature");
                        }
                    }
                }
            }
        }
    }

    private static Ast.ActivityDecl resolveActivity(
            java.util.Map<String, Ast.ActivityDecl> qualified,
            java.util.Map<String, java.util.List<String>> simpleNames,
            String currentModule,
            String requested) {
        if (requested.indexOf('.') >= 0) {
            Ast.ActivityDecl exact = qualified.get(requested);
            if (exact == null) throw new IllegalArgumentException(
                    "WORKFLOW_ACTIVITY_TARGET_UNKNOWN: unknown qualified activity '" + requested + "'");
            return exact;
        }
        Ast.ActivityDecl local = qualified.get(currentModule + "." + requested);
        if (local != null) return local;
        List<String> candidates = simpleNames.getOrDefault(requested, List.of());
        if (candidates.size() > 1) {
            throw new IllegalArgumentException(
                    "WORKFLOW_ACTIVITY_TARGET_AMBIGUOUS: activity '" + requested
                            + "' exists in multiple modules; use a qualified name");
        }
        if (candidates.isEmpty()) throw new IllegalArgumentException(
                "WORKFLOW_ACTIVITY_TARGET_UNKNOWN: undeclared activity '" + requested + "'");
        return qualified.get(candidates.getFirst());
    }

    private static Ast.WorkflowDecl resolveWorkflow(
            java.util.Map<String, Ast.WorkflowDecl> qualified,
            java.util.Map<String, java.util.List<String>> simpleNames,
            String currentModule,
            String requested) {
        if (requested.indexOf('.') >= 0) {
            Ast.WorkflowDecl exact = qualified.get(requested);
            if (exact == null) throw new IllegalArgumentException(
                    "WORKFLOW_CHILD_TARGET_UNKNOWN: unknown qualified workflow '" + requested + "'");
            return exact;
        }
        Ast.WorkflowDecl local = qualified.get(currentModule + "." + requested);
        if (local != null) return local;
        List<String> candidates = simpleNames.getOrDefault(requested, List.of());
        if (candidates.size() > 1) {
            throw new IllegalArgumentException(
                    "WORKFLOW_CHILD_TARGET_AMBIGUOUS: workflow '" + requested
                            + "' exists in multiple modules; use a qualified name");
        }
        if (candidates.isEmpty()) throw new IllegalArgumentException(
                "WORKFLOW_CHILD_TARGET_UNKNOWN: undeclared workflow '" + requested + "'");
        return qualified.get(candidates.getFirst());
    }

    private static void requireActivityOperation(
            Ast.ActivityDecl activity, String activityName,
            String operation, int arity, String context) {
        boolean found = activity.body().methods().stream().anyMatch(method ->
                method.name().equals(operation) && method.parameters().size() == arity);
        if (!found) {
            throw new IllegalArgumentException(
                    "WORKFLOW_ACTIVITY_OPERATION_UNKNOWN: " + context + " references missing operation '"
                            + activityName + "." + operation + "#" + arity + "'");
        }
    }

    private static Ast.MethodDecl requireEndpoint(
            java.util.Map<String, Ast.MethodDecl> endpoints,
            String name, int arity, String kind) {
        Ast.MethodDecl endpoint = endpoints.get(name + "#" + arity);
        if (endpoint == null) {
            throw new IllegalArgumentException(
                    "WORKFLOW_" + kind.toUpperCase() + "_ENDPOINT_UNKNOWN: no workflow member '"
                            + name + "#" + arity + "'");
        }
        return endpoint;
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
            } else if (statement instanceof Ast.ForOfStmt || statement instanceof Ast.ForOfDestructureStmt) {
                throw new IllegalArgumentException(
                        "WORKFLOW_ITERATION_ORDER_UNSUPPORTED: for-of requires a declared deterministic collection-order contract");
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
        if (expression instanceof Ast.LiteralExpr) return;
        if (expression instanceof Ast.NameExpr name) {
            if (name.name().startsWith("$")) {
                throw new IllegalArgumentException("WORKFLOW_INTERNAL_NAME_FORBIDDEN");
            }
            return;
        }
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
