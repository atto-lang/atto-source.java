package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Deliberately narrow checked-AST frontend gate for trusted actor callbacks.
 *
 * This audits actual Oreslang syntax after TypeChecker and OwnershipChecker,
 * unlike the manually supplied RealtimeRegionEffectModel.Plan.
 * It admits only scalar computations; no actor instance/arena allocation,
 * mailbox operation, suspension, dynamic/static call or managed-heap access is
 * presently proved. A PASS MUST NOT enable hard RT admission.
 */
public final class RealtimeActorSourceEffects {
    private RealtimeActorSourceEffects() { }
    private static final int MAX_EXPR_NESTING = 64;
    private static final int MAX_STATEMENT_NESTING = 64;
    private static final int MAX_STATEMENTS = 4096;
    private static final int MAX_PARAMS = 32;
    private static final int MAX_NAME_LENGTH = 128;

    public record Budget(long maxAstSteps) {
        public Budget {
            if (maxAstSteps < 1) reject("AST step budget must be positive");
        }
    }

    public record Report(String actor, String callback, long worstPathAstSteps,
                         int validatedMethods, String assurance) { }

    private static final class Walker {
        private int visited;

        long walk(List<Ast.Stmt> statements, Set<String> locals, int nesting) {
            if (nesting > MAX_STATEMENT_NESTING) reject("statement nesting exceeds maximum");
            long work = 0;
            boolean terminated = false;
            for (Ast.Stmt stmt : statements) {
                if (++visited > MAX_STATEMENTS) reject("AST statement count exceeds maximum");
                if (terminated) reject("unreachable statement following guaranteed return");
                if (stmt instanceof Ast.ReturnStmt ret) {
                    if (ret.value() == null) reject("void return is not admitted");
                    work = plus(work, plus(1, expression(ret.value(), locals, 0)));
                    terminated = true;
                } else if (stmt instanceof Ast.BindingStmt bind) {
                    if (bind.initializer() == null || bind.inferredTypeName() != null
                            || bind.kind() != Ast.BindingKind.CONST
                            || bind.mutableReferent() || !scalar(bind.declaredType())) {
                        reject("only initialized, immutable, explicit scalar locals are admitted");
                    }
                    long init = expression(bind.initializer(), locals, 0);
                    if (bind.name() == null || bind.name().isBlank()
                            || bind.name().length() > MAX_NAME_LENGTH
                            || !locals.add(bind.name())) reject("invalid or duplicate local binding");
                    work = plus(work, plus(1, init));
                } else if (stmt instanceof Ast.IfStmt iff) {
                    if (iff.branches().isEmpty() || iff.elseBody().isEmpty()) {
                        reject("conditional must cover all return paths");
                    }
                    long prefix = 1, maxBody = 0;
                    for (Ast.IfBranch arm : iff.branches()) {
                        prefix = plus(prefix, expression(arm.condition(), locals, 0));
                        Walker child = new Walker();
                        child.visited = visited;
                        long cost = child.walk(arm.body(), new HashSet<>(locals), nesting + 1);
                        visited = child.visited;
                        maxBody = Math.max(maxBody, cost);
                    }
                    Walker elseWalk = new Walker();
                    elseWalk.visited = visited;
                    long elseCost = elseWalk.walk(iff.elseBody(), new HashSet<>(locals), nesting + 1);
                    visited = elseWalk.visited;
                    work = plus(work, plus(prefix, Math.max(maxBody, elseCost)));
                    // If every arm ends with return, no following source is reachable.
                    terminated = allReturn(iff.branches(), iff.elseBody(), nesting + 1);
                } else {
                    reject("unverified statement: " + stmt.getClass().getSimpleName());
                }
            }
            if (!terminated) reject("critical callback path may return without a value");
            return work;
        }

        long expression(Ast.Expr expr, Set<String> locals, int nesting) {
            if (nesting > MAX_EXPR_NESTING) reject("expression nesting exceeds maximum");
            if (expr instanceof Ast.LiteralExpr lit) {
                Object v = lit.value();
                if (!(v instanceof Boolean || v instanceof Byte || v instanceof Short
                        || v instanceof Integer || v instanceof Long)) {
                    reject("only integer/boolean literals are admitted");
                }
                if (v instanceof Long l && (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE)) {
                    reject("i32 literal out of bounds");
                }
                return 1;
            }
            if (expr instanceof Ast.NameExpr name) {
                if (!locals.contains(name.name())) reject("unknown or actor-state/global reference");
                return 1;
            }
            if (expr instanceof Ast.UnaryExpr unary) {
                if (!Set.of("-", "!", "~").contains(unary.operator())) {
                    reject("unverified unary operator");
                }
                return plus(1, expression(unary.operand(), locals, nesting + 1));
            }
            if (expr instanceof Ast.BinaryExpr binary) {
                if (!Set.of("+", "-", "*", "==", "!=", "<", "<=", ">", ">=",
                        "&&", "||", "&", "|", "^").contains(binary.operator())) {
                    reject("unknown or exceptional primitive operator");
                }
                return plus(1, plus(expression(binary.left(), locals, nesting + 1),
                        expression(binary.right(), locals, nesting + 1)));
            }
            reject("unverified heap/reference/call/await/runtime expression: "
                    + expr.getClass().getSimpleName());
            return 0; // unreachable
        }
    }

    private static boolean allReturn(List<Ast.IfBranch> branches,
                                     List<Ast.Stmt> otherwise, int depth) {
        if (depth > MAX_STATEMENT_NESTING) reject("return proof nesting exceeds maximum");
        for (Ast.IfBranch branch : branches) {
            if (!endsWithReturn(branch.body(), depth + 1)) return false;
        }
        return endsWithReturn(otherwise, depth + 1);
    }

    private static boolean endsWithReturn(List<Ast.Stmt> body, int depth) {
        if (depth > MAX_STATEMENT_NESTING) reject("return proof nesting exceeds maximum");
        if (body.isEmpty()) return false;
        Ast.Stmt last = body.getLast();
        if (last instanceof Ast.ReturnStmt) return true;
        if (last instanceof Ast.IfStmt branch && !branch.elseBody().isEmpty()) {
            return allReturn(branch.branches(), branch.elseBody(), depth + 1);
        }
        return false;
    }

    /**
     * Requires a program that has already passed the real Oreslang type and
     * ownership checker. Only OresCompiler should expose this as an entrypoint.
     */
    static Report analyzeChecked(Ast.Program checked, String actorName,
                                 String callbackName, int arity, Budget budget) {
        Objects.requireNonNull(checked, "checked AST");
        Objects.requireNonNull(budget, "budget");
        if (actorName == null || actorName.isBlank()
                || callbackName == null || callbackName.isBlank() || arity < 0
                || actorName.length() > MAX_NAME_LENGTH
                || callbackName.length() > MAX_NAME_LENGTH
                || arity > MAX_PARAMS) {
            reject("actor/callback identity is missing");
        }
        if (!checked.imports().isEmpty() || checked.modules().size() != 1) {
            reject("only one closed-world module without imports is admitted");
        }
        Ast.ModuleDecl module = checked.modules().getFirst();
        if (!module.annotations().isEmpty() || !module.contracts().isEmpty()) {
            reject("module metadata/contract effects are not qualified");
        }
        Ast.ClassDecl actor = null;
        for (Ast.Decl declaration : module.declarations()) {
            // A rejecting void helper is not modeled by Java's definite-
            // assignment analysis as a guaranteed throw. Cast explicitly.
            if (!(declaration instanceof Ast.ClassDecl)) {
                reject("unknown module declaration or actor name");
            }
            Ast.ClassDecl candidate = (Ast.ClassDecl) declaration;
            if (!candidate.name().equals(actorName)) {
                reject("unknown module declaration or actor name");
            }
            if (actor != null) reject("ambiguous actor declaration");
            actor = candidate;
        }
        if (actor == null || actor.actorKind() != Ast.ActorKind.PRIVATE
                || actor.isAbstract() || actor.constructor() != null
                || !actor.fields().isEmpty() || !actor.parents().isEmpty()
                || !actor.interfaces().isEmpty() || !actor.genericParameters().isEmpty()) {
            reject("actor class requires unverified construction, state, inheritance or trust effects");
        }
        // Other methods or lifecycle hooks can allocate/throw before or
        // during this callback. Stage 0 only admits a single-method actor.
        if (actor.methods().size() != 1) {
            reject("additional actor methods and lifecycle callbacks are unverified");
        }
        Ast.MethodDecl method = null;
        for (Ast.MethodDecl candidate : actor.methods()) {
            if (candidate.name().equals(callbackName) && candidate.arity() == arity) {
                if (method != null) reject("ambiguous callback overload");
                method = candidate;
            }
        }
        if (method == null || method.isStatic() || method.isAbstract()
                || method.async() || method.suspends() || method.structural()
                || method.explicitReceiverType() != null
                || !method.annotations().isEmpty()
                || !method.genericParameters().isEmpty()
                || !scalar(method.returnType())) {
            reject("callback signature or suspension is not admitted");
        }
        if (method.parameters().size() > MAX_PARAMS) {
            reject("callback arity exceeds admitted preflight bound");
        }
        Set<String> locals = new HashSet<>();
        for (Ast.Param param : method.parameters()) {
            if (param.name() == null || param.name().isBlank()
                    || param.name().length() > MAX_NAME_LENGTH
                    || param.mutable() || param.structural() || !scalar(param.type())
                    || !locals.add(param.name())) {
                reject("only distinct immutable primitive arguments are admitted");
            }
        }
        Walker walker = new Walker();
        long cost = walker.walk(method.body(), locals, 0);
        if (cost > budget.maxAstSteps()) reject("AST step budget exceeded");
        return new Report(actorName, callbackName, cost, 1,
                "CHECKED_ACTOR_AST_SCALAR_ONLY;ACTOR_REGION_AND_VM_UNVERIFIED");
    }

    private static boolean scalar(Ast.TypeRef type) {
        return type != null && type.arguments().isEmpty()
                && !type.inferArguments()
                && Set.of("i32", "bool").contains(type.name());
    }

    private static long plus(long a, long b) {
        try { return Math.addExact(a, b); }
        catch (ArithmeticException e) {
            throw new IllegalArgumentException("RT-ACTOR-SOURCE: AST resource count overflow");
        }
    }

    private static void reject(String message) {
        throw new IllegalArgumentException("RT-ACTOR-SOURCE: " + message);
    }
}
