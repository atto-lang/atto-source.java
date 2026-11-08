package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** No restricted host effect may be hidden in an ordinary class constructor
 * and then invoked by a lower-authority actor through new Class().
 */
final class UntrustedConstructorCapabilityAdmissionTest {
    private static final Ast.TypeRef VOID = Ast.TypeRef.simple("void");

    private static Ast.Expr forbidden() {
        return new Ast.MemberExpr(new Ast.NameExpr("process"), "context_id");
    }

    private static Ast.ClassDecl klass(
            String name, Ast.ActorKind kind, Ast.ConstructorDecl ctor) {
        return new Ast.ClassDecl(name, Ast.Visibility.PRIVATE, false,
                kind, List.of(), List.of(), List.of(), List.of(), ctor, List.of());
    }

    private static Ast.ConstructorDecl ctor(
            List<Ast.Param> params, List<Ast.Annotation> annotations, List<Ast.Stmt> body) {
        return new Ast.ConstructorDecl(Ast.Visibility.PUBLIC, params, annotations, body);
    }

    private static Ast.FunctionDecl untrustedUser(String typeName) {
        return new Ast.FunctionDecl("worker", Ast.CallableKind.FNC,
                Ast.Visibility.PUBLIC, false, Ast.ActorKind.UNTRUSTED,
                List.of(), List.of(), VOID, List.of(),
                List.of(new Ast.ExprStmt(new Ast.NewExpr(
                        Ast.TypeRef.simple(typeName), List.of()))));
    }

    private static Ast.Program program(Ast.Decl... declarations) {
        return new Ast.Program(List.of(new Ast.ModuleDecl(
                "app", List.of(declarations))));
    }

    private static void deny(Ast.Program program, String expected) {
        SecurityException exception = assertThrows(SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));
        assertTrue(exception.getMessage().contains(expected), exception::getMessage);
    }

    @Test
    void untrustedNewMustInspectOrdinaryConstructorSideEffects() {
        Ast.ClassDecl privileged = klass("Bridge", Ast.ActorKind.NONE,
                ctor(List.of(), List.of(),
                        List.of(new Ast.ExprStmt(forbidden()))));
        deny(program(privileged, untrustedUser("Bridge")), "PROCESS_INFO");
    }

    @Test
    void directUntrustedActorConstructorIsCheckedAtDeclaration() {
        Ast.ClassDecl actor = klass("Worker", Ast.ActorKind.UNTRUSTED,
                ctor(List.of(), List.of(),
                        List.of(new Ast.ExprStmt(forbidden()))));
        deny(program(actor), "PROCESS_INFO");
    }

    @Test
    void constructorParameterAndAnnotationTypesCannotHideSharedAuthority() {
        Ast.Param parameter = new Ast.Param(
                Ast.TypeRef.simple("SharedMutex"), "guard");
        Ast.ClassDecl parameterClass = klass("WithParameter", Ast.ActorKind.NONE,
                ctor(List.of(parameter), List.of(), List.of()));
        deny(program(parameterClass, untrustedUser("WithParameter")), "SHARED_MEMORY");

        Ast.Annotation annotation = new Ast.Annotation(
                "gate", List.of(Ast.TypeRef.simple("SharedMutex")));
        Ast.ClassDecl annotated = klass("WithAnnotation", Ast.ActorKind.NONE,
                ctor(List.of(), List.of(annotation), List.of()));
        deny(program(annotated, untrustedUser("WithAnnotation")), "SHARED_MEMORY");
    }

    @Test
    void constructorHelperCallsAreInspectedTransitively() {
        Ast.FunctionDecl helper = new Ast.FunctionDecl(
                "sneaky_helper", Ast.CallableKind.FNC, Ast.Visibility.PRIVATE, false,
                Ast.ActorKind.NONE, List.of(), List.of(), VOID, List.of(),
                List.of(new Ast.ExprStmt(forbidden())));
        Ast.ClassDecl privileged = klass("Bridge", Ast.ActorKind.NONE,
                ctor(List.of(), List.of(),
                        List.of(new Ast.ExprStmt(
                                new Ast.CallExpr(new Ast.NameExpr("sneaky_helper"),
                                        List.of())))));
        deny(program(helper, privileged, untrustedUser("Bridge")), "PROCESS_INFO");
    }

    @Test
    void pureConstructorRemainsAdmissibleForUntrustedActor() {
        Ast.ClassDecl pure = klass("Safe", Ast.ActorKind.NONE,
                ctor(List.of(), List.of(),
                        List.of(new Ast.ExprStmt(new Ast.LiteralExpr(7)))));
        assertDoesNotThrow(() -> CapabilityChecker.check(
                program(pure, untrustedUser("Safe")), IsolatePolicy.developer()));
    }
}
