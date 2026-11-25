package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

final class AffineDropPlanV1Test {
    private static final Ast.TypeRef ARRAY =
            new Ast.TypeRef("Array", List.of(Ast.TypeRef.simple("int")), false);
    private static Ast.Expr take(String name) {
        return new Ast.RuntimeCallExpr("take", List.of(new Ast.NameExpr(name)));
    }
    private static Ast.Stmt transfer(String from, String to) {
        return new Ast.BindingStmt(Ast.BindingKind.CONST, ARRAY, to, take(from));
    }
    private static Ast.Stmt choose(List<Ast.Stmt> yes, List<Ast.Stmt> no) {
        return new Ast.IfStmt(List.of(new Ast.IfBranch(new Ast.NameExpr("flag"), yes)), no);
    }
    private static AffineDropPlanV1.Plan plan(boolean returnsOwner, Ast.Stmt... body) {
        var fn = new Ast.FunctionDecl("compute", Ast.CallableKind.FNC,
                Ast.Visibility.PRIVATE, false, List.of(),
                List.of(new Ast.Param(ARRAY, "a"), new Ast.Param(ARRAY, "b"),
                        new Ast.Param(Ast.TypeRef.simple("bool"), "flag")),
                returnsOwner ? ARRAY : Ast.TypeRef.simple("void"), List.of(), List.of(body));
        return AffineDropPlanV1.lowerChecked(
                new Ast.Program(List.of(new Ast.ModuleDecl("Test", List.of(fn)))));
    }

    // Independent test-only linear resource-token machine. Every instruction
    // must consume a live slot; no token may be destroyed twice or leak.
    private record Outcome(List<String> destroyed, String returned) { }
    private static Outcome execute(AffineDropPlanV1.Plan plan, boolean flag) {
        Map<Integer, String> live = new LinkedHashMap<>();
        for (var p : plan.owners()) assertNull(live.put(p.slot(), p.name()));
        var destroyed = new ArrayList<String>();
        var seen = new HashSet<String>();
        var instruction = plan.entry();
        int fuel = 4096;
        while (!(instruction instanceof AffineDropPlanV1.Finish)) {
            assertTrue(--fuel > 0);
            if (instruction instanceof AffineDropPlanV1.Transfer transfer) {
                String token = live.remove(transfer.from());
                assertNotNull(token, "transfer from dead slot");
                assertNull(live.put(transfer.to(), token), "overwrite of live slot");
                instruction = transfer.next();
            } else if (instruction instanceof AffineDropPlanV1.Drop drop) {
                String token = live.remove(drop.slot());
                assertNotNull(token, "drop of dead slot");
                assertTrue(seen.add(token), "double destruction");
                destroyed.add(token);
                instruction = drop.next();
            } else if (instruction instanceof AffineDropPlanV1.Branch branch) {
                assertEquals("flag", branch.booleanParameter());
                instruction = flag ? branch.whenTrue() : branch.whenFalse();
            } else {
                fail("unknown instruction");
            }
        }
        String returned = live.remove(AffineDropPlanV1.RETURN_SLOT);
        assertTrue(live.isEmpty(), "live owner leaked");
        assertEquals(plan.returnsOwner(), returned != null);
        assertEquals(plan.owners().size(), destroyed.size() + (returned == null ? 0 : 1));
        return new Outcome(List.copyOf(destroyed), returned);
    }

    @Test
    void reverseInitializationOrderOnReturn() {
        assertEquals(new Outcome(List.of("b", "a"), null),
                execute(plan(false, new Ast.ReturnStmt(null)), false));
    }

    @Test
    void transferResultBeforeDroppingRemainingOwner() {
        var p = plan(true, new Ast.ReturnStmt(take("a")));
        assertEquals(new Outcome(List.of("b"), "a"), execute(p, true));
    }

    @Test
    void branchLocalTransferDropsOnceBeforeJoining() {
        var p = plan(false, choose(List.of(transfer("a", "local")), List.of()),
                new Ast.ReturnStmt(null));
        assertEquals(new Outcome(List.of("a", "b"), null), execute(p, true));
        assertEquals(new Outcome(List.of("b", "a"), null), execute(p, false));
    }

    @Test
    void branchReturnsDifferentOwners() {
        var p = plan(true, choose(List.of(new Ast.ReturnStmt(take("a"))),
                List.of(new Ast.ReturnStmt(take("b")))));
        assertEquals(new Outcome(List.of("b"), "a"), execute(p, true));
        assertEquals(new Outcome(List.of("a"), "b"), execute(p, false));
    }

    @Test
    void nestedScopeReturnTransfersBeforeOuterCleanup() {
        var p = plan(true, new Ast.BlockStmt(List.of(transfer("a", "local"),
                new Ast.ReturnStmt(take("local")))));
        assertEquals(new Outcome(List.of("b"), "a"), execute(p, false));
    }

    @Test
    void rejectsTransferredOwnerUseAndUnsupportedOperations() {
        assertThrows(IllegalArgumentException.class, () -> plan(true,
                choose(List.of(transfer("a", "local")), List.of()),
                new Ast.ReturnStmt(take("a"))));
        assertThrows(IllegalArgumentException.class, () -> plan(false,
                new Ast.ExprStmt(new Ast.RuntimeCallExpr("borrow",
                        List.of(new Ast.NameExpr("a"))))));
        assertThrows(IllegalArgumentException.class, () -> plan(false,
                new Ast.LoopStmt(List.of())));
        assertThrows(IllegalArgumentException.class, () -> plan(true));
    }

    @Test
    void strictCompilerFacadeProducesCheckedDropPlan() {
        var p = OresCompiler.elaborateAffineDropsStrictAtto("""
                fnc compute(Array<int> a, Array<int> b, bool flag): void {
                    return;
                }
                """);
        assertEquals(new Outcome(List.of("b", "a"), null), execute(p, true));
    }
}
