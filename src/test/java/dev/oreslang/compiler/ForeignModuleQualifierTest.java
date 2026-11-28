package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class ForeignModuleQualifierTest {
    private static Ast.TypeRef type(String name, Ast.TypeRef... arguments) {
        return new Ast.TypeRef(name, List.of(arguments), false);
    }

    @Test void qualifiesNestedSignaturesAndPreservesGenericShadowingAndBodies() {
        var nominal = new Ast.InterfaceDecl("Item", List.of(), List.of());
        var other = new Ast.InterfaceDecl("Other", List.of(), List.of());
        var initializer = new Ast.NameExpr("Item");
        var annotation = new Ast.Annotation("test", List.of(type("Other")));
        var field = new Ast.FieldDecl("f", Ast.Visibility.PUBLIC, Ast.BindingKind.LET,
                type("Array", type("Item")), List.of(annotation), initializer, false);
        var body = new Ast.ReturnStmt(new Ast.NameExpr("Other"));
        var fn = new Ast.FunctionDecl("inspect", Ast.CallableKind.FNC, Ast.Visibility.PUBLIC,
                false, false, false, false, false, false, Ast.ActorKind.NONE,
                List.of("Item"), List.of(new Ast.Param(type("Item"), "arg", false, false)),
                type("Array", type("Other")), List.of(annotation), List.of(body),
                List.of(new Ast.GenericParameter("Item", type("Other"))),
                new Ast.EffectSpec(List.of(type("Other")), List.of(), List.of()));
        var iface = new Ast.InterfaceDecl("Reader", Ast.Visibility.PUBLIC, List.of(),
                List.of(type("Other")), List.of(new Ast.InterfaceFieldDecl("value", type("Item"),
                Ast.BindingKind.LET, false)));
        var module = new Ast.ModuleDecl("sample", List.of(), List.of(),
                List.of(nominal, other, field, fn, iface));
        var result = ForeignModuleQualifier.qualify("lib", module);
        assertSame(nominal, result.declarations().get(0));
        var f = (Ast.FieldDecl) result.declarations().get(2);
        assertEquals(type("Array", type("lib.Item")), f.type());
        assertSame(initializer, f.initializer());
        assertSame(annotation, f.annotations().getFirst());
        var method = (Ast.FunctionDecl) result.declarations().get(3);
        assertEquals(type("Item"), method.parameters().getFirst().type());
        assertEquals(type("Array", type("lib.Other")), method.returnType());
        assertEquals(type("lib.Other"), method.genericSpecs().getFirst().defaultType());
        assertEquals(type("lib.Other"), method.effects().throwsTypes().getFirst());
        assertSame(body, method.body().getFirst());
        var reader = (Ast.InterfaceDecl) result.declarations().get(4);
        assertEquals(type("lib.Other"), reader.parents().getFirst());
        assertEquals(type("lib.Item"), ((Ast.InterfaceFieldDecl)reader.members().getFirst()).type());
        assertEquals(type("Array", type("Item")), field.type());
    }
}
