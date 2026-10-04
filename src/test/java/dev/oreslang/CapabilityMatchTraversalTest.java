package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

final class CapabilityMatchTraversalTest {
    private static Ast.Expr processInfo() {
        return new Ast.MemberExpr(new Ast.NameExpr("process"), "context_id");
    }

    private static Ast.Program program(Ast.Expr expression) {
        Ast.FunctionDecl fn = new Ast.FunctionDecl(
                "probe",
                Ast.CallableKind.FNC,
                Ast.Visibility.PRIVATE,
                false,
                List.of(),
                List.of(),
                Ast.TypeRef.simple("void"),
                List.of(),
                List.of(new Ast.ExprStmt(expression)));
        return new Ast.Program(List.of(new Ast.ModuleDecl("app", List.of(fn))));
    }

    private static void assertDenied(Ast.Expr expression) {
        assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program(expression), IsolatePolicy.strictFaas()));
    }

    @Test
    void matchScrutineeCannotHideCapability() {
        assertDenied(new Ast.MatchExpr(
                processInfo(),
                List.of(new Ast.MatchArm(
                        new Ast.WildcardPattern(),
                        null,
                        new Ast.LiteralExpr(1L))),
                1));
    }

    @Test
    void matchGuardCannotHideCapability() {
        assertDenied(new Ast.MatchExpr(
                new Ast.LiteralExpr(true),
                List.of(new Ast.MatchArm(
                        new Ast.WildcardPattern(),
                        processInfo(),
                        new Ast.LiteralExpr(1L))),
                1));
    }

    @Test
    void matchResultCannotHideCapability() {
        assertDenied(new Ast.MatchExpr(
                new Ast.LiteralExpr(true),
                List.of(new Ast.MatchArm(
                        new Ast.WildcardPattern(),
                        null,
                        processInfo())),
                1));
    }
}
