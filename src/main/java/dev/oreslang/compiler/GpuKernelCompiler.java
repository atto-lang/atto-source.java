package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lowers admitted Oreslang GPU callables into deterministic OpenCL C 1.2 source plus
 * backend-neutral kernel metadata.
 *
 * <p>This compiler is intentionally conservative. If the front end accepts a GPU callable,
 * this lowering must be able to emit device code for it. Unsupported constructs fail here rather
 * than being left to a runtime CPU fallback.</p>
 */
public final class GpuKernelCompiler {
    public static final String BACKEND = "opencl-c-1.2";

    public enum KernelKind { FUNCTION, STATIC_FUNCTION, LAMBDA }
    public enum ExecutionShape { SINGLE_WORK_ITEM, DATA_PARALLEL_1D }

    public record GpuParameter(
            String name,
            String oresType,
            String abiType,
            boolean buffer,
            boolean mutable) { }

    public record GpuKernel(
            String sourceName,
            String helperSymbol,
            String kernelSymbol,
            KernelKind kind,
            Ast.GpuMode mode,
            ExecutionShape executionShape,
            String batchId,
            int batchIndex,
            List<GpuParameter> parameters,
            String resultType,
            String launchExtentExpression) {
        public GpuKernel {
            parameters = List.copyOf(parameters);
        }
    }

    public record GpuBatch(
            String id,
            String dispatcherSymbol,
            int workItems,
            List<String> kernelSymbols) {
        public GpuBatch {
            kernelSymbols = List.copyOf(kernelSymbols);
        }

        public boolean hasDispatcher() { return dispatcherSymbol != null; }
    }

    public record GpuProgram(
            String backend,
            String source,
            String sha256,
            List<GpuKernel> kernels,
            List<GpuBatch> batches,
            Set<String> requiredExtensions) {
        public GpuProgram {
            kernels = List.copyOf(kernels);
            batches = List.copyOf(batches);
            requiredExtensions = Set.copyOf(requiredExtensions);
        }

        public boolean hasKernels() { return !kernels.isEmpty(); }

        public GpuProgram(String backend, String source, String sha256, List<GpuKernel> kernels) {
            this(backend, source, sha256, kernels, List.of(), Set.of());
        }

        public static GpuProgram empty() {
            String source = "// no GPU kernels\n";
            return new GpuProgram(BACKEND, source, digest(source), List.of(), List.of(), Set.of());
        }
    }

    private final Ast.Program program;
    private final Map<String, Ast.TypeAliasDecl> aliases = new HashMap<>();
    private final Set<String> ambiguousAliases = new HashSet<>();
    private final Map<String, Target> namedByQualifiedName = new LinkedHashMap<>();
    private final Map<String, Target> namedByShortName = new LinkedHashMap<>();
    private final Set<String> ambiguousNamed = new HashSet<>();
    private final List<Target> namedTargets = new ArrayList<>();
    private final List<Target> lambdaTargets = new ArrayList<>();
    private int lambdaOrdinal;
    private int batchOrdinal;

    private GpuKernelCompiler(Ast.Program program) {
        this.program = program;
    }

    public static GpuProgram compile(Ast.Program checkedProgram) {
        GpuKernelCompiler compiler = new GpuKernelCompiler(checkedProgram);
        compiler.index();
        compiler.collectGpuLambdas();
        compiler.rejectRecursiveGpuCalls();
        return compiler.emit();
    }

    private void index() {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.TypeAliasDecl alias) {
                    putAlias(module.name(), alias);
                } else if (decl instanceof Ast.FunctionDecl fn && Ast.hasGpuPlacement(fn.annotations())) {
                    Target target = Target.namedFunction(module.name(), fn, symbolBase(module.name(), fn.name(), fn.parameters().size()));
                    putNamed(module.name() + "." + fn.name(), fn.name(), target);
                    namedTargets.add(target);
                } else if (decl instanceof Ast.ClassDecl klass) {
                    for (Ast.MethodDecl method : klass.methods()) {
                        if (Ast.hasGpuPlacement(method.annotations())) {
                            String sourceName = klass.name() + "." + method.name();
                            String base = symbolBase(module.name(), klass.name() + "_" + method.name(), method.parameters().size());
                            Target target = Target.staticFunction(module.name(), klass.name(), sourceName, method, base);
                            putNamed(module.name() + "." + sourceName, sourceName, target);
                            putNamed(module.name() + "." + klass.name() + "." + method.name(),
                                    klass.name() + "." + method.name(), target);
                            namedTargets.add(target);
                        }
                    }
                }
            }
        }
    }

    private void putAlias(String module, Ast.TypeAliasDecl alias) {
        aliases.put(module + "." + alias.name(), alias);
        Ast.TypeAliasDecl previous = aliases.putIfAbsent(alias.name(), alias);
        if (previous != null && previous != alias) {
            ambiguousAliases.add(alias.name());
            aliases.remove(alias.name());
        }
    }

    private void putNamed(String qualified, String shortName, Target target) {
        String qualifiedKey = callKey(qualified, target.parameters.size());
        String shortKey = callKey(shortName, target.parameters.size());
        namedByQualifiedName.put(qualifiedKey, target);
        Target previous = namedByShortName.putIfAbsent(shortKey, target);
        if (previous != null && previous != target) {
            ambiguousNamed.add(shortKey);
            namedByShortName.remove(shortKey);
        }
    }

    private void collectGpuLambdas() {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    if (!Ast.hasGpuPlacement(fn.annotations())) {
                        scanStatementsForGpu(module.name(), fn.name(), fn.body());
                    }
                } else if (decl instanceof Ast.ClassDecl klass) {
                    for (Ast.FieldDecl field : klass.fields()) {
                        if (field.initializer() != null) {
                            scanExpressionForGpu(module.name(), klass.name() + "_" + field.name(), field.initializer());
                        }
                    }
                    for (Ast.MethodDecl method : klass.methods()) {
                        if (!Ast.hasGpuPlacement(method.annotations())) {
                            scanStatementsForGpu(module.name(), klass.name() + "_" + method.name(), method.body());
                        }
                    }
                } else if (decl instanceof Ast.FieldDecl field && field.initializer() != null) {
                    scanExpressionForGpu(module.name(), field.name(), field.initializer());
                }
            }
        }
    }

    private void scanStatementsForGpu(String module, String owner, List<Ast.Stmt> statements) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt s) scanExpressionForGpu(module, owner, s.initializer());
            else if (stmt instanceof Ast.DestructureStmt s) scanExpressionForGpu(module, owner, s.initializer());
            else if (stmt instanceof Ast.ReturnStmt s && s.value() != null) scanExpressionForGpu(module, owner, s.value());
            else if (stmt instanceof Ast.ExprStmt s) scanExpressionForGpu(module, owner, s.expression());
            else if (stmt instanceof Ast.DeferStmt s) scanExpressionForGpu(module, owner, s.expression());
            else if (stmt instanceof Ast.IfStmt s) {
                for (Ast.IfBranch branch : s.branches()) {
                    scanExpressionForGpu(module, owner, branch.condition());
                    scanStatementsForGpu(module, owner, branch.body());
                }
                scanStatementsForGpu(module, owner, s.elseBody());
            } else if (stmt instanceof Ast.TryStmt s) {
                scanStatementsForGpu(module, owner, s.body());
                scanStatementsForGpu(module, owner, s.catchBody());
                scanStatementsForGpu(module, owner, s.finallyBody());
            } else if (stmt instanceof Ast.ForOfStmt s) {
                scanExpressionForGpu(module, owner, s.iterable());
                scanStatementsForGpu(module, owner, s.body());
            } else if (stmt instanceof Ast.ForStmt s) {
                if (s.initializer() != null) scanStatementsForGpu(module, owner, List.of(s.initializer()));
                if (s.condition() != null) scanExpressionForGpu(module, owner, s.condition());
                if (s.update() != null) scanExpressionForGpu(module, owner, s.update());
                scanStatementsForGpu(module, owner, s.body());
            }
        }
    }

    private void scanExpressionForGpu(String module, String owner, Ast.Expr expr) {
        if (expr == null) return;
        if (expr instanceof Ast.GpuExpr gpu) {
            if (gpu.mode() == Ast.GpuMode.SINGLE) {
                Ast.LambdaExpr lambda = requireLambda(gpu.expression(), "'gpu' expression");
                addLambdaTarget(module, owner, lambda, Ast.GpuMode.SINGLE, null, 0);
            } else {
                String batchId = "batch_" + sanitize(module) + "_" + sanitize(owner) + "_" + (++batchOrdinal);
                List<Ast.Expr> elements = gpuParallelElements(gpu.expression());
                for (int i = 0; i < elements.size(); i++) {
                    addLambdaTarget(module, owner, requireLambda(elements.get(i), "'gpu parallel'"), Ast.GpuMode.PARALLEL, batchId, i);
                }
            }
            return;
        }
        if (expr instanceof Ast.BinaryExpr e) {
            scanExpressionForGpu(module, owner, e.left());
            scanExpressionForGpu(module, owner, e.right());
        } else if (expr instanceof Ast.UnaryExpr e) {
            scanExpressionForGpu(module, owner, e.operand());
        } else if (expr instanceof Ast.AssignExpr e) {
            scanExpressionForGpu(module, owner, e.target());
            scanExpressionForGpu(module, owner, e.value());
        } else if (expr instanceof Ast.ConditionalExpr e) {
            scanExpressionForGpu(module, owner, e.condition());
            scanExpressionForGpu(module, owner, e.whenTrue());
            scanExpressionForGpu(module, owner, e.whenFalse());
        } else if (expr instanceof Ast.CallExpr e) {
            scanExpressionForGpu(module, owner, e.callee());
            for (Ast.Expr arg : e.arguments()) scanExpressionForGpu(module, owner, arg);
        } else if (expr instanceof Ast.MemberExpr e) {
            scanExpressionForGpu(module, owner, e.receiver());
        } else if (expr instanceof Ast.IndexExpr e) {
            scanExpressionForGpu(module, owner, e.receiver());
            scanExpressionForGpu(module, owner, e.index());
        } else if (expr instanceof Ast.NewExpr e) {
            for (Ast.Expr arg : e.arguments()) scanExpressionForGpu(module, owner, arg);
        } else if (expr instanceof Ast.AwaitExpr e) {
            scanExpressionForGpu(module, owner, e.expression());
        } else if (expr instanceof Ast.ListExpr e) {
            for (Ast.Expr item : e.elements()) scanExpressionForGpu(module, owner, item);
        } else if (expr instanceof Ast.TupleExpr e) {
            for (Ast.Expr item : e.elements()) scanExpressionForGpu(module, owner, item);
        } else if (expr instanceof Ast.ObjectExpr e) {
            for (Ast.ObjectField field : e.fields()) scanExpressionForGpu(module, owner, field.value());
        } else if (expr instanceof Ast.LambdaExpr e) {
            scanStatementsForGpu(module, owner, e.blockBody());
        }
    }

    private void addLambdaTarget(String module, String owner, Ast.LambdaExpr lambda, Ast.GpuMode mode, String batchId, int batchIndex) {
        int ordinal = ++lambdaOrdinal;
        String sourceName = owner + "$gpu$" + ordinal;
        String base = symbolBase(module, owner + "_lambda_" + ordinal, lambda.parameters().size());
        lambdaTargets.add(Target.lambda(module, sourceName, lambda, base, mode, batchId, batchIndex));
    }

    private Ast.LambdaExpr requireLambda(Ast.Expr expr, String where) {
        if (!(expr instanceof Ast.LambdaExpr lambda)) throw new IllegalArgumentException(where + " requires a direct lambda");
        return lambda;
    }

    private List<Ast.Expr> gpuParallelElements(Ast.Expr expression) {
        if (expression instanceof Ast.ListExpr list) return list.elements();
        if (expression instanceof Ast.TupleExpr tuple) return tuple.elements();
        if (expression instanceof Ast.CallExpr call
                && call.callee() instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr name
                && name.name().equals("List")
                && member.member().equals("of")) {
            return call.arguments();
        }
        throw new IllegalArgumentException("'gpu parallel' requires a list/tuple literal or List.of(...)");
    }

    private void rejectRecursiveGpuCalls() {
        Map<Target, Set<Target>> edges = new LinkedHashMap<>();
        for (Target target : namedTargets) {
            LinkedHashSet<Target> calls = new LinkedHashSet<>();
            collectNamedCalls(target.module, target.body, calls);
            edges.put(target, calls);
        }

        Set<Target> visiting = new HashSet<>();
        Set<Target> visited = new HashSet<>();
        ArrayDeque<Target> stack = new ArrayDeque<>();
        for (Target target : namedTargets) {
            detectCycle(target, edges, visiting, visited, stack);
        }
    }

    private void detectCycle(Target target, Map<Target, Set<Target>> edges, Set<Target> visiting,
                             Set<Target> visited, ArrayDeque<Target> stack) {
        if (visited.contains(target)) return;
        if (!visiting.add(target)) {
            StringBuilder cycle = new StringBuilder();
            for (Target item : stack) cycle.append(item.sourceName).append(" -> ");
            cycle.append(target.sourceName);
            throw new IllegalArgumentException("recursive GPU call graph is not supported by the device backend: " + cycle);
        }
        stack.addLast(target);
        for (Target dependency : edges.getOrDefault(target, Set.of())) {
            detectCycle(dependency, edges, visiting, visited, stack);
        }
        stack.removeLast();
        visiting.remove(target);
        visited.add(target);
    }

    private void collectNamedCalls(String module, List<Ast.Stmt> statements, Set<Target> out) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt s) collectNamedCalls(module, s.initializer(), out);
            else if (stmt instanceof Ast.ReturnStmt s && s.value() != null) collectNamedCalls(module, s.value(), out);
            else if (stmt instanceof Ast.ExprStmt s) collectNamedCalls(module, s.expression(), out);
            else if (stmt instanceof Ast.IfStmt s) {
                for (Ast.IfBranch branch : s.branches()) {
                    collectNamedCalls(module, branch.condition(), out);
                    collectNamedCalls(module, branch.body(), out);
                }
                collectNamedCalls(module, s.elseBody(), out);
            } else if (stmt instanceof Ast.ForStmt s) {
                if (s.initializer() instanceof Ast.BindingStmt b) collectNamedCalls(module, b.initializer(), out);
                else if (s.initializer() instanceof Ast.ExprStmt e) collectNamedCalls(module, e.expression(), out);
                if (s.condition() != null) collectNamedCalls(module, s.condition(), out);
                if (s.update() != null) collectNamedCalls(module, s.update(), out);
                collectNamedCalls(module, s.body(), out);
            }
        }
    }

    private void collectNamedCalls(String module, Ast.Expr expr, Set<Target> out) {
        if (expr == null) return;
        if (expr instanceof Ast.CallExpr call) {
            Target target = resolveCallTarget(module, call);
            if (target != null) out.add(target);
            for (Ast.Expr arg : call.arguments()) collectNamedCalls(module, arg, out);
        } else if (expr instanceof Ast.BinaryExpr e) {
            collectNamedCalls(module, e.left(), out);
            collectNamedCalls(module, e.right(), out);
        } else if (expr instanceof Ast.UnaryExpr e) collectNamedCalls(module, e.operand(), out);
        else if (expr instanceof Ast.AssignExpr e) {
            collectNamedCalls(module, e.target(), out);
            collectNamedCalls(module, e.value(), out);
        } else if (expr instanceof Ast.ConditionalExpr e) {
            collectNamedCalls(module, e.condition(), out);
            collectNamedCalls(module, e.whenTrue(), out);
            collectNamedCalls(module, e.whenFalse(), out);
        } else if (expr instanceof Ast.IndexExpr e) {
            collectNamedCalls(module, e.receiver(), out);
            collectNamedCalls(module, e.index(), out);
        }
    }

    private GpuProgram emit() {
        List<Target> all = new ArrayList<>(namedTargets);
        all.addAll(lambdaTargets);
        if (all.isEmpty()) return GpuProgram.empty();

        StringBuilder body = new StringBuilder();

        for (Target target : namedTargets) {
            Ast.TypeRef result = resolvedResult(target);
            body.append("static inline ").append(cScalar(result)).append(' ')
                    .append(target.helperSymbol).append('(')
                    .append(parameterList(target.parameters, target.module)).append(");\n");
        }
        if (!namedTargets.isEmpty()) body.append('\n');

        ArrayList<GpuKernel> metadata = new ArrayList<>();
        for (Target target : namedTargets) {
            Ast.TypeRef result = resolvedResult(target);
            emitHelper(body, target, result);
            KernelEmission kernel = emitKernel(body, target, result);
            metadata.add(kernel.metadata);
        }
        for (Target target : lambdaTargets) {
            Ast.TypeRef result = inferLambdaResult(target);
            emitHelper(body, target, result);
            KernelEmission kernel = emitKernel(body, target, result);
            metadata.add(kernel.metadata);
        }

        List<GpuBatch> batches = emitBatchDispatchers(body);
        boolean fp64 = body.indexOf("double") >= 0;
        LinkedHashSet<String> requiredExtensions = new LinkedHashSet<>();
        if (fp64) requiredExtensions.add("cl_khr_fp64");

        StringBuilder sourceBuilder = new StringBuilder();
        sourceBuilder.append("// Generated by Oreslang GpuKernelCompiler\n");
        sourceBuilder.append("// Backend: ").append(BACKEND).append("\n");
        if (fp64) sourceBuilder.append("#pragma OPENCL EXTENSION cl_khr_fp64 : enable\n");
        sourceBuilder.append('\n').append(body);

        String source = sourceBuilder.toString();
        return new GpuProgram(BACKEND, source, digest(source), metadata, batches, requiredExtensions);
    }

    private List<GpuBatch> emitBatchDispatchers(StringBuilder out) {
        LinkedHashMap<String, List<Target>> groups = new LinkedHashMap<>();
        for (Target target : lambdaTargets) {
            if (target.mode == Ast.GpuMode.PARALLEL && target.batchId != null) {
                groups.computeIfAbsent(target.batchId, ignored -> new ArrayList<>()).add(target);
            }
        }

        ArrayList<GpuBatch> batches = new ArrayList<>();
        for (Map.Entry<String, List<Target>> entry : groups.entrySet()) {
            List<Target> targets = entry.getValue();
            boolean dispatcherEligible = true;
            for (Target target : targets) {
                if (!target.parameters.isEmpty() || !isVoid(inferLambdaResult(target))) {
                    dispatcherEligible = false;
                    break;
                }
            }

            String dispatcher = null;
            if (dispatcherEligible) {
                dispatcher = "ores_batch_" + sanitize(entry.getKey());
                out.append("__kernel void ").append(dispatcher).append("() {\n");
                out.append("  ulong __ores_task = (ulong)get_global_id(0);\n");
                out.append("  switch (__ores_task) {\n");
                for (int i = 0; i < targets.size(); i++) {
                    out.append("    case ").append(i).append("UL: ")
                            .append(targets.get(i).helperSymbol).append("(); break;\n");
                }
                out.append("    default: break;\n");
                out.append("  }\n");
                out.append("}\n\n");
            }

            batches.add(new GpuBatch(
                    entry.getKey(),
                    dispatcher,
                    targets.size(),
                    targets.stream().map(target -> target.kernelSymbol).toList()));
        }
        return List.copyOf(batches);
    }

    private void emitHelper(StringBuilder out, Target target, Ast.TypeRef resultType) {
        out.append("static inline ").append(cScalar(resultType)).append(' ')
                .append(target.helperSymbol).append('(')
                .append(parameterList(target.parameters, target.module)).append(") {\n");
        LoweringEnv env = parameterEnv(target.parameters, target.module);
        emitStatements(out, target.module, target.body, env, "  ");
        out.append("}\n\n");
    }

    private KernelEmission emitKernel(StringBuilder out, Target target, Ast.TypeRef resultType) {
        ParallelLoop parallelLoop = findParallelLoop(target, resultType);
        ExecutionShape shape = parallelLoop == null ? ExecutionShape.SINGLE_WORK_ITEM : ExecutionShape.DATA_PARALLEL_1D;

        out.append("__kernel void ").append(target.kernelSymbol).append('(');
        String params = parameterList(target.parameters, target.module);
        out.append(params);
        if (!isVoid(resultType)) {
            if (!params.isEmpty()) out.append(", ");
            out.append("__global ").append(cScalar(resultType)).append("* restrict __ores_out");
        }
        out.append(") {\n");

        String launchExtent = null;
        if (parallelLoop != null) {
            LoweringEnv env = parameterEnv(target.parameters, target.module);
            Ast.BindingStmt initializer = (Ast.BindingStmt) parallelLoop.loop.initializer();
            Ast.TypeRef inductionType = initializer.declaredType() == null ? Ast.TypeRef.simple("i64") : resolveAlias(initializer.declaredType(), target.module);
            env.define(initializer.name(), inductionType);
            out.append("  ").append(cScalar(inductionType)).append(' ').append(sanitize(initializer.name()))
                    .append(" = (").append(cScalar(inductionType)).append(")get_global_id(0);\n");
            String bound = renderExpr(target.module, parallelLoop.bound, env);
            launchExtent = parallelLoop.operator.equals("<") ? bound : "(" + bound + " + 1)";
            out.append("  if (!(").append(sanitize(initializer.name())).append(' ')
                    .append(parallelLoop.operator).append(' ').append(bound).append(")) return;\n");
            emitStatements(out, target.module, parallelLoop.loop.body(), env, "  ");
        } else {
            out.append("  if (get_global_id(0) != 0 || get_global_id(1) != 0 || get_global_id(2) != 0) return;\n");
            String args = argumentNames(target.parameters);
            if (isVoid(resultType)) {
                out.append("  ").append(target.helperSymbol).append('(').append(args).append(");\n");
            } else {
                out.append("  __ores_out[0] = ").append(target.helperSymbol).append('(').append(args).append(");\n");
            }
        }
        out.append("}\n\n");

        List<GpuParameter> parameters = target.parameters.stream()
                .map(param -> metadataParameter(param, target.module)).toList();
        GpuKernel meta = new GpuKernel(
                target.sourceName,
                target.helperSymbol,
                target.kernelSymbol,
                target.kind,
                target.mode,
                shape,
                target.batchId,
                target.batchIndex,
                parameters,
                oresType(resultType),
                launchExtent);
        return new KernelEmission(meta);
    }

    private ParallelLoop findParallelLoop(Target target, Ast.TypeRef resultType) {
        if (!isVoid(resultType)) return null;
        List<Ast.Stmt> body = target.body;
        Ast.ForStmt loop = null;
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ReturnStmt ret && ret.value() == null) continue;
            if (stmt instanceof Ast.ForStmt candidate && loop == null) {
                loop = candidate;
                continue;
            }
            return null;
        }
        if (loop == null) return null;
        if (!(loop.initializer() instanceof Ast.BindingStmt init)) return null;
        if (!(init.initializer() instanceof Ast.LiteralExpr zero)
                || !(zero.value() instanceof Long n) || n != 0L) return null;
        if (!(loop.condition() instanceof Ast.BinaryExpr condition)
                || !(condition.operator().equals("<") || condition.operator().equals("<="))
                || !(condition.left() instanceof Ast.NameExpr left)
                || !left.name().equals(init.name())) return null;
        if (!isCanonicalIncrement(loop.update(), init.name())) return null;

        LinkedHashSet<String> mutableBuffers = new LinkedHashSet<>();
        for (Ast.Param param : target.parameters) {
            Ast.TypeRef type = resolveAlias(param.type(), target.module);
            if (param.mutable() && isBuffer(type)) mutableBuffers.add(param.name());
        }
        if (!parallelBoundSafe(condition.right(), mutableBuffers)) return null;
        if (!parallelBodySafe(target.module, loop.body(), init.name(), mutableBuffers)) return null;

        return new ParallelLoop(loop, condition.operator(), condition.right());
    }

    private boolean parallelBoundSafe(Ast.Expr expr, Set<String> mutableBuffers) {
        if (expr instanceof Ast.LiteralExpr) return true;
        if (expr instanceof Ast.NameExpr name) return !mutableBuffers.contains(name.name());
        if (expr instanceof Ast.UnaryExpr unary) return parallelBoundSafe(unary.operand(), mutableBuffers);
        if (expr instanceof Ast.BinaryExpr binary) {
            return parallelBoundSafe(binary.left(), mutableBuffers)
                    && parallelBoundSafe(binary.right(), mutableBuffers);
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            return parallelBoundSafe(conditional.condition(), mutableBuffers)
                    && parallelBoundSafe(conditional.whenTrue(), mutableBuffers)
                    && parallelBoundSafe(conditional.whenFalse(), mutableBuffers);
        }
        return false;
    }

    private boolean parallelBodySafe(String module, List<Ast.Stmt> statements, String induction, Set<String> mutableBuffers) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) {
                if (!parallelExprSafe(module, binding.initializer(), induction, mutableBuffers)) return false;
            } else if (stmt instanceof Ast.ReturnStmt returned) {
                if (returned.value() != null && !parallelExprSafe(module, returned.value(), induction, mutableBuffers)) return false;
            } else if (stmt instanceof Ast.ExprStmt expression) {
                if (!parallelExprSafe(module, expression.expression(), induction, mutableBuffers)) return false;
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    if (!parallelExprSafe(module, branch.condition(), induction, mutableBuffers)
                            || !parallelBodySafe(module, branch.body(), induction, mutableBuffers)) return false;
                }
                if (!parallelBodySafe(module, conditional.elseBody(), induction, mutableBuffers)) return false;
            } else {
                // Nested loops, destructuring, for-of, defer, and try are intentionally not
                // auto-parallelized. They still lower through the single-work-item kernel.
                return false;
            }
        }
        return true;
    }

    private boolean parallelExprSafe(String module, Ast.Expr expr, String induction, Set<String> mutableBuffers) {
        if (expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr) return true;
        if (expr instanceof Ast.UnaryExpr unary) {
            return parallelExprSafe(module, unary.operand(), induction, mutableBuffers);
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            return parallelExprSafe(module, binary.left(), induction, mutableBuffers)
                    && parallelExprSafe(module, binary.right(), induction, mutableBuffers);
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            return parallelExprSafe(module, conditional.condition(), induction, mutableBuffers)
                    && parallelExprSafe(module, conditional.whenTrue(), induction, mutableBuffers)
                    && parallelExprSafe(module, conditional.whenFalse(), induction, mutableBuffers);
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            return parallelExprSafe(module, assignment.target(), induction, mutableBuffers)
                    && parallelExprSafe(module, assignment.value(), induction, mutableBuffers);
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            if (indexed.receiver() instanceof Ast.NameExpr buffer && mutableBuffers.contains(buffer.name())) {
                return indexed.index() instanceof Ast.NameExpr index && index.name().equals(induction);
            }
            return parallelExprSafe(module, indexed.receiver(), induction, mutableBuffers)
                    && parallelExprSafe(module, indexed.index(), induction, mutableBuffers);
        }
        if (expr instanceof Ast.CallExpr) {
            // A called GPU helper may touch a mutable buffer in a pattern this local loop
            // analysis cannot prove race-free, so keep the parent kernel single-work-item.
            return false;
        }
        return false;
    }

    private boolean isCanonicalIncrement(Ast.Expr update, String induction) {
        if (!(update instanceof Ast.AssignExpr assignment)
                || !(assignment.target() instanceof Ast.NameExpr target)
                || !target.name().equals(induction)
                || !(assignment.value() instanceof Ast.BinaryExpr add)
                || !add.operator().equals("+")) return false;
        if (add.left() instanceof Ast.NameExpr left
                && left.name().equals(induction)
                && add.right() instanceof Ast.LiteralExpr one
                && one.value() instanceof Long n && n == 1L) return true;
        return add.right() instanceof Ast.NameExpr right
                && right.name().equals(induction)
                && add.left() instanceof Ast.LiteralExpr one
                && one.value() instanceof Long n && n == 1L;
    }

    private void emitStatements(StringBuilder out, String module, List<Ast.Stmt> statements, LoweringEnv parent, String indent) {
        LoweringEnv env = new LoweringEnv(parent);
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) {
                Ast.TypeRef type = binding.declaredType() != null
                        ? resolveAlias(binding.declaredType(), module)
                        : inferExprType(module, binding.initializer(), env);
                requireLocalScalar(type, binding.name());
                String qualifier = binding.kind() == Ast.BindingKind.LET ? "" : "const ";
                out.append(indent).append(qualifier).append(cScalar(type)).append(' ')
                        .append(sanitize(binding.name())).append(" = ")
                        .append(renderExpr(module, binding.initializer(), env)).append(";\n");
                env.define(binding.name(), type);
            } else if (stmt instanceof Ast.ReturnStmt returned) {
                out.append(indent).append("return");
                if (returned.value() != null) out.append(' ').append(renderExpr(module, returned.value(), env));
                out.append(";\n");
            } else if (stmt instanceof Ast.ExprStmt expression) {
                out.append(indent).append(renderExpr(module, expression.expression(), env)).append(";\n");
            } else if (stmt instanceof Ast.IfStmt conditional) {
                boolean first = true;
                for (Ast.IfBranch branch : conditional.branches()) {
                    out.append(indent).append(first ? "if (" : "else if (")
                            .append(renderExpr(module, branch.condition(), env)).append(") {\n");
                    emitStatements(out, module, branch.body(), env, indent + "  ");
                    out.append(indent).append("}\n");
                    first = false;
                }
                if (!conditional.elseBody().isEmpty()) {
                    out.append(indent).append("else {\n");
                    emitStatements(out, module, conditional.elseBody(), env, indent + "  ");
                    out.append(indent).append("}\n");
                }
            } else if (stmt instanceof Ast.ForStmt loop) {
                LoweringEnv loopEnv = new LoweringEnv(env);
                out.append(indent).append("for (");
                if (loop.initializer() instanceof Ast.BindingStmt binding) {
                    Ast.TypeRef type = binding.declaredType() != null
                            ? resolveAlias(binding.declaredType(), module)
                            : inferExprType(module, binding.initializer(), loopEnv);
                    requireLocalScalar(type, binding.name());
                    out.append(binding.kind() == Ast.BindingKind.LET ? "" : "const ")
                            .append(cScalar(type)).append(' ').append(sanitize(binding.name())).append(" = ")
                            .append(renderExpr(module, binding.initializer(), loopEnv));
                    loopEnv.define(binding.name(), type);
                } else if (loop.initializer() instanceof Ast.ExprStmt expression) {
                    out.append(renderExpr(module, expression.expression(), loopEnv));
                } else if (loop.initializer() != null) {
                    throw new IllegalArgumentException("GPU for-loop initializer is not lowerable");
                }
                out.append("; ");
                if (loop.condition() != null) out.append(renderExpr(module, loop.condition(), loopEnv));
                out.append("; ");
                if (loop.update() != null) out.append(renderExpr(module, loop.update(), loopEnv));
                out.append(") {\n");
                emitStatements(out, module, loop.body(), loopEnv, indent + "  ");
                out.append(indent).append("}\n");
            } else {
                throw new IllegalArgumentException("GPU statement reached lowering without a representation: "
                        + stmt.getClass().getSimpleName());
            }
        }
    }

    private String renderExpr(String module, Ast.Expr expr, LoweringEnv env) {
        if (expr instanceof Ast.LiteralExpr literal) return renderLiteral(literal.value());
        if (expr instanceof Ast.NameExpr name) return sanitize(name.name());
        if (expr instanceof Ast.UnaryExpr unary) {
            return "(" + unary.operator() + renderExpr(module, unary.operand(), env) + ")";
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            String left = renderExpr(module, binary.left(), env);
            String right = renderExpr(module, binary.right(), env);
            String op = binary.operator().equals("|") ? "||" : binary.operator().equals(",") ? "&&" : binary.operator();
            if (op.equals("%") && isFloating(inferExprType(module, binary, env))) {
                return "fmod(" + left + ", " + right + ")";
            }
            return "(" + left + " " + op + " " + right + ")";
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            return "(" + renderExpr(module, assignment.target(), env) + " = "
                    + renderExpr(module, assignment.value(), env) + ")";
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            return "(" + renderExpr(module, conditional.condition(), env) + " ? "
                    + renderExpr(module, conditional.whenTrue(), env) + " : "
                    + renderExpr(module, conditional.whenFalse(), env) + ")";
        }
        if (expr instanceof Ast.CallExpr call) {
            Target target = resolveCallTarget(module, call);
            if (target == null) throw new IllegalArgumentException("GPU call target could not be lowered");
            String args = String.join(", ", call.arguments().stream().map(arg -> renderExpr(module, arg, env)).toList());
            return target.helperSymbol + "(" + args + ")";
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            return renderExpr(module, indexed.receiver(), env) + "[" + renderExpr(module, indexed.index(), env) + "]";
        }
        throw new IllegalArgumentException("GPU expression reached lowering without a representation: "
                + expr.getClass().getSimpleName());
    }

    private String renderLiteral(Object value) {
        if (value instanceof Boolean b) return b ? "((uchar)1)" : "((uchar)0)";
        if (value instanceof Long n) return Long.toString(n) + "L";
        if (value instanceof Double n) {
            if (Double.isNaN(n) || Double.isInfinite(n)) {
                throw new IllegalArgumentException("non-finite floating-point literals are not supported in GPU code");
            }
            String text = Double.toString(n);
            return text.contains(".") || text.contains("e") || text.contains("E") ? text : text + ".0";
        }
        throw new IllegalArgumentException("literal is not lowerable to GPU code: " + value);
    }

    private Ast.TypeRef inferLambdaResult(Target target) {
        LoweringEnv env = parameterEnv(target.parameters, target.module);
        ArrayList<Ast.TypeRef> returns = new ArrayList<>();
        collectReturnTypes(target.module, target.body, env, returns);
        if (returns.isEmpty()) return Ast.TypeRef.simple("void");
        Ast.TypeRef result = returns.getFirst();
        for (int i = 1; i < returns.size(); i++) result = joinTypes(target.module, result, returns.get(i));
        result = resolveAlias(result, target.module);
        if (isBuffer(result) || result.isTupleType()) {
            throw new IllegalArgumentException("GPU lambdas must return a scalar or void; write buffer results through mutable parameters");
        }
        cScalar(result);
        return result;
    }

    private void collectReturnTypes(String module, List<Ast.Stmt> statements, LoweringEnv parent, List<Ast.TypeRef> out) {
        LoweringEnv env = new LoweringEnv(parent);
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) {
                Ast.TypeRef type = binding.declaredType() != null
                        ? resolveAlias(binding.declaredType(), module)
                        : inferExprType(module, binding.initializer(), env);
                env.define(binding.name(), type);
            } else if (stmt instanceof Ast.ReturnStmt returned) {
                out.add(returned.value() == null ? Ast.TypeRef.simple("void") : inferExprType(module, returned.value(), env));
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) collectReturnTypes(module, branch.body(), env, out);
                collectReturnTypes(module, conditional.elseBody(), env, out);
            } else if (stmt instanceof Ast.ForStmt loop) {
                LoweringEnv loopEnv = new LoweringEnv(env);
                if (loop.initializer() instanceof Ast.BindingStmt binding) {
                    Ast.TypeRef type = binding.declaredType() != null
                            ? resolveAlias(binding.declaredType(), module)
                            : inferExprType(module, binding.initializer(), loopEnv);
                    loopEnv.define(binding.name(), type);
                }
                collectReturnTypes(module, loop.body(), loopEnv, out);
            }
        }
    }

    private Ast.TypeRef inferExprType(String module, Ast.Expr expr, LoweringEnv env) {
        if (expr instanceof Ast.LiteralExpr literal) {
            if (literal.value() instanceof Long) return Ast.TypeRef.simple("i64");
            if (literal.value() instanceof Double) return Ast.TypeRef.simple("f64");
            if (literal.value() instanceof Boolean) return Ast.TypeRef.simple("bool");
            throw new IllegalArgumentException("GPU literal type cannot be inferred");
        }
        if (expr instanceof Ast.NameExpr name) {
            Ast.TypeRef type = env.lookup(name.name());
            if (type == null) throw new IllegalArgumentException("GPU lowering cannot resolve type of '" + name.name() + "'");
            return type;
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            if (unary.operator().equals("!")) return Ast.TypeRef.simple("bool");
            return inferExprType(module, unary.operand(), env);
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            if (Set.of(",", "|", "==", "!=", "<", "<=", ">", ">=").contains(binary.operator())) {
                return Ast.TypeRef.simple("bool");
            }
            return joinTypes(module, inferExprType(module, binary.left(), env), inferExprType(module, binary.right(), env));
        }
        if (expr instanceof Ast.AssignExpr assignment) return inferExprType(module, assignment.value(), env);
        if (expr instanceof Ast.ConditionalExpr conditional) {
            return joinTypes(module, inferExprType(module, conditional.whenTrue(), env), inferExprType(module, conditional.whenFalse(), env));
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            Ast.TypeRef receiver = resolveAlias(inferExprType(module, indexed.receiver(), env), module);
            if (!isBuffer(receiver)) throw new IllegalArgumentException("GPU index receiver is not a buffer");
            return resolveAlias(receiver.arguments().getFirst(), module);
        }
        if (expr instanceof Ast.CallExpr call) {
            Target target = resolveCallTarget(module, call);
            if (target == null) throw new IllegalArgumentException("GPU lowering cannot resolve call result type");
            return resolvedResult(target);
        }
        throw new IllegalArgumentException("GPU lowering cannot infer expression type for " + expr.getClass().getSimpleName());
    }

    private Ast.TypeRef joinTypes(String module, Ast.TypeRef a, Ast.TypeRef b) {
        a = resolveAlias(a, module);
        b = resolveAlias(b, module);
        if (sameType(module, a, b)) return a;
        if (isNumericScalar(a) && isNumericScalar(b)) {
            int rankA = numericRank(a), rankB = numericRank(b);
            return rankA >= rankB ? a : b;
        }
        if (isVoid(a) && isVoid(b)) return a;
        throw new IllegalArgumentException("GPU lowering cannot unify result types " + oresType(a) + " and " + oresType(b));
    }

    private int numericRank(Ast.TypeRef type) {
        return switch (resolveAlias(type, null).name()) {
            case "i8", "u8" -> 1;
            case "i16", "u16" -> 2;
            case "i32", "u32" -> 3;
            case "i64", "u64", "int", "uint" -> 4;
            case "f32" -> 5;
            case "f64", "float" -> 6;
            default -> 0;
        };
    }

    private Ast.TypeRef resolvedResult(Target target) {
        if (target.returnType == null) return inferLambdaResult(target);
        return resolveAlias(target.returnType, target.module);
    }

    private Target resolveCallTarget(String module, Ast.CallExpr call) {
        int arity = call.arguments().size();
        if (call.callee() instanceof Ast.NameExpr name) {
            Target local = namedByQualifiedName.get(callKey(module + "." + name.name(), arity));
            if (local != null) return local;
            String shortKey = callKey(name.name(), arity);
            if (!ambiguousNamed.contains(shortKey)) return namedByShortName.get(shortKey);
            return null;
        }
        if (call.callee() instanceof Ast.MemberExpr member && member.receiver() instanceof Ast.NameExpr owner) {
            Target moduleFunction = namedByQualifiedName.get(callKey(owner.name() + "." + member.member(), arity));
            if (moduleFunction != null) return moduleFunction;

            String classShort = owner.name() + "." + member.member();
            Target localStatic = namedByQualifiedName.get(callKey(module + "." + classShort, arity));
            if (localStatic != null) return localStatic;
            String shortKey = callKey(classShort, arity);
            if (!ambiguousNamed.contains(shortKey)) return namedByShortName.get(shortKey);
        }
        return null;
    }

    private static String callKey(String name, int arity) {
        return name + "#" + arity;
    }

    private String parameterList(List<Ast.Param> parameters, String module) {
        return String.join(", ", parameters.stream().map(param -> cParameter(param, module)).toList());
    }

    private String argumentNames(List<Ast.Param> parameters) {
        return String.join(", ", parameters.stream().map(param -> sanitize(param.name())).toList());
    }

    private String cParameter(Ast.Param param, String module) {
        Ast.TypeRef type = resolveAlias(param.type(), module);
        if (isBuffer(type)) {
            Ast.TypeRef element = resolveAlias(type.arguments().getFirst(), module);
            return "__global " + (param.mutable() ? "" : "const ") + cScalar(element)
                    + "* restrict " + sanitize(param.name());
        }
        return cScalar(type) + " " + sanitize(param.name());
    }

    private GpuParameter metadataParameter(Ast.Param param, String module) {
        Ast.TypeRef type = resolveAlias(param.type(), module);
        String declaration = cParameter(param, module);
        return new GpuParameter(
                param.name(),
                oresType(type),
                declaration.substring(0, declaration.lastIndexOf(' ')),
                isBuffer(type),
                param.mutable());
    }

    private LoweringEnv parameterEnv(List<Ast.Param> parameters, String module) {
        LoweringEnv env = new LoweringEnv(null);
        for (Ast.Param param : parameters) env.define(param.name(), resolveAlias(param.type(), module));
        return env;
    }

    private void requireLocalScalar(Ast.TypeRef type, String name) {
        type = resolveAlias(type, null);
        if (isBuffer(type) || type.isTupleType() || isVoid(type)) {
            throw new IllegalArgumentException("GPU local '" + name + "' must lower to a scalar register");
        }
        cScalar(type);
    }

    private boolean isNumericScalar(Ast.TypeRef type) {
        String name = resolveAlias(type, null).name();
        return Set.of("i8","i16","i32","i64","u8","u16","u32","u64","int","uint","f32","f64","float").contains(name);
    }

    private boolean isFloating(Ast.TypeRef type) {
        String name = resolveAlias(type, null).name();
        return name.equals("f32") || name.equals("f64") || name.equals("float");
    }

    private String cScalar(Ast.TypeRef original) {
        Ast.TypeRef type = resolveAlias(original, null);
        if (type.isTupleType() || isBuffer(type)) {
            throw new IllegalArgumentException("type is not a scalar GPU ABI value: " + oresType(type));
        }
        return switch (type.name()) {
            case "void" -> "void";
            case "bool", "Bool", "u8" -> "uchar";
            case "i8" -> "char";
            case "i16" -> "short";
            case "u16" -> "ushort";
            case "i32" -> "int";
            case "u32" -> "uint";
            case "i64", "int" -> "long";
            case "u64", "uint" -> "ulong";
            case "f32" -> "float";
            case "f64", "float" -> "double";
            default -> throw new IllegalArgumentException("type has no OpenCL C scalar lowering: " + oresType(type));
        };
    }

    private boolean isBuffer(Ast.TypeRef type) {
        type = resolveAlias(type, null);
        return (type.name().equals("Array") || type.name().equals("List")) && type.arguments().size() == 1;
    }

    private boolean isVoid(Ast.TypeRef type) {
        return resolveAlias(type, null).name().equals("void");
    }

    private Ast.TypeRef resolveAlias(Ast.TypeRef original, String module) {
        Ast.TypeRef type = original;
        Set<String> seen = new HashSet<>();
        while (type != null && !type.isTupleType() && !type.isUnion() && !type.isRecordType()) {
            Ast.TypeAliasDecl alias = module == null ? null : aliases.get(module + "." + type.name());
            if (alias == null && !ambiguousAliases.contains(type.name())) alias = aliases.get(type.name());
            if (alias == null) break;
            if (!alias.genericParameters().isEmpty() || !type.arguments().isEmpty()) break;
            if (!seen.add(alias.name())) throw new IllegalArgumentException("GPU type alias cycle involving " + alias.name());
            type = alias.target();
        }
        return type;
    }

    private boolean sameType(String module, Ast.TypeRef a, Ast.TypeRef b) {
        return oresType(resolveAlias(a, module)).equals(oresType(resolveAlias(b, module)));
    }

    private String oresType(Ast.TypeRef type) {
        if (type == null) return "<inferred>";
        if (type.isTupleType()) return "[" + String.join(",", type.arguments().stream().map(this::oresType).toList()) + "]";
        if (type.isUnion()) return String.join("|", type.arguments().stream().map(this::oresType).toList());
        if (type.arguments().isEmpty()) return type.name();
        return type.name() + "<" + String.join(",", type.arguments().stream().map(this::oresType).toList()) + ">";
    }

    private String symbolBase(String module, String name, int arity) {
        return sanitize(module) + "_" + sanitize(name) + "_a" + arity;
    }

    private static String sanitize(String name) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_') out.append(c);
            else out.append('_');
        }
        if (out.isEmpty() || Character.isDigit(out.charAt(0))) out.insert(0, '_');
        return out.toString();
    }

    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record KernelEmission(GpuKernel metadata) { }
    private record ParallelLoop(Ast.ForStmt loop, String operator, Ast.Expr bound) { }

    private static final class LoweringEnv {
        private final LoweringEnv parent;
        private final Map<String, Ast.TypeRef> types = new HashMap<>();

        private LoweringEnv(LoweringEnv parent) { this.parent = parent; }

        private void define(String name, Ast.TypeRef type) {
            if (types.putIfAbsent(name, type) != null) {
                throw new IllegalArgumentException("duplicate GPU lowering binding '" + name + "'");
            }
        }

        private Ast.TypeRef lookup(String name) {
            Ast.TypeRef own = types.get(name);
            return own != null ? own : parent == null ? null : parent.lookup(name);
        }
    }

    private static final class Target {
        private final String module;
        private final String ownerClass;
        private final String sourceName;
        private final String helperSymbol;
        private final String kernelSymbol;
        private final KernelKind kind;
        private final Ast.GpuMode mode;
        private final String batchId;
        private final int batchIndex;
        private final List<Ast.Param> parameters;
        private final Ast.TypeRef returnType;
        private final List<Ast.Stmt> body;

        private Target(String module, String ownerClass, String sourceName, String base, KernelKind kind,
                       Ast.GpuMode mode, String batchId, int batchIndex, List<Ast.Param> parameters,
                       Ast.TypeRef returnType, List<Ast.Stmt> body) {
            this.module = module;
            this.ownerClass = ownerClass;
            this.sourceName = sourceName;
            this.helperSymbol = "ores_dev_" + base;
            this.kernelSymbol = "ores_kernel_" + base;
            this.kind = kind;
            this.mode = mode;
            this.batchId = batchId;
            this.batchIndex = batchIndex;
            this.parameters = List.copyOf(parameters);
            this.returnType = returnType;
            this.body = List.copyOf(body);
        }

        private static Target namedFunction(String module, Ast.FunctionDecl fn, String base) {
            return new Target(module, null, fn.name(), base, KernelKind.FUNCTION, Ast.GpuMode.SINGLE,
                    null, 0, fn.parameters(), fn.returnType(), fn.body());
        }

        private static Target staticFunction(String module, String ownerClass, String sourceName,
                                             Ast.MethodDecl method, String base) {
            return new Target(module, ownerClass, sourceName, base, KernelKind.STATIC_FUNCTION, Ast.GpuMode.SINGLE,
                    null, 0, method.parameters(), method.returnType(), method.body());
        }

        private static Target lambda(String module, String sourceName, Ast.LambdaExpr lambda, String base,
                                     Ast.GpuMode mode, String batchId, int batchIndex) {
            return new Target(module, null, sourceName, base, KernelKind.LAMBDA, mode,
                    batchId, batchIndex, lambda.parameters(), null, lambda.blockBody());
        }
    }
}
