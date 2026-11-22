package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.BorrowMirAuditV1;
import dev.oreslang.types.OwnershipChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BorrowMirAuditV1Test {
    private static Ast.Program function(String type, List<Ast.Stmt> body) {
        Ast.FunctionDecl function = new Ast.FunctionDecl(
                "echo", Ast.CallableKind.FNC, Ast.Visibility.PRIVATE,
                false, Ast.ActorKind.NONE,
                List.of(), List.of(new Ast.Param(Ast.TypeRef.simple(type), "x")),
                Ast.TypeRef.simple(type), List.of(), body);
        return new Ast.Program(List.of(new Ast.ModuleDecl("Sample", List.of(function))));
    }

    private static Ast.ReturnStmt returnParam() {
        return new Ast.ReturnStmt(new Ast.NameExpr("x"));
    }

    @Test
    void inPassCheckedCopyParameterHasBoundedModelOnlyEvents() {
        var report = OwnershipChecker.checkWithBorrowMirAuditV1(
                function("int", List.of(returnParam())));
        assertEquals(BorrowMirAuditV1.CONTRACT_VERSION, report.version());
        assertFalse(report.completeSourceInventory());
        assertFalse(report.verifiedEmittedCode());
        assertEquals(1, report.functions().size());
        var trace = report.functions().getFirst();
        assertTrue(trace.eligibleForModelComparison());
        assertEquals(BorrowMirAuditV1.MODEL_ONLY, trace.status());
        assertEquals(List.of("INIT", "READ", "RETURN"),
                trace.instructions().stream().map(BorrowMirAuditV1.Instruction::operation).toList());
        assertEquals(List.of(1L, 2L, 3L),
                trace.instructions().stream().map(BorrowMirAuditV1.Instruction::instructionId).toList());
        assertEquals(1, trace.instructions().getFirst().ownerId());
        assertEquals(1, trace.instructions().get(1).ownerId());
        assertTrue(trace.instructions().getFirst().isCopy());
        assertFalse(trace.instructions().getFirst().isSend());
    }

    @Test
    void deterministicIdsDoNotDependOnAstObjectIdentity() {
        var first = OwnershipChecker.checkWithBorrowMirAuditV1(
                function("int", List.of(returnParam())));
        var second = OwnershipChecker.checkWithBorrowMirAuditV1(
                function("int", List.of(returnParam())));
        assertEquals(first, second);
    }

    @Test
    void additionalStatementRejectsNarrowShadowProfileWithoutWeakeningChecker() {
        var report = OwnershipChecker.checkWithBorrowMirAuditV1(
                function("int", List.of(
                        new Ast.ExprStmt(new Ast.NameExpr("x")),
                        returnParam())));
        var trace = report.functions().getFirst();
        assertEquals(BorrowMirAuditV1.UNSUPPORTED, trace.status());
        assertTrue(trace.instructions().isEmpty());
        // Existing compiler ownership validation remains authoritative.
        assertNotNull(OwnershipChecker.check(function("int", List.of(returnParam()))));
    }

    @Test
    void twoReturnStatementsAreNotClaimedAsLinearProof() {
        var report = OwnershipChecker.checkWithBorrowMirAuditV1(
                function("int", List.of(returnParam(), returnParam())));
        var trace = report.functions().getFirst();
        assertEquals(BorrowMirAuditV1.UNSUPPORTED, trace.status());
        assertTrue(trace.instructions().isEmpty());
    }

    @Test
    void nonCopyReturnIsNotSilentlyPresentedAsCopyProof() {
        var report = OwnershipChecker.checkWithBorrowMirAuditV1(
                function("Box", List.of(returnParam())));
        assertEquals(BorrowMirAuditV1.UNSUPPORTED, report.functions().getFirst().status());
        assertTrue(report.functions().getFirst().instructions().isEmpty());
    }

    @Test
    void normalOwnershipCheckIsNotModifiedByShadowObserver() {
        var program = function("int", List.of(returnParam()));
        assertSame(program, OwnershipChecker.check(program));
        var result = OwnershipChecker.checkWithBorrowMirAuditV1(
                function("int", List.of(returnParam())));
        assertEquals(BorrowMirAuditV1.MODEL_ONLY, result.functions().getFirst().status());
        assertFalse(result.completeSourceInventory());
    }
}
