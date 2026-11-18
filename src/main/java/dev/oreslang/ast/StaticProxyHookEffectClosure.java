package dev.oreslang.ast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fail-closed, closed-world call-graph admission for experimental before hooks.
 *
 * This proves only that the reachable source body uses the narrow supported
 * synchronous expression subset and local, statically identified helpers.
 * It does not prove host IO purity, target-method purity, or Native AOT.
 * Unresolved dispatch and recursive paths are rejected, never assumed safe.
 */
final class StaticProxyHookEffectClosure {
    private static final int MAX_NODES = 4096;
    private static final int MAX_DEPTH = 32;
    private static final Set<String> SCALAR_TYPES =
            Set.of("int", "float", "bool", "String", "string", "void");

    private final Ast.ClassDecl owner;
    private final Map<Ast.MethodArity, Ast.MethodDecl> methods = new HashMap<>();
    private final Set<Ast.MethodArity> visited = new HashSet<>();
    private final Set<Ast.MethodArity> active = new HashSet<>();
    private int nodes;

    private StaticProxyHookEffectClosure(Ast.Program program, Ast.ClassDecl owner) {
        this.owner = owner;
        // An allowed stdio reference must denote the runtime namespace, not
        // an imported or locally declared value/class of the same spelling.
        for (Ast.ImportDecl imported : program.imports()) {
            if (imported.names().contains("stdio") || "stdio".equals(imported.namespace())) {
                throw fail("stdio name is shadowed by an import");
            }
        }
        for (Ast.ModuleDecl module : program.modules()) {
            if (module.name().equals("stdio")) throw fail("stdio namespace is shadowed");
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.ClassDecl klass && klass.name().equals("stdio")
                        || decl instanceof Ast.FunctionDecl fn && fn.name().equals("stdio")
                        || decl instanceof Ast.FieldDecl field && field.name().equals("stdio")
                        || decl instanceof Ast.TypeAliasDecl alias && alias.name().equals("stdio")
                        || decl instanceof Ast.InterfaceDecl iface && iface.name().equals("stdio")) {
                    throw fail("stdio name is shadowed by a declaration");
                }
            }
        }
        for (Ast.FieldDecl field : owner.fields()) {
            if (field.name().equals("stdio") || field.name().equals("__proxy_target")
                    || field.name().equals("__proxy_hooks")) {
                throw fail("interceptor field conflicts with compiler-owned or builtin name: "
                        + owner.name() + "." + field.name());
            }
        }
        for (Ast.MethodDecl method : owner.methods()) {
            if (methods.putIfAbsent(method.overloadIdentity(), method) != null) {
                throw fail("ambiguous interceptor call graph slot: "
                        + owner.name() + "." + method.name() + "/" + method.arity());
            }
        }
    }

    static void verify(Ast.Program program, Ast.ClassDecl owner, Ast.MethodDecl hook) {
        new StaticProxyHookEffectClosure(program, owner).visitMethod(hook, 0);
    }

    private void budget(int depth) {
        if (depth > MAX_DEPTH || ++nodes > MAX_NODES) {
            throw fail("interceptor call graph exceeds depth/node budget");
        }
    }

    private void visitMethod(Ast.MethodDecl method, int depth) {
        budget(depth);
        Ast.MethodArity slot = method.overloadIdentity();
        if (visited.contains(slot)) return;
        if (!active.add(slot)) {
            throw fail("recursive interceptor helper dispatch: "
                    + owner.name() + "." + slot.name() + "/" + slot.arity());
        }
        try {
            if (method.isStatic() || method.isAbstract() || method.async()
                    || method.suspends() || method.structural()
                    || method.explicitReceiverType() != null
                    || !method.genericParameters().isEmpty() || !method.effects().isEmpty()
                    || !method.annotations().isEmpty() || !scalar(method.returnType())) {
                throw fail("interceptor helper has unverified callable effects or signature: "
                        + owner.name() + "." + method.name());
            }
            Set<String> params = new HashSet<>();
            for (Ast.Param param : method.parameters()) {
                if (param.mutable() || param.structural() || !scalar(param.type())
                        || !params.add(param.name()) || param.name().equals("self")
                        || param.name().equals("stdio")
                        || param.name().equals("__proxy_target")
                        || param.name().equals("__proxy_hooks")) {
                    throw fail("interceptor helper has unsafe parameter contract: "
                            + owner.name() + "." + method.name());
                }
            }
            for (Ast.Stmt stmt : method.body()) visitStatement(stmt, depth + 1, params);
            visited.add(slot);
        } finally {
            active.remove(slot);
        }
    }

    private static boolean scalar(Ast.TypeRef type) {
        return type != null && !type.inferArguments()
                && type.arguments().isEmpty() && SCALAR_TYPES.contains(type.name());
    }

    private void visitStatement(Ast.Stmt stmt, int depth, Set<String> params) {
        budget(depth);
        if (stmt instanceof Ast.ExprStmt e) {
            visitExpression(e.expression(), depth + 1, params);
        } else if (stmt instanceof Ast.ReturnStmt r) {
            if (r.value() != null) visitExpression(r.value(), depth + 1, params);
        } else if (stmt instanceof Ast.BlockStmt b) {
            for (Ast.Stmt s : b.body()) visitStatement(s, depth + 1, params);
        } else if (stmt instanceof Ast.IfStmt condition) {
            for (Ast.IfBranch branch : condition.branches()) {
                visitExpression(branch.condition(), depth + 1, params);
                for (Ast.Stmt s : branch.body()) visitStatement(s, depth + 1, params);
            }
            for (Ast.Stmt s : condition.elseBody()) visitStatement(s, depth + 1, params);
        } else {
            // Locals/closures/loops/try/defer/select/async and future statement
            // forms require lexical identity or unwind/suspension proofs.
            throw fail("interceptor hook has unverified statement control flow: "
                    + owner.name() + "." + stmt.getClass().getSimpleName());
        }
    }

    private void visitExpression(Ast.Expr expr, int depth, Set<String> params) {
        budget(depth);
        if (expr instanceof Ast.LiteralExpr) return;
        if (expr instanceof Ast.NameExpr name) {
            if (!params.contains(name.name())) {
                throw fail("interceptor uses unverified name: " + name.name());
            }
            return;
        }
        if (expr instanceof Ast.BinaryExpr b) {
            visitExpression(b.left(), depth + 1, params);
            visitExpression(b.right(), depth + 1, params);
            return;
        }
        if (expr instanceof Ast.UnaryExpr u) {
            visitExpression(u.operand(), depth + 1, params);
            return;
        }
        if (expr instanceof Ast.ConditionalExpr c) {
            visitExpression(c.condition(), depth + 1, params);
            visitExpression(c.whenTrue(), depth + 1, params);
            visitExpression(c.whenFalse(), depth + 1, params);
            return;
        }
        if (expr instanceof Ast.CallExpr call) {
            if (call.origin() != Ast.CallOrigin.DIRECT
                    || call.typeArgumentsPresent() || !call.typeArguments().isEmpty()
                    || !(call.callee() instanceof Ast.MemberExpr callee)) {
                throw fail("interceptor call has unresolved/indirect dispatch");
            }
            for (Ast.Expr argument : call.arguments()) {
                visitExpression(argument, depth + 1, params);
            }
            if (callee.receiver() instanceof Ast.NameExpr self
                    && self.name().equals("self")) {
                Ast.MethodArity key = new Ast.MethodArity(callee.member(), call.arguments().size());
                Ast.MethodDecl target = methods.get(key);
                if (target == null) {
                    throw fail("interceptor helper call has unresolved local slot: "
                            + owner.name() + "." + key.name() + "/" + key.arity());
                }
                visitMethod(target, depth + 1);
                return;
            }
            if (callee.member().equals("write")
                    && call.arguments().size() == 1
                    && callee.receiver() instanceof Ast.MemberExpr channel
                    && channel.receiver() instanceof Ast.NameExpr ns
                    && ns.name().equals("stdio")
                    && (channel.member().equals("stdout") || channel.member().equals("stderr"))) {
                // Explicit synchronous IO is allowed, but NOT described as
                // pure or as an infallible exceptional-effect boundary.
                return;
            }
            throw fail("interceptor call has unresolved/indirect dispatch: "
                    + callee.member() + "/" + call.arguments().size());
        }
        // Member extraction, assignments, await, tasks, runtime intrinsics,
        // lambdas, structural aggregates and all new nodes fail closed.
        throw fail("interceptor expression has unverified effects/escapes: "
                + expr.getClass().getSimpleName());
    }

    private static IllegalArgumentException fail(String message) {
        return new IllegalArgumentException("AOT_PROXY_UNSUPPORTED: " + message);
    }
}
