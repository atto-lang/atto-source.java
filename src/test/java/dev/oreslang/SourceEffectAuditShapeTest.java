package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.EffectContracts;
import dev.oreslang.types.SourceEffectAudit;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the checked-AST walker independently of parser syntax.
 * Reflection is TEST-ONLY; production admission always parses and typechecks
 * source using SourceEffectAudit.inspect(String).
 */
final class SourceEffectAuditShapeTest {
    private static Set<EffectContracts.Effect> effects(Ast.Expr expr,
            boolean structural, boolean nonLexical, boolean generic) throws Exception {
        var fn = new Ast.FunctionDecl(
                "value", Ast.CallableKind.FNC, Ast.Visibility.PUBLIC,
                false, false, false, structural, nonLexical, false,
                Ast.ActorKind.NONE,
                generic ? List.of("T") : List.of(),
                List.of(), Ast.TypeRef.simple("int"), List.of(),
                List.of(new Ast.ReturnStmt(expr)),
                generic ? List.of(new Ast.GenericParameter("T", null)) : List.of());
        var program = new Ast.Program(List.of(new Ast.ModuleDecl("audit", List.of(fn))));
        var method = SourceEffectAudit.class.getDeclaredMethod("inspectChecked", Ast.Program.class);
        method.setAccessible(true);
        try {
            var report = (SourceEffectAudit.Report) method.invoke(null, program);
            assertNull(report.sourceSha256(),
                    "unchecked synthetic AST cannot receive a source-bound fingerprint");
            assertFalse(report.conservativelyEffectFree(),
                    "synthetic AST inspection must not mint a compiler-admitted report");
            return report.functions().values().iterator().next();
        } catch (InvocationTargetException e) {
            throw new AssertionError("AST walker failed", e.getCause());
        }
    }

    @Test void primitiveLiteralsAreTheOnlyAutomaticScalarProof() throws Exception {
        assertEquals(Set.of(), effects(new Ast.LiteralExpr(42), false, false, false));
        assertEquals(Set.of(), effects(new Ast.LiteralExpr(true), false, false, false));
        assertEquals(Set.of(EffectContracts.Effect.ALLOC),
                effects(new Ast.LiteralExpr("text"), false, false, false));
        assertTrue(effects(new Ast.LiteralExpr(new Object()), false, false, false)
                .contains(EffectContracts.Effect.UNKNOWN_CALL));
    }

    @Test void aggregateConstructionIsNotAllocationFree() throws Exception {
        var list = new Ast.ListExpr(List.of(new Ast.LiteralExpr(1)));
        var tuple = new Ast.TupleExpr(List.of(new Ast.LiteralExpr(1)));
        var record = new Ast.ObjectExpr(
                List.of(Ast.ObjectField.named("x", new Ast.LiteralExpr(1))),
                Ast.ObjectKind.RECORD, List.of());
        for (var expr : List.<Ast.Expr>of(list, tuple, record)) {
            assertTrue(effects(expr, false, false, false)
                    .contains(EffectContracts.Effect.ALLOC));
        }
    }

    @Test void aggregateFieldCallsAreNotLostDuringRecordTraversal() throws Exception {
        var invoked = new Ast.CallExpr(new Ast.NameExpr("unknown"), List.of());
        var record = new Ast.ObjectExpr(
                List.of(Ast.ObjectField.named("x", invoked)),
                Ast.ObjectKind.RECORD, List.of());
        var row = effects(record, false, false, false);
        assertTrue(row.contains(EffectContracts.Effect.ALLOC));
        assertTrue(row.contains(EffectContracts.Effect.UNKNOWN_CALL));
    }

    @Test void syntacticTrapIsNotProofOfCheckedHandlerOrAllocationFreedom()
            throws Exception {
        var row = effects(new Ast.TrapExpr(new Ast.LiteralExpr(42)),
                false, false, false);
        assertTrue(row.contains(EffectContracts.Effect.UNKNOWN_CALL));
        assertTrue(row.contains(EffectContracts.Effect.ALLOC));
    }

    @Test void unprovenCallableModifiersDoNotMintPurity() throws Exception {
        var literal = new Ast.LiteralExpr(42);
        assertTrue(effects(literal, true, false, false)
                .contains(EffectContracts.Effect.UNKNOWN_CALL));
        assertTrue(effects(literal, false, true, false)
                .contains(EffectContracts.Effect.UNKNOWN_CALL));
        assertTrue(effects(literal, false, false, true)
                .contains(EffectContracts.Effect.UNKNOWN_CALL));
    }
}
