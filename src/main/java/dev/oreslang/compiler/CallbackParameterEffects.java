package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiler-source-derived may-effects for callback parameters.
 * NOT a proof of inline-only, non-retention, completion or timing. Unknown
 * dynamic/FFI calls and callback forwarding remain possible escapes.
 */
public final class CallbackParameterEffects {
    private CallbackParameterEffects() { }

    public record Summary(boolean mayInvokeInline, boolean mayEscape,
                          boolean maySuspendOrDefer) {
        public String timingHint() {
            if (maySuspendOrDefer || mayEscape) return "unknown or deferred";
            if (mayInvokeInline) return "may execute inline";
            return "not observed; no timing guarantee";
        }
    }

    public record Callable(String module, String name, int arity, int parameterIndex) { }
    private static final int MAX_VISITS = 200_000;
    private static final int MAX_DEPTH = 256;

    /** Scope-aware function/method declaration summaries; NO imported ABI proof. */
    public static Map<Callable, Summary> infer(Ast.Program program) {
        Map<Callable, Summary> result = new LinkedHashMap<>();
        for (Ast.ModuleDecl module : program.modules()) {
            String mod = module.name() == null ? "__root__" : module.name();
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    add(result, mod, fn.name(), fn.parameters(), fn.body(),
                            fn.async() || fn.suspends() || fn.generator());
                } else if (decl instanceof Ast.ClassDecl cls) {
                    for (Ast.MethodDecl method : cls.methods()) {
                        add(result, mod, cls.name() + "." + method.name(),
                                method.parameters(), method.body(),
                                method.async() || method.suspends());
                    }
                }
            }
        }
        return Map.copyOf(result);
    }

    private static void add(Map<Callable, Summary> result, String mod,
                            String name, List<Ast.Param> params,
                            List<Ast.Stmt> body, boolean asyncOrSuspends) {
        for (int i = 0; i < params.size(); i++) {
            Visitor visitor = new Visitor(params.get(i).name());
            visitor.maySuspend = asyncOrSuspends;
            visitor.block(body, false);
            Callable key = new Callable(mod, name, params.size(), i);
            if (result.putIfAbsent(key, visitor.summary()) != null)
                throw new IllegalArgumentException("ambiguous callback summary " + key);
        }
    }

    private static final class Visitor {
        final String parameter;
        boolean inline, escape, maySuspend;
        int visits, depth;

        Visitor(String parameter) { this.parameter = parameter; }
        Summary summary() { return new Summary(inline, escape, maySuspend); }
        void enter() {
            if (++visits > MAX_VISITS || ++depth > MAX_DEPTH)
                throw new IllegalArgumentException("callback effect analysis budget exceeded");
        }
        void leave() { depth--; }

        void block(List<Ast.Stmt> statements, boolean deferred) {
            enter();
            try { for (Ast.Stmt stmt : statements) statement(stmt, deferred); }
            finally { leave(); }
        }
        void statement(Ast.Stmt stmt, boolean deferred) {
            if (stmt == null) return;
            enter();
            try {
                if (stmt instanceof Ast.BindingStmt b) expression(b.initializer(), true);
                else if (stmt instanceof Ast.DestructureStmt d) expression(d.initializer(), true);
                else if (stmt instanceof Ast.ReturnStmt r) expression(r.value(), true);
                else if (stmt instanceof Ast.YieldStmt y) { maySuspend = true; expression(y.value(), true); }
                else if (stmt instanceof Ast.ExprStmt e) expression(e.expression(), deferred);
                else if (stmt instanceof Ast.DeferStmt d) expression(d.expression(), true);
                else if (stmt instanceof Ast.BlockStmt b) block(b.body(), deferred);
                else if (stmt instanceof Ast.IfStmt branch) {
                    for (Ast.IfBranch arm : branch.branches()) {
                        expression(arm.condition(), deferred);
                        block(arm.body(), deferred);
                    }
                    block(branch.elseBody(), deferred);
                } else if (stmt instanceof Ast.ForStmt f) {
                    statement(f.initializer(), deferred);
                    expression(f.condition(), deferred);
                    expression(f.update(), deferred);
                    block(f.body(), deferred);
                } else if (stmt instanceof Ast.ForOfStmt f) {
                    expression(f.iterable(), deferred); block(f.body(), deferred);
                } else if (stmt instanceof Ast.ForOfDestructureStmt f) {
                    expression(f.iterable(), deferred); block(f.body(), deferred);
                } else if (stmt instanceof Ast.DoWhileStmt d) {
                    block(d.body(), deferred); expression(d.condition(), deferred);
                } else if (stmt instanceof Ast.LoopStmt l) block(l.body(), deferred);
                else if (stmt instanceof Ast.MatchStmt m) {
                    expression(m.subject(), deferred);
                    for (Ast.MatchArm arm : m.arms()) {
                        expression(arm.guard(), deferred); block(arm.body(), deferred);
                    }
                } else if (stmt instanceof Ast.SwitchStmt sw) {
                    expression(sw.subject(), deferred);
                    for (Ast.SwitchCase arm : sw.cases()) {
                        for (Ast.Expr c : arm.constants()) expression(c, deferred);
                        block(arm.body(), deferred);
                    }
                    block(sw.defaultBody(), deferred);
                } else if (stmt instanceof Ast.TryStmt t) {
                    block(t.body(), deferred); block(t.catchBody(), deferred);
                    block(t.finallyBody(), deferred);
                } else if (stmt instanceof Ast.SelectStmt select) {
                    maySuspend = true;
                    for (Ast.SelectArm arm : select.arms()) {
                        expression(arm.channel(), true); expression(arm.value(), true);
                        block(arm.body(), true);
                    }
                } else if (!(stmt instanceof Ast.BreakStmt || stmt instanceof Ast.ContinueStmt)) {
                    // An unmodeled future AST variant cannot silently prove
                    // that a callback is never forwarded or retained.
                    escape = true;
                    maySuspend = true;
                }
            } finally { leave(); }
        }

        void expression(Ast.Expr expr, boolean deferred) {
            if (expr == null) return;
            enter();
            try {
                if (expr instanceof Ast.NameExpr n) {
                    if (parameter.equals(n.name())) escape = true;
                } else if (expr instanceof Ast.CallExpr call) {
                    if (call.callee() instanceof Ast.NameExpr n
                            && parameter.equals(n.name())) {
                        if (deferred) escape = true;
                        else inline = true;
                    } else expression(call.callee(), deferred);
                    for (Ast.Expr arg : call.arguments()) expression(arg, true);
                } else if (expr instanceof Ast.LambdaExpr lambda) {
                    expression(lambda.expressionBody(), true);
                    if (lambda.blockBody() != null) block(lambda.blockBody(), true);
                } else if (expr instanceof Ast.AwaitExpr a) {
                    maySuspend = true;
                    expression(a.expression(), true);
                } else if (expr instanceof Ast.TrapExpr t) expression(t.expression(), deferred);
                else if (expr instanceof Ast.RuntimeCallExpr r) {
                    if (r.operation().equals("cooperate")) maySuspend = true;
                    for (Ast.Expr arg : r.arguments()) expression(arg, true);
                } else if (expr instanceof Ast.BinaryExpr b) {
                    expression(b.left(), deferred); expression(b.right(), deferred);
                } else if (expr instanceof Ast.UnaryExpr u) expression(u.operand(), deferred);
                else if (expr instanceof Ast.AssignExpr a) {
                    expression(a.target(), true); expression(a.value(), true);
                } else if (expr instanceof Ast.ConditionalExpr c) {
                    expression(c.condition(), deferred);
                    expression(c.whenTrue(), deferred); expression(c.whenFalse(), deferred);
                } else if (expr instanceof Ast.MemberExpr m) expression(m.receiver(), true);
                else if (expr instanceof Ast.IndexExpr i) {
                    expression(i.receiver(), true); expression(i.index(), true);
                } else if (expr instanceof Ast.NewExpr n) {
                    for (Ast.Expr arg : n.arguments()) expression(arg, true);
                } else if (expr instanceof Ast.ChannelOpExpr c) {
                    maySuspend = true; expression(c.channel(), deferred);
                    expression(c.value(), true); block(c.callbackBody(), true);
                } else if (expr instanceof Ast.DynamicSelectExpr d) {
                    maySuspend = true; expression(d.cases(), true);
                } else if (expr instanceof Ast.TypeTestExpr t) expression(t.value(), deferred);
                else if (expr instanceof Ast.PatternTestExpr p) expression(p.value(), deferred);
                else if (expr instanceof Ast.CastExpr c) expression(c.value(), deferred);
                else if (expr instanceof Ast.SpreadExpr s) expression(s.expression(), true);
                else if (expr instanceof Ast.MatchExpr m) {
                    expression(m.subject(), deferred);
                    for (Ast.MatchValueArm arm : m.arms()) {
                        expression(arm.guard(), deferred); expression(arm.value(), deferred);
                    }
                } else if (expr instanceof Ast.ListExpr l) {
                    for (Ast.Expr v : l.elements()) expression(v, true);
                } else if (expr instanceof Ast.TupleExpr t) {
                    for (Ast.Expr v : t.elements()) expression(v, true);
                } else if (expr instanceof Ast.ObjectExpr o) {
                    for (Ast.ObjectField v : o.fields()) expression(v.value(), true);
                } else if (!(expr instanceof Ast.LiteralExpr)) {
                    escape = true; maySuspend = true;
                }
            } finally { leave(); }
        }
    }
}
