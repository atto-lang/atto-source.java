package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Syntax-stage contract only. An AST node must never be mistaken for verified
 * nominal type identity or executable backend lowering.
 */
final class LocalAggregateTypeSyntaxStage1Test {

    private static Ast.FunctionDecl parseCallable(String body, boolean routine) {
        String source = "pub " + (routine ? "routine" : "fnc")
                + " example(): T { " + body + " }";
        Ast.Program parsed = Parser.parse(source);
        return assertInstanceOf(Ast.FunctionDecl.class,
                parsed.modules().getFirst().declarations().getFirst());
    }

    @Test
    void namedLocalStructPreservesItsShapeAndPositionalConstruction() {
        Ast.FunctionDecl function = parseCallable(
                "struct T { int foo } return T{5};", false);
        Ast.LocalTypeDeclStmt type = assertInstanceOf(Ast.LocalTypeDeclStmt.class,
                function.body().getFirst());
        assertTrue(type.nominal());
        assertTrue(type.target().isStructType());
        assertEquals("int", type.target().structMembers().get("foo").name());

        Ast.ReturnStmt returned = assertInstanceOf(Ast.ReturnStmt.class,
                function.body().get(1));
        Ast.LocalAggregateConstructExpr construction =
                assertInstanceOf(Ast.LocalAggregateConstructExpr.class, returned.value());
        assertEquals("T", construction.typeName());
        assertEquals(1, construction.positionalArguments().size());
        assertEquals(0, construction.namedArguments().size());
    }

    @Test
    void transparentStructAliasWorksInRoutineWithNamedConstruction() {
        Ast.FunctionDecl routine = parseCallable(
                "type T = struct { int foo } return T{foo: 5};", true);
        Ast.LocalTypeDeclStmt type = assertInstanceOf(Ast.LocalTypeDeclStmt.class,
                routine.body().getFirst());
        assertFalse(type.nominal());
        assertTrue(type.target().isStructType());
        Ast.ReturnStmt returned = (Ast.ReturnStmt) routine.body().get(1);
        Ast.LocalAggregateConstructExpr construction =
                (Ast.LocalAggregateConstructExpr) returned.value();
        assertEquals("foo", construction.namedArguments().getFirst().name());
    }

    @Test
    void localRecordAndRecordAliasRemainStructuralRecords() {
        for (String declaration : new String[]{
                "record T { int foo }",
                "type T = record { int foo }"}) {
            Ast.FunctionDecl function = parseCallable(
                    declaration + " return T{5};", false);
            Ast.LocalTypeDeclStmt type = (Ast.LocalTypeDeclStmt)
                    function.body().getFirst();
            assertFalse(type.nominal());
            assertTrue(type.target().isRecordType());
            assertFalse(type.target().isStructType());
        }
    }

    @Test
    void colonFieldDeclarationAlsoPreservesAggregateCategory() {
        Ast.FunctionDecl function = parseCallable(
                "type T = struct { foo: int, bar: string } return T{5, \"hello\"};", false);
        Ast.LocalTypeDeclStmt type = (Ast.LocalTypeDeclStmt)
                function.body().getFirst();
        assertTrue(type.target().isStructType());
        assertEquals(2, type.target().structMembers().size());
        Ast.LocalAggregateConstructExpr construct =
                (Ast.LocalAggregateConstructExpr) ((Ast.ReturnStmt) function.body().get(1)).value();
        assertEquals(2, construct.positionalArguments().size());
    }

    @Test
    void localClassAndRedundantConstTypeAreRejected() {
        IllegalArgumentException cls = assertThrows(IllegalArgumentException.class,
                () -> parseCallable("class T as end return T{5};", false));
        assertTrue(cls.getMessage().contains("module-scope"), cls::toString);

        IllegalArgumentException prefixed = assertThrows(IllegalArgumentException.class,
                () -> parseCallable("define class T as end return T{5};", false));
        assertTrue(prefixed.getMessage().contains("compile-time declarations"), prefixed::toString);

        IllegalArgumentException constant = assertThrows(IllegalArgumentException.class,
                () -> parseCallable("const type T = struct { int foo } return T{5};", false));
        assertTrue(constant.getMessage().contains("redundant"), constant::toString);
    }

    @Test
    void duplicateDeclarationsFieldsAndMixedConstructorsFailParsing() {
        assertThrows(IllegalArgumentException.class, () -> parseCallable(
                "struct T { int foo } record T { int bar } return T{5};", false));
        assertThrows(IllegalArgumentException.class, () -> parseCallable(
                "struct T { int foo, int foo } return T{5};", false));
        assertThrows(IllegalArgumentException.class, () -> parseCallable(
                "struct T { int foo } return T{5, foo: 5};", false));
    }

    @Test
    void unloweredLocalAggregatesFailClosedBeforeExecution() {
        Ast.Program parsed = Parser.parse("""
                pub fnc example(): T {
                    struct T { int foo }
                    return T{5};
                }
                """);
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(parsed));
    }
}
