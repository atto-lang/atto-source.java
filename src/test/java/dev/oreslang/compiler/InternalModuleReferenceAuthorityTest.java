package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class InternalModuleReferenceAuthorityTest {
    private static Ast.FunctionDecl fn(
            String name,
            Ast.Visibility visibility,
            List<Ast.Stmt> body) {
        return new Ast.FunctionDecl(
                name,
                visibility,
                false,
                List.of(),
                List.of(),
                Ast.TypeRef.simple("void"),
                List.of(),
                body);
    }

    private static Ast.Program forgedProgram(Ast.NameExpr forgedReceiver) {
        Ast.FunctionDecl helper = fn(
                "helper",
                Ast.Visibility.PUBLIC,
                List.of(new Ast.ReturnStmt(null)));
        Ast.FunctionDecl demo = fn(
                "demo",
                Ast.Visibility.PRIVATE,
                List.of(
                        new Ast.ExprStmt(
                                new Ast.MemberExpr(forgedReceiver, "helper")),
                        new Ast.ReturnStmt(null)));
        return new Ast.Program(
                List.of(new Ast.ModuleDecl(
                        Parser.ROOT_MODULE,
                        List.of(helper, demo))));
    }

    @Test
    void encodedInternalModuleNameIsNotAuthorityByItself() {
        Ast.NameExpr forged = new Ast.NameExpr(
                Ast.internalModuleReference(Parser.ROOT_MODULE));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> MacroIdentifierHygiene
                        .authorizedInternalModuleReferenceTarget(forged));

        assertTrue(failure.getMessage().contains(
                "unauthorized compiler-internal module reference"),
                failure::toString);
    }

    @Test
    void typeCheckerRejectsForgedInternalModuleReference() {
        Ast.NameExpr forged = new Ast.NameExpr(
                Ast.internalModuleReference(Parser.ROOT_MODULE));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(forgedProgram(forged)));

        assertTrue(failure.getMessage().contains(
                "unauthorized compiler-internal module reference"),
                failure::toString);
        assertTrue(failure.getMessage().contains("checked capture"),
                failure::toString);
    }

    @Test
    void capabilityAdmissionRejectsForgedInternalModuleReference() {
        Ast.NameExpr forged = new Ast.NameExpr(
                Ast.internalModuleReference(Parser.ROOT_MODULE));

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> CapabilityChecker.check(
                        forgedProgram(forged),
                        IsolatePolicy.developer()));

        assertTrue(failure.getMessage().contains(
                "unauthorized compiler-internal module reference"),
                failure::toString);
    }

    @Test
    void checkedCaptureMintsExactAuthorizedReceiverIdentity() {
        Ast.Program expanded = MacroExpander.expand(Parser.parse("""
                pub fnc helper() -> int {
                    return 42;
                }

                define macro reference(Identifier target) -> Syntax<Expr> {
                    return capture_fnc(target);
                }

                fnc demo() -> void {
                    reference!(ident helper);
                    return;
                }
                """));

        Ast.FunctionDecl demo = expanded.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("demo"))
                .findFirst()
                .orElseThrow();
        Ast.ExprStmt statement = assertInstanceOf(
                Ast.ExprStmt.class, demo.body().getFirst());
        Ast.MemberExpr captured = assertInstanceOf(
                Ast.MemberExpr.class, statement.expression());
        Ast.NameExpr receiver = assertInstanceOf(
                Ast.NameExpr.class, captured.receiver());

        assertEquals(
                Parser.ROOT_MODULE,
                MacroIdentifierHygiene
                        .authorizedInternalModuleReferenceTarget(receiver));
        assertDoesNotThrow(() -> TypeChecker.checkTypes(expanded));
        assertDoesNotThrow(() -> CapabilityChecker.check(
                expanded, IsolatePolicy.developer()));
    }    @Test
    void authorizedReceiverCannotBeReusedForDifferentModuleMember() {
        Ast.Program expanded = MacroExpander.expand(Parser.parse("""
                pub fnc helper() -> int {
                    return 42;
                }

                define macro reference(Identifier target) -> Syntax<Expr> {
                    return capture_fnc(target);
                }

                fnc demo() -> void {
                    reference!(ident helper);
                    return;
                }
                """));

        Ast.FunctionDecl originalDemo = expanded.modules().getFirst().declarations().stream()
                .filter(Ast.FunctionDecl.class::isInstance)
                .map(Ast.FunctionDecl.class::cast)
                .filter(fn -> fn.name().equals("demo"))
                .findFirst()
                .orElseThrow();
        Ast.MemberExpr captured = assertInstanceOf(
                Ast.MemberExpr.class,
                assertInstanceOf(
                        Ast.ExprStmt.class,
                        originalDemo.body().getFirst()).expression());
        Ast.NameExpr authorizedReceiver = assertInstanceOf(
                Ast.NameExpr.class, captured.receiver());

        Ast.FunctionDecl helper = fn(
                "helper",
                Ast.Visibility.PUBLIC,
                List.of(new Ast.ReturnStmt(null)));
        Ast.FunctionDecl other = fn(
                "other",
                Ast.Visibility.PUBLIC,
                List.of(new Ast.ReturnStmt(null)));
        Ast.MemberExpr widened =
                new Ast.MemberExpr(authorizedReceiver, "other");
        Ast.FunctionDecl forgedDemo = fn(
                "forged_demo",
                Ast.Visibility.PRIVATE,
                List.of(
                        new Ast.ExprStmt(widened),
                        new Ast.ReturnStmt(null)));
        Ast.Program forged = new Ast.Program(
                List.of(new Ast.ModuleDecl(
                        Parser.ROOT_MODULE,
                        List.of(helper, other, forgedDemo))));

        IllegalArgumentException typeFailure = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(forged));
        assertTrue(typeFailure.getMessage().contains(
                "exact MemberExpr identity"), typeFailure::toString);

        IllegalArgumentException capabilityFailure = assertThrows(
                IllegalArgumentException.class,
                () -> CapabilityChecker.check(
                        forged, IsolatePolicy.developer()));
        assertTrue(capabilityFailure.getMessage().contains(
                "exact MemberExpr identity"), capabilityFailure::toString);
    }


}
