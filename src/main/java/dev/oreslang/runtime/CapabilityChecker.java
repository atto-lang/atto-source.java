package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;

import java.util.List;

/**
 * Language-level capability admission pass. This executes before guest
 * statements and complements Graal/OS isolation rather than replacing it.
 */
public final class CapabilityChecker {
    private CapabilityChecker() { }

    public static void check(Ast.Program program, IsolatePolicy policy) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) checkStatements(fn.body(), policy);
                else if (declaration instanceof Ast.ClassDecl klass) {
                    for (Ast.FieldDecl field : klass.fields()) if (field.initializer() != null) checkExpr(field.initializer(), policy);
                    for (Ast.MethodDecl method : klass.methods()) checkStatements(method.body(), policy);
                } else if (declaration instanceof Ast.FieldDecl field) {
                    if (field.initializer() != null) checkExpr(field.initializer(), policy);
                } else if (declaration instanceof Ast.InterfaceDecl || declaration instanceof Ast.TypeAliasDecl) {
                    // Type-only declarations have no executable guest expressions.
                } else {
                    throw new IllegalStateException("capability checker does not handle declaration kind " + declaration.getClass().getSimpleName());
                }
            }
        }
    }

    private static void checkStatements(List<Ast.Stmt> statements, IsolatePolicy policy) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt s) checkExpr(s.initializer(), policy);
            else if (stmt instanceof Ast.DestructureStmt s) checkExpr(s.initializer(), policy);
            else if (stmt instanceof Ast.MatchStmt s) {
                checkExpr(s.value(), policy);
                for (Ast.MatchArm arm : s.arms()) checkStatements(arm.body(), policy);
            }
            else if (stmt instanceof Ast.ReturnStmt s) {
                if (s.value() != null) checkExpr(s.value(), policy);
            }
            else if (stmt instanceof Ast.ExprStmt s) checkExpr(s.expression(), policy);
            else if (stmt instanceof Ast.DeferStmt s) checkExpr(s.expression(), policy);
            else if (stmt instanceof Ast.IfStmt s) {
                for (Ast.IfBranch b : s.branches()) {
                    checkExpr(b.condition(), policy);
                    checkStatements(b.body(), policy);
                }
                checkStatements(s.elseBody(), policy);
            } else if (stmt instanceof Ast.TryStmt s) {
                checkStatements(s.body(), policy);
                checkStatements(s.catchBody(), policy);
                checkStatements(s.finallyBody(), policy);
            } else if (stmt instanceof Ast.ForOfStmt s) {
                checkExpr(s.iterable(), policy);
                checkStatements(s.body(), policy);
            } else if (stmt instanceof Ast.ForStmt s) {
                if (s.initializer() != null) checkStatements(List.of(s.initializer()), policy);
                if (s.condition() != null) checkExpr(s.condition(), policy);
                if (s.update() != null) checkExpr(s.update(), policy);
                checkStatements(s.body(), policy);
            } else {
                throw new IllegalStateException("capability checker does not handle statement kind " + stmt.getClass().getSimpleName());
            }
        }
    }

    private static void checkExpr(Ast.Expr expr, IsolatePolicy policy) {
        if (expr instanceof Ast.NameExpr n && n.name().equals("print")) require(policy, IsolatePolicy.Capability.STDOUT, "print");
        else if (expr instanceof Ast.LiteralExpr) {
            // Literals do not perform privileged work.
        }
        else if (expr instanceof Ast.NameExpr n) {
            // Materializing a privileged facade as a first-class value is more
            // powerful than a direct member access because aliases hide the
            // original root from later syntax-only path inspection. Require the
            // facade's full capability set up front.
            checkCapabilityPath(n.name(), policy);
        }
        else if (expr instanceof Ast.CallExpr c) {
            checkExpr(c.callee(), policy);
            for (Ast.Expr arg : c.arguments()) checkExpr(arg, policy);
        } else if (expr instanceof Ast.MemberExpr m) {
            String path = memberPath(m);
            if (path == null || !checkCapabilityPath(path, policy)) {
                checkExpr(m.receiver(), policy);
            }
        } else if (expr instanceof Ast.BinaryExpr e) { checkExpr(e.left(), policy); checkExpr(e.right(), policy); }
        else if (expr instanceof Ast.UnaryExpr e) checkExpr(e.operand(), policy);
        else if (expr instanceof Ast.AssignExpr e) {
            checkExpr(e.target(), policy);
            checkExpr(e.value(), policy);
        }
        else if (expr instanceof Ast.ConditionalExpr e) { checkExpr(e.condition(), policy); checkExpr(e.whenTrue(), policy); checkExpr(e.whenFalse(), policy); }
        else if (expr instanceof Ast.IndexExpr e) { checkExpr(e.receiver(), policy); checkExpr(e.index(), policy); }
        else if (expr instanceof Ast.NewExpr e) for (Ast.Expr a : e.arguments()) checkExpr(a, policy);
        else if (expr instanceof Ast.AwaitExpr e) checkExpr(e.expression(), policy);
        else if (expr instanceof Ast.ListExpr e) for (Ast.Expr a : e.elements()) checkExpr(a, policy);
        else if (expr instanceof Ast.TupleExpr e) for (Ast.Expr a : e.elements()) checkExpr(a, policy);
        else if (expr instanceof Ast.ObjectExpr e) for (Ast.ObjectField f : e.fields()) checkExpr(f.value(), policy);
        else if (expr instanceof Ast.LambdaExpr e) {
            if (e.expressionBody() != null) checkExpr(e.expressionBody(), policy);
            if (e.blockBody() != null) checkStatements(e.blockBody(), policy);
        } else {
            throw new IllegalStateException("capability checker does not handle expression kind " + expr.getClass().getSimpleName());
        }
    }

    private static boolean checkCapabilityPath(String path, IsolatePolicy policy) {
        if (path.equals("stdio") || path.startsWith("stdio.")) {
            require(policy, IsolatePolicy.Capability.STDOUT, path);
            return true;
        }
        if (path.equals("process") || path.startsWith("process.")) {
            if (path.startsWith("process.context_id") || path.startsWith("process.descriptor")) {
                require(policy, IsolatePolicy.Capability.PROCESS_INFO, path);
            } else if (path.startsWith("process.share_readonly")) {
                require(policy, IsolatePolicy.Capability.ACTOR_SHARE_READONLY, path);
            } else if (path.startsWith("process.spawn")) {
                require(policy, IsolatePolicy.Capability.CHILD_PROCESS, path);
            } else {
                require(policy, IsolatePolicy.Capability.PROCESS_INFO, path);
                require(policy, IsolatePolicy.Capability.ACTOR_SHARE_READONLY, path);
                require(policy, IsolatePolicy.Capability.CHILD_PROCESS, path);
            }
            return true;
        }
        if (path.equals("network") || path.startsWith("network.")) {
            require(policy, IsolatePolicy.Capability.NETWORK, path);
            return true;
        }
        if (path.equals("fs") || path.startsWith("fs.")) {
            if (path.startsWith("fs.read")) require(policy, IsolatePolicy.Capability.FILESYSTEM_READ, path);
            else if (path.startsWith("fs.write")) require(policy, IsolatePolicy.Capability.FILESYSTEM_WRITE, path);
            else {
                require(policy, IsolatePolicy.Capability.FILESYSTEM_READ, path);
                require(policy, IsolatePolicy.Capability.FILESYSTEM_WRITE, path);
            }
            return true;
        }
        if (path.equals("env") || path.startsWith("env.")) {
            require(policy, IsolatePolicy.Capability.ENVIRONMENT, path);
            return true;
        }
        if (path.equals("ffi") || path.startsWith("ffi.")) {
            require(policy, IsolatePolicy.Capability.FFI, path);
            return true;
        }
        if (path.equals("polyglot") || path.startsWith("polyglot.")) {
            require(policy, IsolatePolicy.Capability.POLYGLOT, path);
            return true;
        }
        if (path.equals("thread") || path.startsWith("thread.")) {
            require(policy, IsolatePolicy.Capability.THREAD_CREATE, path);
            return true;
        }
        return false;
    }

    private static String memberPath(Ast.Expr expr) {
        if (expr instanceof Ast.NameExpr n) return n.name();
        if (expr instanceof Ast.MemberExpr m) {
            String parent = memberPath(m.receiver());
            return parent == null ? null : parent + "." + m.member();
        }
        return null;
    }

    private static void require(IsolatePolicy policy, IsolatePolicy.Capability capability, String api) {
        if (!policy.allows(capability)) throw new SecurityException("Oreslang isolate denies capability " + capability + " required by " + api);
    }
}
