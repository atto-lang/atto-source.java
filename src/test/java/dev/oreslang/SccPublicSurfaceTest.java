package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.SccPublicSurface;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class SccPublicSurfaceTest {
    private static final Ast.TypeRef INT = Ast.TypeRef.simple("int");

    private static Ast.FunctionDecl fn(Ast.Visibility visibility, Ast.TypeRef result) {
        return new Ast.FunctionDecl("work",visibility,false,
                List.of(),List.of(),result,List.of(),List.of());
    }

    private static Ast.Program program(Ast.Decl... declarations) {
        return new Ast.Program(List.of(new Ast.ModuleDecl("api",List.of(declarations))));
    }

    @Test void primitivePublicFunctionsAndPrivateImplementationTypesRemainAllowed() {
        assertDoesNotThrow(() -> SccPublicSurface.requirePrimitiveOnly(
                program(fn(Ast.Visibility.PUBLIC,INT))));
        assertDoesNotThrow(() -> SccPublicSurface.requirePrimitiveOnly(
                program(new Ast.TypeAliasDecl(
                    "Hidden",Ast.Visibility.PRIVATE,List.of(),INT))));
        assertDoesNotThrow(() -> SccPublicSurface.requirePrimitiveOnly(
                program(fn(Ast.Visibility.PRIVATE,Ast.TypeRef.simple("PrivateNominal")))));
    }

    @Test void publicNominalAliasStateAndActorTypesFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                SccPublicSurface.requirePrimitiveOnly(program(
                    new Ast.TypeAliasDecl("PublicAlias",Ast.Visibility.PUBLIC,List.of(),INT))));
        assertThrows(IllegalArgumentException.class, () ->
                SccPublicSurface.requirePrimitiveOnly(program(
                    new Ast.FieldDecl("state",Ast.Visibility.PUBLIC,Ast.BindingKind.CONST,
                            INT,new Ast.LiteralExpr(3L)))));
        assertThrows(IllegalArgumentException.class, () ->
                SccPublicSurface.requirePrimitiveOnly(program(
                    new Ast.ClassDecl("Actor",Ast.Visibility.PUBLIC,false,
                        Ast.ActorKind.PRIVATE,List.of(),List.of(),List.of(),
                        List.of(),null,List.of()))));
        assertThrows(IllegalArgumentException.class, () ->
                SccPublicSurface.requirePrimitiveOnly(program(
                    new Ast.InterfaceDecl("PublicApi",Ast.Visibility.PUBLIC,
                        List.of(),List.of(),List.of()))));
    }

    @Test void unsupportedModuleContractsAnnotationsAndEffectsFailClosed() {
        var declared = fn(Ast.Visibility.PUBLIC,INT);
        assertThrows(IllegalArgumentException.class, () ->
                SccPublicSurface.requirePrimitiveOnly(new Ast.Program(List.of(
                    new Ast.ModuleDecl("api",List.of(),List.of(INT),List.of(declared))))));
        assertThrows(IllegalArgumentException.class, () ->
                SccPublicSurface.requirePrimitiveOnly(new Ast.Program(List.of(
                    new Ast.ModuleDecl("api",
                        List.of(new Ast.Annotation("annotation",List.of())),
                        List.of(),List.of(declared))))));
        var effectful = new Ast.FunctionDecl("work",Ast.CallableKind.FNC,
            Ast.Visibility.PUBLIC,true,false,false,false,false,false,
            Ast.ActorKind.NONE,List.of(),List.of(),INT,List.of(),List.of());
        assertThrows(IllegalArgumentException.class, () ->
                SccPublicSurface.requirePrimitiveOnly(program(effectful)));
        assertThrows(IllegalArgumentException.class, () ->
                SccPublicSurface.requirePrimitiveOnly(program(
                    fn(Ast.Visibility.PUBLIC,Ast.TypeRef.simple("Nominal")))));
    }
}
