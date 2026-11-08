package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Direct AST construction covers expression forms that parser syntax can
 * normalize or reject earlier. Admission must still traverse all effects
 * when passed a compiler-owned, already constructed AST.
 */
final class UntrustedCapabilityExpressionCoverageTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");
    private static final Ast.TypeRef VOID = Ast.TypeRef.simple("void");
    private static final Ast.TypeRef SHARED = Ast.TypeRef.simple("SharedMutex");

    private static Ast.Expr forbidden() {
        return new Ast.MemberExpr(new Ast.NameExpr("process"), "context_id");
    }

    private static Ast.Program program(Ast.Expr expression) {
        Ast.FunctionDecl fn = new Ast.FunctionDecl(
                "worker", Ast.CallableKind.FNC, Ast.Visibility.PUBLIC, false,
                Ast.ActorKind.UNTRUSTED, List.of(), List.of(), VOID, List.of(),
                List.of(new Ast.ExprStmt(expression)));
        return new Ast.Program(List.of(new Ast.ModuleDecl("app", List.of(fn))));
    }

    private static void deny(Ast.Expr expression, String expected) {
        SecurityException error = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program(expression), IsolatePolicy.developer()));
        assertTrue(error.getMessage().contains(expected), error::getMessage);
    }

    @Test
    void valueMatchSubjectAndArmValueCannotLaunderAuthority() {
        deny(new Ast.MatchExpr(forbidden(), List.of(
                new Ast.MatchValueArm(new Ast.WildcardPattern(), null,
                        new Ast.LiteralExpr(1))), INT), "PROCESS_INFO");
        deny(new Ast.MatchExpr(new Ast.LiteralExpr(1), List.of(
                new Ast.MatchValueArm(new Ast.WildcardPattern(), null,
                        forbidden())), INT), "PROCESS_INFO");
    }

    @Test
    void valueMatchPrefixAndGuardAreBothChecked() {
        Ast.Stmt prefix = new Ast.ExprStmt(forbidden());
        deny(new Ast.MatchExpr(new Ast.LiteralExpr(1), List.of(
                new Ast.MatchValueArm(new Ast.WildcardPattern(), null,
                        List.of(prefix), new Ast.LiteralExpr(0))), INT), "PROCESS_INFO");
        deny(new Ast.MatchExpr(new Ast.LiteralExpr(1), List.of(
                new Ast.MatchValueArm(new Ast.WildcardPattern(), forbidden(),
                        new Ast.LiteralExpr(0))), INT), "PROCESS_INFO");
    }

    @Test
    void matchPatternsAndDeclaredResultTypesMustNotConcealSharedCapability() {
        deny(new Ast.MatchExpr(new Ast.LiteralExpr(1), List.of(
                new Ast.MatchValueArm(new Ast.TypePattern(SHARED, "resource"),
                        null, new Ast.LiteralExpr(0))), INT), "SHARED_MEMORY");
        deny(new Ast.MatchExpr(new Ast.LiteralExpr(1), List.of(
                new Ast.MatchValueArm(new Ast.WildcardPattern(), null,
                        new Ast.LiteralExpr(0))), SHARED), "SHARED_MEMORY");
    }

    @Test
    void castTypeTestsAndPatternTestsInspectTheirActualAuthority() {
        deny(new Ast.CastExpr(forbidden(), INT, Ast.CastMode.CHECKED), "PROCESS_INFO");
        deny(new Ast.TypeTestExpr(forbidden(), INT, "x"), "PROCESS_INFO");
        deny(new Ast.TypeTestExpr(new Ast.LiteralExpr(1), SHARED, "x"), "SHARED_MEMORY");
        deny(new Ast.PatternTestExpr(new Ast.LiteralExpr(1),
                new Ast.TypePattern(SHARED, "x")), "SHARED_MEMORY");
    }

    @Test
    void trapAndLocalAggregateArgumentsCannotHideEffects() {
        deny(new Ast.TrapExpr(forbidden()), "PROCESS_INFO");
        deny(new Ast.LocalAggregateConstructExpr("T", List.of(forbidden()), List.of()),
                "PROCESS_INFO");
        deny(new Ast.LocalAggregateConstructExpr("T", List.of(),
                List.of(new Ast.ObjectField("a", forbidden()))), "PROCESS_INFO");
    }

    @Test
    void nonblockingChannelCallbackBodyIsAdmittedTransitively() {
        deny(new Ast.ChannelOpExpr(
                Ast.ChannelOperation.WRITE, Ast.WaitMode.NONBLOCKING,
                new Ast.NameExpr("pipe"), new Ast.LiteralExpr(7), true,
                List.of(new Ast.ExprStmt(forbidden()))), "PROCESS_INFO");
    }

    @Test
    void callTypeArgumentsAndExplicitStructFieldsCannotHideSharedCapability() {
        deny(new Ast.CallExpr(new Ast.NameExpr("safe"), List.of(SHARED), true,
                List.of()), "SHARED_MEMORY");
        deny(new Ast.ObjectExpr(List.of(), Ast.ObjectKind.EXPLICIT_STRUCT,
                List.of(new Ast.StructFieldSpec("secret", SHARED))), "SHARED_MEMORY");
    }

    @Test
    void lambdaParameterAndReturnTypesMustBeChecked() {
        deny(new Ast.LambdaExpr(List.of(new Ast.Param(SHARED, "p")),
                new Ast.LiteralExpr(0), null, false, null), "SHARED_MEMORY");
        deny(new Ast.LambdaExpr(List.of(), null,
                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(0))),
                false, SHARED), "SHARED_MEMORY");
    }

    @Test
    void nominalConstructorPatternCannotLaunderSharedAuthority() {
        deny(new Ast.PatternTestExpr(new Ast.LiteralExpr(1),
                new Ast.ConstructorPattern("SharedMutex", List.of())),
                "SHARED_MEMORY");
    }

    @Test
    void pureValueMatchAndCastStillPassZeroAuthorityAdmission() {
        Ast.Expr pure = new Ast.MatchExpr(new Ast.LiteralExpr(1), List.of(
                new Ast.MatchValueArm(new Ast.WildcardPattern(), null,
                        new Ast.CastExpr(new Ast.LiteralExpr(7), INT,
                                Ast.CastMode.CHECKED))), INT);
        assertDoesNotThrow(() ->
                CapabilityChecker.check(program(pure), IsolatePolicy.developer()));
    }
}
