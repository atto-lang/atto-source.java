package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class CapabilityFormalTraversalTest {
    private static Ast.Expr processInfo() {
        return new Ast.MemberExpr(new Ast.NameExpr("process"), "context_id");
    }

    private static Ast.Program program(List<Ast.ContractClause> contracts, List<Ast.Stmt> body) {
        Ast.FunctionDecl fn = new Ast.FunctionDecl(
                "probe",
                Ast.CallableKind.FNC,
                Ast.Visibility.PRIVATE,
                false,
                List.of(),
                List.of(),
                Ast.TypeRef.simple("void"),
                List.of(),
                contracts,
                body);
        return new Ast.Program(List.of(new Ast.ModuleDecl("app", List.of(fn))));
    }

    private static void assertDenied(Ast.Program program) {
        assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.strictFaas()));
    }

    @Test
    void contractsCannotHideCapabilities() {
        for (Ast.ContractKind kind : Ast.ContractKind.values()) {
            assertDenied(program(
                    List.of(new Ast.ContractClause(kind, processInfo())),
                    List.of()));
        }
    }

    @Test
    void proofStatementsCannotHideCapabilities() {
        assertDenied(program(List.of(), List.of(new Ast.AssertStmt(processInfo()))));
        assertDenied(program(List.of(), List.of(new Ast.AssumeStmt(processInfo()))));
        assertDenied(program(List.of(), List.of(new Ast.InvariantStmt(processInfo()))));
    }

    @Test
    void matchSubjectGuardAndArmCannotHideCapabilities() {
        Ast.Pattern any = new Ast.WildcardPattern();

        assertDenied(program(List.of(), List.of(new Ast.ExprStmt(
                new Ast.MatchExpr(
                        processInfo(),
                        List.of(new Ast.MatchArm(any, null, new Ast.LiteralExpr(1L))))))));

        assertDenied(program(List.of(), List.of(new Ast.ExprStmt(
                new Ast.MatchExpr(
                        new Ast.LiteralExpr(true),
                        List.of(new Ast.MatchArm(any, processInfo(), new Ast.LiteralExpr(1L))))))));

        assertDenied(program(List.of(), List.of(new Ast.ExprStmt(
                new Ast.MatchExpr(
                        new Ast.LiteralExpr(true),
                        List.of(new Ast.MatchArm(any, null, processInfo())))))));
    }

    @Test
    void oldExpressionCannotHideCapabilities() {
        assertDenied(program(
                List.of(),
                List.of(new Ast.ExprStmt(new Ast.OldExpr(processInfo())))));
    }
}
