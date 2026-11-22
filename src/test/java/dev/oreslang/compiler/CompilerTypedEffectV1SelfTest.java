package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.List;
import java.util.Set;

/**
 * JDK-only deterministic typed-AST effect checker regressions.
 *
 * Synthetic programs below test inference ONLY; the separate real-source
 * fixture checks parser/type/ownership frontdoor. Neither is guest-AOT or WCET.
 */
public final class CompilerTypedEffectV1SelfTest {
    private CompilerTypedEffectV1SelfTest() { }

    private static Ast.FunctionDecl fn(String name, Ast.Stmt... body) {
        return new Ast.FunctionDecl(name, Ast.CallableKind.FNC, Ast.Visibility.PUBLIC,
                false, false, false, false, false, false, Ast.ActorKind.NONE,
                List.of(), List.of(), Ast.TypeRef.simple("int"), List.of(), List.of(body));
    }

    private static Ast.Program program(Ast.FunctionDecl... functions) {
        return new Ast.Program(List.of(new Ast.ModuleDecl("__root__", List.of(functions))));
    }

    private static Ast.ReturnStmt ret(Ast.Expr value) { return new Ast.ReturnStmt(value); }
    private static Ast.Expr num(long value) { return new Ast.LiteralExpr(value); }
    private static Ast.Expr call(String target) {
        return new Ast.CallExpr(new Ast.NameExpr(target), List.of());
    }

    private static CompilerTypedEffectV1.Report analyze(
            Ast.Program source, CompilerTypedEffectV1.Profile mode) {
        return CompilerTypedEffectV1.analyzeChecked(source, mode);
    }

    private static void rejected(String reason, Ast.Program source,
                                 CompilerTypedEffectV1.Profile mode, String diagnostic) {
        try {
            analyze(source, mode);
            throw new AssertionError("unsafe source admitted: " + reason);
        } catch (IllegalArgumentException error) {
            if (!error.getMessage().startsWith(diagnostic)) {
                throw new AssertionError(reason + " returned " + error.getMessage(), error);
            }
        }
    }

    public static void main(String[] ignored) {
        Ast.Program pure = program(fn("main", ret(call("helper"))),
                fn("helper", ret(num(42))));
        var result = analyze(pure, CompilerTypedEffectV1.Profile.PURE);
        if (result.runtimeCertified() || result.rows().size() != 2
                || !result.rows().get(1).inferredEffects().isEmpty()) {
            throw new AssertionError("incorrect closed scalar effect result");
        }
        analyze(pure, CompilerTypedEffectV1.Profile.DETERMINISTIC);
        analyze(pure, CompilerTypedEffectV1.Profile.NO_ALLOC);

        Ast.Program transitiveAlloc = program(
                fn("main", ret(call("helper"))),
                fn("helper", new Ast.ExprStmt(new Ast.NewExpr(
                    Ast.TypeRef.simple("Box"), List.of())), ret(num(42))));
        rejected("transitive allocation", transitiveAlloc,
                CompilerTypedEffectV1.Profile.NO_ALLOC, "E_HARD_RT_ALLOCATION:");
        rejected("constructor exceptional/cleanup effects are unverified", transitiveAlloc,
                CompilerTypedEffectV1.Profile.PURE, "E_EFFECT_UNKNOWN:");

        Ast.Program recursion = program(
                fn("main", ret(call("helper"))),
                fn("helper", new Ast.ExprStmt(new Ast.NewExpr(
                    Ast.TypeRef.simple("Box"), List.of())), ret(call("main"))));
        rejected("allocation inside a recursive cycle", recursion,
                CompilerTypedEffectV1.Profile.NO_ALLOC, "E_HARD_RT_ALLOCATION:");

        rejected("unresolved callee", program(fn("main", ret(call("missing")))),
                CompilerTypedEffectV1.Profile.PURE, "E_EFFECT_UNKNOWN:");
        rejected("ambient clock call has no authenticated target summary",
                program(fn("main", ret(new Ast.CallExpr(
                        new Ast.MemberExpr(new Ast.NameExpr("clock"), "now"), List.of())))),
                CompilerTypedEffectV1.Profile.DETERMINISTIC, "E_EFFECT_UNKNOWN:");
        rejected("unbounded loop cannot be modeled as pure by omission",
                program(fn("main", new Ast.LoopStmt(List.of(ret(num(1)))))),
                CompilerTypedEffectV1.Profile.PURE, "E_EFFECT_UNKNOWN:");

        // Genuine frontdoor: parser -> type checker -> ownership checker.
        var checked = OresCompiler.analyzeEffectProfileV1(
                "pub fnc main(): int { return 42; }",
                CompilerTypedEffectV1.Profile.NO_ALLOC);
        if (checked.runtimeCertified() || checked.rows().isEmpty()) {
            throw new AssertionError("checked frontend did not return a bounded report");
        }
        System.out.println("CompilerTypedEffectV1SelfTest: source/AST checks completed; guest NOT_EXECUTED");
    }
}
