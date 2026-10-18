package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.BoundInvocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * These tests assert rejection by the non-executable invocation preview.
 * They do not claim interpreter, GraalVM, or LLVM runtime implementation.
 */
final class BoundInvocationSafetyTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");

    private static Ast.ClassDecl concreteBox() {
        return (Ast.ClassDecl) Parser.parse("""
                define module app as
                    define class Box as
                        constructor(int value) { return; }
                        pub fnc echo(int v): int { return v; }
                    end
                end
                """).modules().getFirst().declarations().getFirst();
    }

    private static Ast.ClassDecl modifiedClass(
            Ast.ClassDecl base, boolean abstractClass, Ast.ConstructorDecl constructor) {
        return new Ast.ClassDecl(base.name(), base.visibility(), abstractClass,
                base.actorKind(), base.genericParameters(), base.parents(),
                base.interfaces(), base.fields(), constructor, base.methods());
    }

    private static Ast.StagedNewExpr construction() {
        return new Ast.StagedNewExpr(Ast.TypeRef.simple("Box"), List.of(
                Ast.CallArgument.named("value", new Ast.LiteralExpr(3L))), false);
    }

    @Test
    void abstractOwnerCannotProduceDirectConstructorOrMethodPreview() {
        Ast.ClassDecl concrete = concreteBox();
        Ast.ClassDecl abstractBox = modifiedClass(concrete, true, concrete.constructor());

        IllegalArgumentException ctor = assertThrows(IllegalArgumentException.class,
                () -> BoundInvocation.bindClassConstructor(
                        abstractBox, construction(), List.of(INT)));
        assertTrue(ctor.getMessage().contains("abstract"), ctor::toString);

        Ast.StagedCallExpr method = new Ast.StagedCallExpr(
                new Ast.MemberExpr(new Ast.NameExpr("box"), "echo"),
                List.of(), false, List.of(
                        Ast.CallArgument.named("v", new Ast.LiteralExpr(3L))));
        IllegalArgumentException methodFailure = assertThrows(IllegalArgumentException.class,
                () -> BoundInvocation.bindClassMethod(abstractBox, method, List.of(INT)));
        assertTrue(methodFailure.getMessage().contains("abstract"), methodFailure::toString);

        // The rejection must not globally disable ordinary concrete-owner plans.
        assertDoesNotThrow(() -> BoundInvocation.bindClassConstructor(
                concrete, construction(), List.of(INT)));
    }

    @Test
    void syntheticConstructorSelfCannotBeMistakenForGuestAbiArgument() {
        Ast.ClassDecl concrete = concreteBox();
        Ast.ConstructorDecl original = concrete.constructor();

        for (boolean leading : List.of(true, false)) {
            Ast.Param self = new Ast.Param(Ast.TypeRef.simple("Box"), "self");
            List<Ast.Param> formals = leading
                    ? List.of(self, original.parameters().getFirst())
                    : List.of(original.parameters().getFirst(), self);
            Ast.ConstructorDecl forged = new Ast.ConstructorDecl(
                    original.visibility(), formals, original.annotations(), original.body());
            Ast.ClassDecl withSelf = modifiedClass(concrete, false, forged);

            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> BoundInvocation.bindClassConstructor(
                            withSelf, construction(), List.of(INT)));
            assertTrue(failure.getMessage().contains("receiver erasure"), failure::toString);
        }
    }
}
