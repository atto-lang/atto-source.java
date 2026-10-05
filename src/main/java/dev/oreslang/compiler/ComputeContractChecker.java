package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Semantic gate for data-oriented compute, dataflow, and static aspects.
 *
 * <p>This pass runs after ordinary type checking. It intentionally validates a
 * conservative AOT/device-safe subset: compute functions cannot acquire ambient
 * I/O/process/actor authority, await, allocate object/list state, create
 * closures, or call arbitrary host-style methods. Parallel/SIMD loop markers
 * are legal only inside compute functions and remain optimization requests:
 * a backend must still prove a legal lowering before executing iterations in
 * parallel.</p>
 */
public final class ComputeContractChecker {
    private static final Set<String> EFFECT_ANNOTATIONS = Set.of(
            "Reads", "Writes", "Discards", "Reduces", "Atomic", "Layout", "Place");
    private static final Set<String> PURE_INTRINSICS = Set.of(
            "abs", "min", "max", "sqrt", "sin", "cos", "tan",
            "exp", "log", "floor", "ceil", "round");

    private final Map<String, Ast.FunctionDecl> functions = new LinkedHashMap<>();
    private final Map<String, Ast.FunctionDecl> simpleFunctions = new HashMap<>();
    private final Set<String> ambiguousFunctions = new HashSet<>();
    private record OwnedFlow(String module, Ast.FlowDecl declaration) { }
    private record OwnedAspect(String module, Ast.AspectDecl declaration) { }

    private final List<OwnedFlow> flows = new ArrayList<>();
    private final List<OwnedAspect> aspects = new ArrayList<>();

    private ComputeContractChecker(Ast.Program program) {
        index(program);
    }

    public static Ast.Program check(Ast.Program program) {
        Objects.requireNonNull(program, "program");
        ComputeContractChecker checker = new ComputeContractChecker(program);
        checker.validate(program);
        return program;
    }

    public record RegionEffect(
            String rootParameter,
            String fieldPath,
            String privilege,
            String reductionOperator) { }

    public record ComputeContract(
            String name,
            List<RegionEffect> effects,
            Map<String, String> layouts,
            String placement) {
        public ComputeContract {
            effects = List.copyOf(effects);
            layouts = Map.copyOf(layouts);
        }
    }

    /** Extracts normalized backend metadata from a checked compute function. */
    public static ComputeContract contractOf(Ast.FunctionDecl fn) {
        if (!isCompute(fn)) {
            throw new IllegalArgumentException("function is not a compute fnc: " + fn.name());
        }
        ArrayList<RegionEffect> effects = new ArrayList<>();
        LinkedHashMap<String, String> layouts = new LinkedHashMap<>();
        String placement = "auto";

        for (Ast.Annotation annotation : fn.annotations()) {
            switch (annotation.name()) {
                case "Reads", "Writes", "Discards", "Atomic" -> {
                    for (Ast.TypeRef argument : annotation.arguments()) {
                        String[] path = splitPath(argument.name());
                        effects.add(new RegionEffect(
                                path[0],
                                path[1],
                                annotation.name().toLowerCase(),
                                null));
                    }
                }
                case "Reduces" -> {
                    String[] path = splitPath(annotation.arguments().get(0).name());
                    effects.add(new RegionEffect(
                            path[0],
                            path[1],
                            "reduces",
                            annotation.arguments().get(1).name()));
                }
                case "Layout" -> layouts.put(
                        annotation.arguments().get(0).name(),
                        annotation.arguments().get(1).name());
                case "Place" -> placement = annotation.arguments().getFirst().name();
                default -> { }
            }
        }

        return new ComputeContract(fn.name(), effects, layouts, placement);
    }

    public static boolean isCompute(Ast.FunctionDecl fn) {
        return hasAnnotation(fn.annotations(), "Compute");
    }

    private void index(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    String qualified = qualify(module.name(), fn.name());
                    functions.put(qualified, fn);
                    Ast.FunctionDecl previous = simpleFunctions.putIfAbsent(fn.name(), fn);
                    if (previous != null && previous != fn) {
                        simpleFunctions.remove(fn.name());
                        ambiguousFunctions.add(fn.name());
                    }
                } else if (declaration instanceof Ast.FlowDecl flow) {
                    flows.add(new OwnedFlow(module.name(), flow));
                } else if (declaration instanceof Ast.AspectDecl aspect) {
                    aspects.add(new OwnedAspect(module.name(), aspect));
                }
            }
        }
    }

    private void validate(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    validateFunction(module.name(), fn);
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    for (Ast.MethodDecl method : klass.methods()) {
                        validateLoopExecution(
                                method.body(),
                                false,
                                "method " + klass.name() + "." + method.name());
                    }
                }
            }
        }

        validateMetadataNames(program);
        for (OwnedFlow flow : flows) validateFlow(flow.module(), flow.declaration());
        for (OwnedAspect aspect : aspects) validateAspect(aspect.module(), aspect.declaration());
    }

    private void validateMetadataNames(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            LinkedHashMap<String, String> occupied = new LinkedHashMap<>();
            for (Ast.Decl declaration : module.declarations()) {
                String name = null;
                String kind = null;
                if (declaration instanceof Ast.FunctionDecl fn) {
                    name = fn.name(); kind = "callable";
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    name = klass.name(); kind = "class";
                } else if (declaration instanceof Ast.InterfaceDecl iface) {
                    name = iface.name(); kind = "interface";
                } else if (declaration instanceof Ast.FieldDecl field) {
                    name = field.name(); kind = "binding";
                } else if (declaration instanceof Ast.TypeAliasDecl alias) {
                    name = alias.name(); kind = "type alias";
                } else if (declaration instanceof Ast.FlowDecl flow) {
                    name = flow.name(); kind = "flow";
                } else if (declaration instanceof Ast.AspectDecl aspect) {
                    name = aspect.name(); kind = "aspect";
                }
                if (name == null) continue;
                String previous = occupied.putIfAbsent(name, kind);
                if (previous != null
                        && (kind.equals("flow")
                            || kind.equals("aspect")
                            || previous.equals("flow")
                            || previous.equals("aspect"))) {
                    throw new IllegalArgumentException(
                            "compile-time declaration '" + qualify(module.name(), name)
                                    + "' collides between " + previous + " and " + kind);
                }
            }
        }
    }

    private void validateFunction(String module, Ast.FunctionDecl fn) {
        boolean compute = isCompute(fn);
        boolean hasEffectMetadata = fn.annotations().stream()
                .anyMatch(annotation -> EFFECT_ANNOTATIONS.contains(annotation.name()));

        if (!compute && hasEffectMetadata) {
            throw new IllegalArgumentException(
                    "region effects/layout/placement require 'compute fnc': " + qualify(module, fn.name()));
        }

        if (!compute) {
            validateLoopExecution(fn.body(), false, "function " + qualify(module, fn.name()));
            return;
        }

        if (fn.kind() != Ast.CallableKind.FNC
                || fn.actorKind() != Ast.ActorKind.NONE
                || fn.async()
                || fn.nonLexical()) {
            throw new IllegalArgumentException(
                    "compute fnc must be synchronous, non-actor, and lexical: " + qualify(module, fn.name()));
        }
        if (!fn.genericParameters().isEmpty()) {
            throw new IllegalArgumentException(
                    "compute fnc generic specialization is not part of the AOT floor yet: "
                            + qualify(module, fn.name()));
        }

        Map<String, Ast.Param> params = new LinkedHashMap<>();
        for (Ast.Param param : fn.parameters()) params.put(param.name(), param);

        validateComputeMetadata(fn, params);
        validateLoopExecution(fn.body(), true, "compute fnc " + qualify(module, fn.name()));
        validateComputeBody(fn.body(), module, fn.name());
    }

    private void validateComputeMetadata(Ast.FunctionDecl fn, Map<String, Ast.Param> params) {
        int placeCount = 0;
        Set<String> layoutTargets = new LinkedHashSet<>();

        for (Ast.Annotation annotation : fn.annotations()) {
            switch (annotation.name()) {
                case "Reads", "Writes", "Discards", "Atomic" -> {
                    if (annotation.arguments().isEmpty()) {
                        throw new IllegalArgumentException(
                                annotation.name().toLowerCase() + " requires at least one region path");
                    }
                    for (Ast.TypeRef argument : annotation.arguments()) {
                        validateRegionPath(argument, params, annotation.name().toLowerCase());
                    }
                }
                case "Reduces" -> {
                    if (annotation.arguments().size() != 2) {
                        throw new IllegalArgumentException(
                                "reduces requires exactly a region path and reduction operator");
                    }
                    validateRegionPath(annotation.arguments().get(0), params, "reduces");
                    Ast.TypeRef operator = annotation.arguments().get(1);
                    requireMetadataAtom(operator, "reduction operator");
                }
                case "Layout" -> {
                    if (annotation.arguments().size() != 2) {
                        throw new IllegalArgumentException(
                                "layout requires a region parameter and layout kind");
                    }
                    Ast.TypeRef target = annotation.arguments().get(0);
                    Ast.TypeRef layout = annotation.arguments().get(1);
                    requireMetadataAtom(target, "layout target");
                    requireRegionParameter(params, target.name(), "layout");
                    requireMetadataAtom(layout, "layout kind");
                    if (!Set.of("auto", "aos", "soa", "aosoa", "tiled").contains(layout.name())) {
                        throw new IllegalArgumentException("unknown region layout '" + layout.name() + "'");
                    }
                    if (!layoutTargets.add(target.name())) {
                        throw new IllegalArgumentException(
                                "duplicate layout declaration for region parameter '" + target.name() + "'");
                    }
                }
                case "Place" -> {
                    placeCount++;
                    if (placeCount > 1) {
                        throw new IllegalArgumentException("compute fnc may declare at most one place clause");
                    }
                    if (annotation.arguments().size() != 1) {
                        throw new IllegalArgumentException("place requires exactly one of auto, cpu, or gpu");
                    }
                    Ast.TypeRef placement = annotation.arguments().getFirst();
                    requireMetadataAtom(placement, "placement");
                    if (!Set.of("auto", "cpu", "gpu").contains(placement.name())) {
                        throw new IllegalArgumentException("unknown compute placement '" + placement.name() + "'");
                    }
                }
                default -> { }
            }
        }
    }

    private void validateRegionPath(
            Ast.TypeRef path,
            Map<String, Ast.Param> params,
            String clause) {
        requireMetadataAtom(path, clause + " target");
        String[] split = splitPath(path.name());
        requireRegionParameter(params, split[0], clause);
    }

    private void requireRegionParameter(
            Map<String, Ast.Param> params,
            String name,
            String clause) {
        Ast.Param parameter = params.get(name);
        if (parameter == null) {
            throw new IllegalArgumentException(
                    clause + " target '" + name + "' is not a parameter of this compute fnc");
        }
        Ast.TypeRef type = parameter.type();
        if (!type.name().equals("Region") && !type.name().equals("RegionView")) {
            throw new IllegalArgumentException(
                    clause + " target '" + name + "' must have Region<T> or RegionView<T> type");
        }
        if (type.arguments().size() != 1 || type.inferArguments()) {
            throw new IllegalArgumentException(
                    type.name() + " requires exactly one explicit element type");
        }
    }

    private static void requireMetadataAtom(Ast.TypeRef value, String what) {
        if (value == null
                || value.inferArguments()
                || value.isBorrow()
                || value.isStringLiteral()
                || value.isRecordType()
                || value.isTupleType()
                || value.isUnion()
                || !value.arguments().isEmpty()) {
            throw new IllegalArgumentException(what + " must be a simple static name/path");
        }
    }

    private void validateLoopExecution(List<Ast.Stmt> body, boolean compute, String owner) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ForOfStmt loop) {
                validateExecution(loop.execution(), compute, owner);
                validateLoopExecution(loop.body(), compute, owner);
            } else if (stmt instanceof Ast.ForOfDestructureStmt loop) {
                validateExecution(loop.execution(), compute, owner);
                validateLoopExecution(loop.body(), compute, owner);
            } else if (stmt instanceof Ast.ForStmt loop) {
                validateExecution(loop.execution(), compute, owner);
                validateLoopExecution(loop.body(), compute, owner);
            } else if (stmt instanceof Ast.LoopStmt loop) {
                validateLoopExecution(loop.body(), compute, owner);
            } else if (stmt instanceof Ast.BlockStmt block) {
                validateLoopExecution(block.body(), compute, owner);
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    validateLoopExecution(branch.body(), compute, owner);
                }
                validateLoopExecution(conditional.elseBody(), compute, owner);
            } else if (stmt instanceof Ast.TryStmt attempted) {
                validateLoopExecution(attempted.body(), compute, owner);
                validateLoopExecution(attempted.catchBody(), compute, owner);
                validateLoopExecution(attempted.finallyBody(), compute, owner);
            }
        }
    }

    private static void validateExecution(
            Ast.LoopExecution execution,
            boolean compute,
            String owner) {
        if (execution != Ast.LoopExecution.SEQUENTIAL && !compute) {
            throw new IllegalArgumentException(
                    execution.name().toLowerCase()
                            + " loop execution is only legal inside compute fnc; found in " + owner);
        }
    }

    private void validateComputeBody(List<Ast.Stmt> body, String module, String functionName) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.DeferStmt) {
                throw unsafe(functionName, "defer");
            }
            if (stmt instanceof Ast.TryStmt) {
                throw unsafe(functionName, "try/catch/finally");
            }
            if (stmt instanceof Ast.BindingStmt binding) {
                validateComputeExpr(binding.initializer(), module, functionName);
            } else if (stmt instanceof Ast.DestructureStmt destructure) {
                validateComputeExpr(destructure.initializer(), module, functionName);
            } else if (stmt instanceof Ast.ReturnStmt returned) {
                validateComputeExpr(returned.value(), module, functionName);
            } else if (stmt instanceof Ast.ExprStmt expression) {
                validateComputeExpr(expression.expression(), module, functionName);
            } else if (stmt instanceof Ast.BlockStmt block) {
                validateComputeBody(block.body(), module, functionName);
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    validateComputeExpr(branch.condition(), module, functionName);
                    validateComputeBody(branch.body(), module, functionName);
                }
                validateComputeBody(conditional.elseBody(), module, functionName);
            } else if (stmt instanceof Ast.ForOfStmt loop) {
                validateComputeExpr(loop.iterable(), module, functionName);
                validateComputeBody(loop.body(), module, functionName);
            } else if (stmt instanceof Ast.ForOfDestructureStmt loop) {
                validateComputeExpr(loop.iterable(), module, functionName);
                validateComputeBody(loop.body(), module, functionName);
            } else if (stmt instanceof Ast.ForStmt loop) {
                if (loop.initializer() instanceof Ast.BindingStmt binding) {
                    validateComputeExpr(binding.initializer(), module, functionName);
                } else if (loop.initializer() instanceof Ast.ExprStmt expression) {
                    validateComputeExpr(expression.expression(), module, functionName);
                }
                validateComputeExpr(loop.condition(), module, functionName);
                validateComputeExpr(loop.update(), module, functionName);
                validateComputeBody(loop.body(), module, functionName);
            } else if (stmt instanceof Ast.LoopStmt loop) {
                validateComputeBody(loop.body(), module, functionName);
            }
        }
    }

    private void validateComputeExpr(Ast.Expr expr, String module, String owner) {
        if (expr == null || expr instanceof Ast.LiteralExpr) return;

        if (expr instanceof Ast.NameExpr name) {
            if (Set.of("stdio", "process", "actor").contains(name.name())) {
                throw unsafe(owner, "ambient " + name.name() + " capability");
            }
            return;
        }
        if (expr instanceof Ast.AwaitExpr) throw unsafe(owner, "await");
        if (expr instanceof Ast.NewExpr) throw unsafe(owner, "allocation with new");
        if (expr instanceof Ast.LambdaExpr) throw unsafe(owner, "closure/lambda creation");
        if (expr instanceof Ast.ListExpr || expr instanceof Ast.ObjectExpr) {
            throw unsafe(owner, "heap-backed collection/object literal allocation");
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            validateComputeExpr(binary.left(), module, owner);
            validateComputeExpr(binary.right(), module, owner);
            return;
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            validateComputeExpr(unary.operand(), module, owner);
            return;
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            validateComputeExpr(assignment.target(), module, owner);
            validateComputeExpr(assignment.value(), module, owner);
            return;
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            validateComputeExpr(conditional.condition(), module, owner);
            validateComputeExpr(conditional.whenTrue(), module, owner);
            validateComputeExpr(conditional.whenFalse(), module, owner);
            return;
        }
        if (expr instanceof Ast.MemberExpr member) {
            validateComputeExpr(member.receiver(), module, owner);
            return;
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            validateComputeExpr(indexed.receiver(), module, owner);
            validateComputeExpr(indexed.index(), module, owner);
            return;
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr element : tuple.elements()) validateComputeExpr(element, module, owner);
            return;
        }
        if (expr instanceof Ast.CallExpr call) {
            validateComputeCall(call, module, owner);
        }
    }

    private void validateComputeCall(Ast.CallExpr call, String module, String owner) {
        for (Ast.Expr argument : call.arguments()) {
            validateComputeExpr(argument, module, owner);
        }

        if (call.callee() instanceof Ast.NameExpr direct) {
            if (PURE_INTRINSICS.contains(direct.name())) return;
            Ast.FunctionDecl target = resolveFunction(module, direct.name());
            if (target != null && isCompute(target)) return;
            throw unsafe(owner, "call to non-compute callable '" + direct.name() + "'");
        }

        if (call.callee() instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr namespace) {
            Ast.FunctionDecl target = functions.get(qualify(namespace.name(), member.member()));
            if (target != null && isCompute(target)) return;
        }

        throw unsafe(owner, "dynamic/member call; compute fnc calls must resolve statically to another compute fnc or pure intrinsic");
    }

    private record ResolvedFunction(String id, Ast.FunctionDecl declaration) { }

    private void validateFlow(String module, Ast.FlowDecl flow) {
        LinkedHashMap<String, LinkedHashSet<String>> outgoing = new LinkedHashMap<>();
        for (Ast.FlowEdge edge : flow.edges()) {
            ResolvedFunction from = resolveFunctionRef(module, edge.from());
            ResolvedFunction to = resolveFunctionRef(module, edge.to());
            if (from == null || !isCompute(from.declaration())) {
                throw new IllegalArgumentException(
                        "flow '" + flow.name() + "' source '" + edge.from() + "' is not a compute fnc");
            }
            if (to == null || !isCompute(to.declaration())) {
                throw new IllegalArgumentException(
                        "flow '" + flow.name() + "' target '" + edge.to() + "' is not a compute fnc");
            }
            outgoing.computeIfAbsent(from.id(), ignored -> new LinkedHashSet<>()).add(to.id());
            outgoing.computeIfAbsent(to.id(), ignored -> new LinkedHashSet<>());
        }

        Set<String> visiting = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        for (String node : outgoing.keySet()) {
            if (hasCycle(node, outgoing, visiting, visited)) {
                throw new IllegalArgumentException("flow '" + flow.name() + "' contains a cycle");
            }
        }
    }

    private static boolean hasCycle(
            String node,
            Map<String, ? extends Set<String>> outgoing,
            Set<String> visiting,
            Set<String> visited) {
        if (visited.contains(node)) return false;
        if (!visiting.add(node)) return true;
        Set<String> nextNodes = outgoing.get(node);
        if (nextNodes != null) {
            for (String next : nextNodes) {
                if (hasCycle(next, outgoing, visiting, visited)) return true;
            }
        }
        visiting.remove(node);
        visited.add(node);
        return false;
    }

    private void validateAspect(String module, Ast.AspectDecl aspect) {
        Set<String> rules = new LinkedHashSet<>();
        for (Ast.AspectRule rule : aspect.rules()) {
            Ast.FunctionDecl handler = resolveFunction(module, rule.handler());
            if (handler == null) {
                throw new IllegalArgumentException(
                        "aspect '" + aspect.name() + "' references unknown handler '" + rule.handler() + "'");
            }
            if (handler.kind() != Ast.CallableKind.FNC
                    || handler.actorKind() != Ast.ActorKind.NONE
                    || handler.async()
                    || !handler.genericParameters().isEmpty()) {
                throw new IllegalArgumentException(
                        "aspect handler '" + rule.handler()
                                + "' must be a synchronous non-actor non-generic fnc");
            }

            if (rule.adviceKind() == Ast.AspectAdviceKind.AROUND
                    && Set.of(
                            Ast.AspectJoinPoint.TASK,
                            Ast.AspectJoinPoint.AWAIT_SUSPEND,
                            Ast.AspectJoinPoint.AWAIT_RESUME,
                            Ast.AspectJoinPoint.REGION_TRANSFER,
                            Ast.AspectJoinPoint.GPU_LAUNCH)
                    .contains(rule.joinPoint())) {
                throw new IllegalArgumentException(
                        "around advice is forbidden for compute/device/scheduler join point "
                                + rule.joinPoint().name().toLowerCase()
                                + "; use before/after so optimizer and scheduler semantics remain analyzable");
            }

            String key = rule.joinPoint() + ":" + rule.adviceKind() + ":" + rule.handler();
            if (!rules.add(key)) {
                throw new IllegalArgumentException(
                        "duplicate aspect rule in '" + aspect.name() + "': " + key);
            }
        }
    }

    private Ast.FunctionDecl resolveFunction(String module, String name) {
        ResolvedFunction resolved = resolveFunctionRef(module, name);
        return resolved == null ? null : resolved.declaration();
    }

    private ResolvedFunction resolveFunctionRef(String module, String name) {
        if (name.contains(".")) {
            Ast.FunctionDecl qualified = functions.get(name);
            return qualified == null ? null : new ResolvedFunction(name, qualified);
        }

        String localId = qualify(module, name);
        Ast.FunctionDecl local = functions.get(localId);
        if (local != null) return new ResolvedFunction(localId, local);

        Ast.FunctionDecl target = resolveAnyFunction(name);
        if (target == null) return null;
        for (Map.Entry<String, Ast.FunctionDecl> entry : functions.entrySet()) {
            if (entry.getValue() == target) return new ResolvedFunction(entry.getKey(), target);
        }
        throw new IllegalStateException("indexed function has no canonical id: " + name);
    }

    private Ast.FunctionDecl resolveAnyFunction(String name) {
        if (name.contains(".")) return functions.get(name);
        if (ambiguousFunctions.contains(name)) {
            throw new IllegalArgumentException(
                    "ambiguous callable '" + name + "'; qualify it with its module");
        }
        return simpleFunctions.get(name);
    }

    private static IllegalArgumentException unsafe(String owner, String feature) {
        return new IllegalArgumentException(
                "compute fnc '" + owner + "' cannot use " + feature
                        + "; compute bodies must remain statically analyzable and device/AOT safe");
    }

    private static boolean hasAnnotation(List<Ast.Annotation> annotations, String name) {
        for (Ast.Annotation annotation : annotations) {
            if (annotation.name().equals(name)) return true;
        }
        return false;
    }

    private static String[] splitPath(String path) {
        int dot = path.indexOf('.');
        return dot < 0
                ? new String[] { path, "" }
                : new String[] { path.substring(0, dot), path.substring(dot + 1) };
    }

    private static String qualify(String module, String name) {
        return module.equals("__root__") ? name : module + "." + name;
    }
}
