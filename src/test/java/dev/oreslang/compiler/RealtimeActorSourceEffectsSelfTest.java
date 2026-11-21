package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.List;

/**
 * JDK-only executable fixtures against the real Oreslang AST signatures.
 * The public compiler entrypoint performs type/ownership checks before this gate;
 * these synthetic AST fixtures exercise the gate directly, not the full parser.
 */
public final class RealtimeActorSourceEffectsSelfTest {
    private RealtimeActorSourceEffectsSelfTest() { }
    private static int rejects;
    private static Ast.TypeRef i32() { return Ast.TypeRef.simple("i32"); }
    private static Ast.NameExpr name(String x) { return new Ast.NameExpr(x); }
    private static Ast.LiteralExpr num(int n) { return new Ast.LiteralExpr(n); }
    private static Ast.ReturnStmt ret(Ast.Expr expr) { return new Ast.ReturnStmt(expr); }
    private static Ast.MethodDecl method(List<Ast.Stmt> body) {
        return new Ast.MethodDecl("tick", Ast.Visibility.PUBLIC,
                false, false, false, false, false, null,
                List.of(), List.of(new Ast.Param(i32(), "x")), i32(), List.of(), body);
    }
    private static Ast.Program actor(Ast.MethodDecl callback) {
        var cls = new Ast.ClassDecl("Ticker", Ast.Visibility.PUBLIC,
                false, Ast.ActorKind.PRIVATE, List.of(), List.of(), List.of(),
                List.of(), null, List.of(callback));
        return new Ast.Program(List.of(new Ast.ModuleDecl("test", List.of(cls))));
    }
    private static RealtimeActorSourceEffects.Report scan(Ast.Program p, int maxSteps) {
        return RealtimeActorSourceEffects.analyzeChecked(
                p, "Ticker", "tick", 1, new RealtimeActorSourceEffects.Budget(maxSteps));
    }
    private static void reject(String label, Ast.Program program) {
        try {
            scan(program, 200);
            throw new AssertionError("unsafe source accepted: " + label);
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().startsWith("RT-ACTOR-SOURCE:")) {
                throw new AssertionError("unexpected failure: " + label, e);
            }
            rejects++;
        }
    }
    private static Ast.MethodDecl variant(boolean async, boolean suspends, boolean isStatic) {
        return new Ast.MethodDecl("tick", Ast.Visibility.PUBLIC,
                isStatic, false, async, suspends, false, null,
                List.of(), List.of(new Ast.Param(i32(), "x")), i32(), List.of(),
                List.of(ret(name("x"))));
    }
    public static void main(String[] ignored) {
        Ast.Program valid = actor(method(List.of(ret(
                new Ast.BinaryExpr("+", name("x"), num(1))))));
        var report = scan(valid, 100);
        if (report.worstPathAstSteps() != 4 || report.validatedMethods() != 1
                || !report.assurance().contains("ACTOR_REGION_AND_VM_UNVERIFIED")) {
            throw new AssertionError("incorrect scalar source report: " + report);
        }
        Ast.IfStmt branches = new Ast.IfStmt(
                List.of(new Ast.IfBranch(new Ast.BinaryExpr(">", name("x"), num(0)),
                        List.of(ret(num(1))))),
                List.of(ret(num(0))));
        if (scan(actor(method(List.of(branches))), 100).worstPathAstSteps() < 4) {
            throw new AssertionError("conditional worst-path accounting absent");
        }

        reject("heap allocation", actor(method(List.of(
                ret(new Ast.NewExpr(i32(), List.of()))))));
        reject("suspension", actor(method(List.of(
                ret(new Ast.AwaitExpr(name("x")))))));
        reject("runtime share", actor(method(List.of(
                ret(new Ast.RuntimeCallExpr("share", List.of(name("x"))))))));
        reject("runtime borrow", actor(method(List.of(
                ret(new Ast.RuntimeCallExpr("borrow", List.of(name("x"))))))));
        reject("unverified function call", actor(method(List.of(
                ret(new Ast.CallExpr(name("external"), List.of(name("x"))))))));
        reject("member reference", actor(method(List.of(
                ret(new Ast.MemberExpr(name("x"), "field"))))));
        reject("integer division exception path", actor(method(List.of(
                ret(new Ast.BinaryExpr("/", name("x"), num(2)))))));
        reject("loop", actor(method(List.of(
                new Ast.LoopStmt(List.of(ret(num(1))))))));
        reject("unbound scalar name", actor(method(List.of(ret(name("global"))))));
        reject("missing return", actor(method(List.of(
                new Ast.BindingStmt(Ast.BindingKind.CONST, i32(), "z", num(1))))));
        reject("unreachable after return", actor(method(List.of(ret(num(1)), ret(num(2))))));
        reject("incomplete conditional", actor(method(List.of(
                new Ast.IfStmt(List.of(new Ast.IfBranch(
                        new Ast.BinaryExpr(">", name("x"), num(0)),
                        List.of(ret(num(1))))), List.of())))));
        reject("mutable binding", actor(method(List.of(
                new Ast.BindingStmt(Ast.BindingKind.LET, i32(), "z", num(1)), ret(num(1))))));
        reject("callback declared async", actor(variant(true, false, false)));
        reject("callback declares suspends", actor(variant(false, true, false)));
        reject("static actor callback", actor(variant(false, false, true)));

        var sibling = new Ast.MethodDecl("helper", Ast.Visibility.PRIVATE,
                false, false, false, false, false, null,
                List.of(), List.of(), i32(), List.of(), List.of(ret(num(0))));
        var withSibling = new Ast.ClassDecl("Ticker", Ast.Visibility.PUBLIC,
                false, Ast.ActorKind.PRIVATE, List.of(), List.of(), List.of(),
                List.of(), null, List.of(method(List.of(ret(name("x")))), sibling));
        reject("unverified sibling actor method", new Ast.Program(
                List.of(new Ast.ModuleDecl("test", List.of(withSibling)))));

        var withField = new Ast.ClassDecl("Ticker", Ast.Visibility.PUBLIC,
                false, Ast.ActorKind.PRIVATE, List.of(), List.of(), List.of(),
                List.of(new Ast.FieldDecl("field", Ast.Visibility.PUBLIC,
                        Ast.BindingKind.LET, i32(), num(1))), null,
                List.of(method(List.of(ret(name("x"))))));
        reject("unverified actor memory field", new Ast.Program(
                List.of(new Ast.ModuleDecl("test", List.of(withField)))));
        var untrusted = new Ast.ClassDecl("Ticker", Ast.Visibility.PUBLIC,
                false, Ast.ActorKind.UNTRUSTED, List.of(), List.of(), List.of(), List.of(),
                null, List.of(method(List.of(ret(name("x"))))));
        reject("untrusted actor execution", new Ast.Program(
                List.of(new Ast.ModuleDecl("test", List.of(untrusted)))));
        try {
            scan(valid, 3);
            throw new AssertionError("AST budget underflow permitted");
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains("RT-ACTOR-SOURCE:")) throw e;
            rejects++;
        }
        if (rejects != 20) throw new AssertionError("expected 20 negatives, found " + rejects);
        System.out.println("PASS: checked-AST conservative actor callback gate, 20 negative cases");
    }
}
