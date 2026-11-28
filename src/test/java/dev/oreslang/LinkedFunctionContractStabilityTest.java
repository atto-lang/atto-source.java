package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.LinkedFunctionContract;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class LinkedFunctionContractStabilityTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");

    private static Ast.FunctionDecl function(
            Ast.TypeRef result, List<Ast.Param> parameters,
            List<Ast.Annotation> annotations, List<Ast.Stmt> body) {
        return new Ast.FunctionDecl("work", Ast.CallableKind.FNC,
                Ast.Visibility.PUBLIC, false, false, false, false,
                false, false, Ast.ActorKind.NONE, List.of(),
                parameters, result, annotations, body);
    }

    @Test void bodyOnlyChangeDoesNotChangeInterfaceContract() {
        var original = function(INT, List.of(), List.of(),
                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(1L))));
        var updated = function(INT, List.of(), List.of(),
                List.of(new Ast.ReturnStmt(new Ast.LiteralExpr(2L))));
        assertDoesNotThrow(() ->
                LinkedFunctionContract.requireStableSignature(original, updated));
    }

    @Test void changingReturnOrNamedParameterIsNotCompatible() {
        var original = function(INT,
                List.of(new Ast.Param(INT, "value")), List.of(), List.of());
        var retChange = function(Ast.TypeRef.simple("String"),
                List.of(new Ast.Param(INT, "value")), List.of(), List.of());
        var nameChange = function(INT,
                List.of(new Ast.Param(INT, "different")), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () ->
                LinkedFunctionContract.requireStableSignature(original, retChange));
        assertThrows(IllegalArgumentException.class, () ->
                LinkedFunctionContract.requireStableSignature(original, nameChange));
    }

    @Test void newlyAddedAnnotationsOrEffectsFailClosed() {
        var original = function(INT, List.of(), List.of(), List.of());
        var annotations = function(INT, List.of(),
                List.of(new Ast.Annotation("dangerous",List.of())), List.of());
        var effectful = new Ast.FunctionDecl("work",Ast.CallableKind.FNC,
                Ast.Visibility.PUBLIC,true,false,false,false,false,false,
                Ast.ActorKind.NONE,List.of(),List.of(),INT,List.of(),List.of());
        assertThrows(IllegalArgumentException.class, () ->
                LinkedFunctionContract.requireStableSignature(original, annotations));
        assertThrows(IllegalArgumentException.class, () ->
                LinkedFunctionContract.requireStableSignature(original, effectful));
    }
}
