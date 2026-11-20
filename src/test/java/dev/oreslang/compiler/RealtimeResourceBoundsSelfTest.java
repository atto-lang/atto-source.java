package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Executable limits for callback inputs and modeled region allocation sizes. */
public final class RealtimeResourceBoundsSelfTest {
    private static int rejected;
    private static Ast.TypeRef i32() { return Ast.TypeRef.simple("i32"); }

    private static Ast.Program sourceWithArgs(int count) {
        List<Ast.Param> args = new ArrayList<>();
        for (int i = 0; i < count; i++) args.add(new Ast.Param(i32(), "p" + i));
        var callback = new Ast.MethodDecl("tick", Ast.Visibility.PUBLIC,
                false, false, false, false, false, null, List.of(), args,
                i32(), List.of(), List.of(new Ast.ReturnStmt(new Ast.NameExpr("p0"))));
        var actor = new Ast.ClassDecl("Ticker", Ast.Visibility.PUBLIC, false,
                Ast.ActorKind.PRIVATE, List.of(), List.of(), List.of(), List.of(),
                null, List.of(callback));
        return new Ast.Program(List.of(new Ast.ModuleDecl("test", List.of(actor))));
    }

    private static void denied(String label, Runnable check, String needle) {
        try {
            check.run();
            throw new AssertionError("unsafe configuration admitted: " + label);
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().contains(needle)) {
                throw new AssertionError("unexpected rejection: " + label, e);
            }
            rejected++;
        }
    }

    public static void main(String[] args) {
        var budget = new RealtimeActorSourceEffects.Budget(20);
        var accepted = RealtimeActorSourceEffects.analyzeChecked(
                sourceWithArgs(32), "Ticker", "tick", 32, budget);
        if (accepted.worstPathAstSteps() != 2) {
            throw new AssertionError("unexpected accepted resource estimate: " + accepted);
        }
        denied("33 arguments", () -> RealtimeActorSourceEffects.analyzeChecked(
                sourceWithArgs(33), "Ticker", "tick", 33, budget), "RT-ACTOR-SOURCE:");
        denied("oversized actor name", () -> RealtimeActorSourceEffects.analyzeChecked(
                sourceWithArgs(1), "x".repeat(129), "tick", 1, budget), "RT-ACTOR-SOURCE:");
        denied("oversized callback name", () -> RealtimeActorSourceEffects.analyzeChecked(
                sourceWithArgs(1), "Ticker", "x".repeat(129), 1, budget), "RT-ACTOR-SOURCE:");

        var arena = new RealtimeRegionEffectModel.Region("arena",
                RealtimeRegionEffectModel.RegionKind.ACTOR_ARENA,
                "ticker", null, 1, 1, 16);
        var allocationBudget = new RealtimeRegionEffectModel.Budget(16, 0, 4, 1);
        var invalid = new RealtimeRegionEffectModel.Plan("ticker", "run",
                List.of(arena), Set.of(), List.of(new RealtimeRegionEffectModel.Function(
                        "run", List.of(new RealtimeRegionEffectModel.Allocate(
                                "object", "arena", 0, false)))), allocationBudget);
        denied("zero byte object", () -> RealtimeRegionEffectModel.check(invalid),
                "allocation size must be positive");
        var valid = new RealtimeRegionEffectModel.Plan("ticker", "run",
                List.of(arena), Set.of(), List.of(new RealtimeRegionEffectModel.Function(
                        "run", List.of(new RealtimeRegionEffectModel.Allocate(
                                "object", "arena", 4, false),
                                new RealtimeRegionEffectModel.Drop("object")))), allocationBudget);
        if (RealtimeRegionEffectModel.check(valid).arenaBytesAllocated() != 4) {
            throw new AssertionError("modeled arena budget changed");
        }
        if (rejected != 4) throw new AssertionError("expected four denials, observed " + rejected);
        System.out.println("PASS: actor input/resource hardening, four negative limits plus controls");
    }
}
