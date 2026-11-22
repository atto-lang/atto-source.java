package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.ReadonlyShapeProjectionV1;
import java.util.List;
import java.util.Map;

/**
 * Executes the REAL AST-declaration-to-witness bridge using the repository's
 * exact Ast.java. Unlike the older manually-authored descriptor selftests,
 * field slots and visibility here originate from compiler AST nodes.
 * This is still not a Truffle guest or a proof of its call lowering.
 */
public final class ReadonlyShapeAstDeclarationHostMain {
    private static Ast.FieldDecl field(String name, Ast.Visibility visibility,
                                       Ast.BindingKind binding, String type) {
        return new Ast.FieldDecl(name, visibility, binding,
                Ast.TypeRef.simple(type), null);
    }
    private static Ast.ClassDecl klass(String name, Ast.FieldDecl... fields) {
        return new Ast.ClassDecl(name, false, List.of(), List.of(fields), List.of());
    }
    private static Ast.TypeRef row(String name, String type) {
        return Ast.TypeRef.readonlyRecordType(
                Map.of(name, Ast.TypeRef.simple(type)));
    }
    private static void require(boolean ok, String label) {
        if (!ok) throw new AssertionError("AST shape proof: " + label);
    }
    private static void reject(Runnable operation, String label) {
        try { operation.run(); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Expected compiler AST admission rejection: " + label);
    }
    public static void main(String[] args) {
        var a=klass("A", field("x",Ast.Visibility.PUBLIC,Ast.BindingKind.VAL,"int"));
        var b=klass("B", field("padding",Ast.Visibility.PUBLIC,Ast.BindingKind.VAL,"int"),
                field("x",Ast.Visibility.PUBLIC,Ast.BindingKind.VAL,"int"));
        var expected=row("x","int");
        var planA=ReadonlyShapeProjectionV1.proveCheckedClass(a,expected,10,12,1);
        var planB=ReadonlyShapeProjectionV1.proveCheckedClass(b,expected,11,12,2);
        require(planA.field("x").sourceSlot()==0,"actual A.x offset");
        require(planB.field("x").sourceSlot()==1,"actual B.x offset");
        require(planA.sourceNominalId()!=planB.sourceNominalId(),"source identity");
        require(List.of(42L).get(planA.field("x").sourceSlot())
                +List.of(10L,42L).get(planB.field("x").sourceSlot())==84L,
                "cross-layout source-declaration oracle 84");
        reject(()->ReadonlyShapeProjectionV1.proveCheckedClass(
                klass("Private",field("x",Ast.Visibility.PRIVATE,Ast.BindingKind.VAL,"int")),
                expected,12,12,3),"private");
        reject(()->ReadonlyShapeProjectionV1.proveCheckedClass(
                klass("Mutable",field("x",Ast.Visibility.PUBLIC,Ast.BindingKind.LET,"int")),
                expected,12,12,3),"mutable");
        reject(()->ReadonlyShapeProjectionV1.proveCheckedClass(
                klass("Wrong",field("x",Ast.Visibility.PUBLIC,Ast.BindingKind.VAL,"string")),
                expected,12,12,3),"wrong scalar type");
        reject(()->ReadonlyShapeProjectionV1.proveCheckedClass(
                b,row("x","string"),12,12,3),"unsupported string ABI");
        reject(()->ReadonlyShapeProjectionV1.proveCheckedClass(
                b,expected,12,0,3),"stale generation");
        reject(()->ReadonlyShapeProjectionV1.proveCheckedClass(
                b,expected,12,12,0),"expired loan");
        var inherited = new Ast.ClassDecl("Child",false,
                List.of(),List.of(Ast.TypeRef.simple("Parent")),List.of(),
                List.of(field("x",Ast.Visibility.PUBLIC,Ast.BindingKind.VAL,"int")),
                List.of());
        reject(()->ReadonlyShapeProjectionV1.proveCheckedClass(
                inherited,expected,12,12,3),"unflattened inheritance");
        System.out.println("JAVA AST-DECLARATION SHAPE PROOF PASS, expected 84; GUEST NOT_EXECUTED");
    }
}
