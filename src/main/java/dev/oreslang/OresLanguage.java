package dev.oreslang;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.TruffleLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.nodes.OresEvalRootNode;
import dev.oreslang.nodes.OresInteropRootNode;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.AsyncRuntime;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.OresScheduler;
import org.graalvm.polyglot.SandboxPolicy;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@TruffleLanguage.Registration(
        id = OresLanguage.ID,
        name = "Oreslang",
        version = "0.1.0",
        defaultMimeType = OresLanguage.MIME_TYPE,
        characterMimeTypes = OresLanguage.MIME_TYPE,
        contextPolicy = TruffleLanguage.ContextPolicy.EXCLUSIVE,
        sandbox = SandboxPolicy.UNTRUSTED,
        website = "https://github.com/ores-truffle-oreslang/oreslang-source.java")
public final class OresLanguage extends TruffleLanguage<OresContext> {
    public static final String ID = "ores";
    public static final String MIME_TYPE = "application/x-oreslang";

    @Override
    protected OresContext createContext(Env env) {
        return new OresContext(this, env);
    }

    /**
     * Host-owned actor dispatcher and async workers may enter the context.
     * Guest source still has no raw thread-creation authority; that remains controlled by
     * IsolatePolicy and the Polyglot Context builder.
     *
     * Strict/adversarial contexts serialize actor guest turns in OresContext.
     * Non-adversarial contexts may execute independent actor turns concurrently.
     */
    @Override
    protected boolean isThreadAccessAllowed(Thread thread, boolean singleThreaded) {
        return singleThreaded
                || ActorRuntime.isActorCarrierThread()
                || AsyncRuntime.isAsyncCarrierThread()
                || OresScheduler.isSchedulerCarrierThread();
    }

    @Override
    protected void initializeMultiThreading(OresContext context) {
        // All mutable language state used by actor turns is context-owned,
        // actor-owned, immutable, or explicitly synchronized.
    }

    @Override
    protected void disposeContext(OresContext context) {
        context.close();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        var source = request.getSource();
        String text = source.getCharacters().toString();
        // Exact opt-in per source unit. Default arithmetic retains legacy semantics.
        boolean experimentalCheckedI64 = OresCompiler.checkedI64SourceOptIn(text);
        Ast.Program program = OresCompiler.parseAndTypeCheck(text);
        if (experimentalCheckedI64) verifyExperimentalCheckedI64Subset(program);
        String codeUnitId = source.getPath();
        if (codeUnitId == null || codeUnitId.isBlank()) {
            codeUnitId = source.getName();
        } else {
            try {
                codeUnitId = Path.of(codeUnitId).toAbsolutePath().normalize().toString().replace('\\', '/');
            } catch (InvalidPathException invalidPath) {
                throw new IllegalArgumentException("invalid Oreslang source path identity", invalidPath);
            }
        }
        if (codeUnitId == null || codeUnitId.isBlank()) codeUnitId = "<anonymous>";
        RootCallTarget evaluator = new OresEvalRootNode(this, program, codeUnitId, experimentalCheckedI64).getCallTarget();
        return new OresInteropRootNode(this, evaluator).getCallTarget();
    }

    /**
     * The checked arithmetic experiment has no certified actor, async,
     * superclass, import or source-level exceptional effect ABI yet.
     * Reject those source units before creating any executable guest root.
     * Default/unmarked source has no new admission restrictions.
     */
    private static void verifyExperimentalCheckedI64Subset(Ast.Program program) {
        if (program.namespace() != null || !program.imports().isEmpty()) {
            throw new IllegalArgumentException("checked-i64-v1 rejects namespace/import boundaries");
        }
        Map<String, Set<Integer>> signatures = new HashMap<>();
        Set<String> declaredNames = new HashSet<>();
        int functionCount = 0;
        boolean hasMain = false;
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (!(declaration instanceof Ast.FunctionDecl fn)
                        || fn.kind() != Ast.CallableKind.FNC
                        || fn.actorKind() != Ast.ActorKind.NONE
                        || fn.async() || fn.suspends() || fn.generator()
                        || fn.trapped() || fn.structural() || fn.nonLexical()
                        || !fn.genericParameters().isEmpty()
                        || !fn.annotations().isEmpty()
                        || !isCheckedI64Identifier(fn.name())
                        || fn.parameters().size() > 32
                        || fn.parameters().stream().anyMatch(p ->
                                !isCheckedI64Identifier(p.name()))
                        || !isCheckedI64Scalar(fn.returnType())
                        || fn.parameters().stream().anyMatch(p ->
                                p.mutable() || p.structural() || !isCheckedI64Scalar(p.type()))
                        || fn.body().size() != 1
                        || !(fn.body().getFirst() instanceof Ast.ReturnStmt ret)
                        || ret.value() == null) {
                    throw new IllegalArgumentException(
                            "checked-i64-v1 requires synchronous scalar functions; "
                            + "actor, class, import and effect lowering are unsupported");
                }
                // The host-native LLVM checked profile exposes a diagnostic
                // through libc puts and synthesizes an internal guest-main
                // symbol. These names cannot be portable checked callables.
                if ("puts".equals(fn.name()) || "__oreslang_guest_main".equals(fn.name())) {
                    throw new IllegalArgumentException(
                            "checked-i64-v1 rejects reserved native callable " + fn.name());
                }
                if (++functionCount > 32 || !declaredNames.add(fn.name())) {
                    throw new IllegalArgumentException(
                            "checked-i64-v1 rejects overloaded or duplicate functions and modules over 32 callables");
                }
                if ("main".equals(fn.name())) {
                    if (!fn.parameters().isEmpty()
                            || !"int".equals(fn.returnType().name())) {
                        throw new IllegalArgumentException(
                                "checked-i64-v1 main requires zero arguments and int result");
                    }
                    hasMain = true;
                }
                signatures.computeIfAbsent(fn.name(), ignored -> new HashSet<>())
                        .add(fn.parameters().size());
            }
        }
        if (!hasMain) {
            throw new IllegalArgumentException("checked-i64-v1 requires a scalar main entrypoint");
        }
        Map<String, Set<String>> edges = new HashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                Ast.FunctionDecl fn = (Ast.FunctionDecl) declaration;
                Set<String> parameters = new HashSet<>();
                for (Ast.Param parameter : fn.parameters()) {
                    if (!parameters.add(parameter.name())) {
                        throw new IllegalArgumentException(
                                "checked-i64-v1 duplicate scalar parameter name");
                    }
                }
                Ast.ReturnStmt ret = (Ast.ReturnStmt) fn.body().getFirst();
                Set<String> directCalls = new HashSet<>();
                checkExperimentalCheckedI64Expr(ret.value(), parameters,
                        signatures, directCalls, new int[]{0}, 0);
                edges.put(fn.name(), directCalls);
            }
        }
        Set<String> active = new HashSet<>();
        Set<String> completed = new HashSet<>();
        for (String name : edges.keySet()) {
            checkExperimentalCheckedI64Calls(name, edges, active, completed);
        }
    }

    private static void checkExperimentalCheckedI64Calls(String name,
            Map<String, Set<String>> edges, Set<String> active, Set<String> completed) {
        if (completed.contains(name)) return;
        if (!active.add(name)) {
            throw new IllegalArgumentException("checked-i64-v1 recursive call graph is unsupported");
        }
        for (String target : edges.getOrDefault(name, Set.of())) {
            checkExperimentalCheckedI64Calls(target, edges, active, completed);
        }
        active.remove(name);
        completed.add(name);
    }

    /**
     * Whitelist only side-effect-free integer/bool expressions which the
     * checked experimental runtime has actually exercised. A nested match,
     * trap, float promotion, dynamic call or runtime operation must not bypass
     * the top-level signature guard. Limit depth before recursive descent.
     */
    private static void checkExperimentalCheckedI64Expr(Ast.Expr expr,
            Set<String> parameters, Map<String, Set<Integer>> signatures,
            Set<String> directCalls, int[] visited, int depth) {
        if (expr == null || depth > 48 || ++visited[0] > 2048) {
            throw new IllegalArgumentException("checked-i64-v1 expression depth/size or null operand");
        }
        if (expr instanceof Ast.LiteralExpr literal) {
            if (literal.value() instanceof Long || literal.value() instanceof Integer
                    || literal.value() instanceof Boolean) return;
        } else if (expr instanceof Ast.NameExpr name) {
            if (parameters.contains(name.name())) return;
        } else if (expr instanceof Ast.BinaryExpr binary) {
            if (Set.of("+", "-", "*", "<", "<=", ">", ">=", "==", "!=",
                    "&&", "||", "eq", "neq").contains(binary.operator())) {
                checkExperimentalCheckedI64Expr(binary.left(), parameters, signatures,
                        directCalls, visited, depth + 1);
                checkExperimentalCheckedI64Expr(binary.right(), parameters, signatures,
                        directCalls, visited, depth + 1);
                return;
            }
        } else if (expr instanceof Ast.UnaryExpr unary) {
            if ("-".equals(unary.operator()) || "!".equals(unary.operator())) {
                checkExperimentalCheckedI64Expr(unary.operand(), parameters, signatures,
                        directCalls, visited, depth + 1);
                return;
            }
        } else if (expr instanceof Ast.ConditionalExpr conditional) {
            checkExperimentalCheckedI64Expr(conditional.condition(), parameters, signatures,
                    directCalls, visited, depth + 1);
            checkExperimentalCheckedI64Expr(conditional.whenTrue(), parameters, signatures,
                    directCalls, visited, depth + 1);
            checkExperimentalCheckedI64Expr(conditional.whenFalse(), parameters, signatures,
                    directCalls, visited, depth + 1);
            return;
        } else if (expr instanceof Ast.CallExpr call) {
            if (!call.typeArgumentsPresent() && call.typeArguments().isEmpty()
                    && call.callee() instanceof Ast.NameExpr target
                    && signatures.getOrDefault(target.name(), Set.of()).contains(call.arguments().size())) {
                directCalls.add(target.name());
                for (Ast.Expr argument : call.arguments()) {
                    checkExperimentalCheckedI64Expr(argument, parameters, signatures,
                            directCalls, visited, depth + 1);
                }
                return;
            }
        }
        throw new IllegalArgumentException(
                "checked-i64-v1 unsupported expression AST kind: "
                + expr.getClass().getSimpleName());
    }

    private static boolean isCheckedI64Identifier(String name) {
        return name != null && name.length() <= 128
                && name.matches("[A-Za-z_][A-Za-z_0-9]*");
    }

    private static boolean isCheckedI64Scalar(Ast.TypeRef type) {
        return type != null && !type.inferArguments() && type.arguments().isEmpty()
                && (type.name().equals("int") || type.name().equals("bool"));
    }

}
