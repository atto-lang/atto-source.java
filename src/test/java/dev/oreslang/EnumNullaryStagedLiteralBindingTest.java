package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.EnumCaseSourceBindings;
import dev.oreslang.types.EnumCaseSourceBindings.Key;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.oreslang.types.EnumCaseOverloadPolicy.Domain.MODULE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline compiler-stage evidence, NOT TypeChecker-authenticated dispatch.
 * Real lexical binding and guest execution remain fail-closed.
 */
final class EnumNullaryStagedLiteralBindingTest {
    private static final Key KEY = new Key(
            MODULE, "local_unit.__root__", "foo", 1);

    private static Ast.CallExpr call(Ast.Program program) {
        Ast.FunctionDecl main = program.modules().getFirst().declarations().stream()
                .filter(d -> d instanceof Ast.FunctionDecl f && f.name().equals("main"))
                .map(d -> (Ast.FunctionDecl) d)
                .findFirst().orElseThrow();
        Ast.ExprStmt statement = assertInstanceOf(
                Ast.ExprStmt.class, main.body().getFirst());
        return assertInstanceOf(Ast.CallExpr.class, statement.expression());
    }

    @Test
    void bindingHasNoPublicForgingConstructor() {
        for (var constructor : EnumCaseSourceBindings.StagedLiteralBinding.class
                .getDeclaredConstructors()) {
            assertTrue(java.lang.reflect.Modifier.isPrivate(constructor.getModifiers()),
                    "a host caller must not be able to fabricate a static-binding receipt");
        }
    }

    @Test
    void parsedLiteralHasExactSelectedDeclarationAndUniqueStaticSymbol() {
        Ast.Program program = Parser.parse("""
                define enum Foo as A, B, C end
                fnc foo(Foo.A a): int { return 10; }
                fnc foo(Foo.B a): int { return 20; }
                fnc foo(Foo a): int { return 30; }
                fnc main(): void { foo(Foo.A); }
                """);
        var proof = EnumCaseSourceBindings.compileLocalProof(program);
        var selected = proof.bindStagedNullaryLiteralCall(KEY, call(program));
        assertEquals("Foo.A", selected.target().function()
                .parameters().getFirst().type().name());
        assertSame(call(program), selected.call());
        assertSame(selected.target().signature(), proof.families().get(KEY).getFirst().signature());
        assertEquals(selected.target().signature().symbolId(), selected.staticSlotId());

        var b = proof.bindStagedNullaryLiteralCall(KEY,
                new Ast.CallExpr(new Ast.NameExpr("foo"),
                        List.of(new Ast.MemberExpr(new Ast.NameExpr("Foo"), "B"))));
        var c = proof.bindStagedNullaryLiteralCall(KEY,
                new Ast.CallExpr(new Ast.NameExpr("foo"),
                        List.of(new Ast.MemberExpr(new Ast.NameExpr("Foo"), "C"))));
        assertEquals("Foo.B", b.target().function().parameters().getFirst().type().name());
        assertEquals("Foo", c.target().function().parameters().getFirst().type().name());
        assertNotEquals(selected.staticSlotId(), b.staticSlotId());
        assertNotEquals(selected.staticSlotId(), c.staticSlotId());

        // A staged syntactic call cannot bypass executable compiler admission.
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypes(program));
    }

    @Test
    void canonicalParsedEnumAndIntLiteralPinTwoArgumentStaticTargets() {
        Ast.Program program = Parser.parse("""
                define enum Foo as A, B, C end
                fnc foo(Foo.A a, int b): int { return b + 10; }
                fnc foo(Foo.B a, int b): int { return b + 20; }
                fnc foo(Foo a, int b): int { return b + 30; }
                fnc main(): void { foo(Foo.A, 5); }
                """);
        Key two = new Key(MODULE, "local_unit.__root__", "foo", 2);
        var proof = EnumCaseSourceBindings.compileLocalProof(program);
        var binding = proof.bindStagedNullaryLiteralCall(two, call(program));
        assertEquals("Foo.A",
                binding.target().function().parameters().getFirst().type().name());
        assertEquals(binding.target().signature().symbolId(), binding.staticSlotId());
        var b = proof.bindStagedNullaryLiteralCall(two,
                new Ast.CallExpr(new Ast.NameExpr("foo"),
                        List.of(new Ast.MemberExpr(new Ast.NameExpr("Foo"), "B"),
                                new Ast.LiteralExpr(5L))));
        assertEquals("Foo.B", b.target().function().parameters().getFirst().type().name());
        var c = proof.bindStagedNullaryLiteralCall(two,
                new Ast.CallExpr(new Ast.NameExpr("foo"),
                        List.of(new Ast.MemberExpr(new Ast.NameExpr("Foo"), "C"),
                                new Ast.LiteralExpr(5L))));
        assertEquals("Foo", c.target().function().parameters().getFirst().type().name());
        assertEquals(3, java.util.Set.of(binding.staticSlotId(),
                b.staticSlotId(), c.staticSlotId()).size());
        assertThrows(IllegalArgumentException.class, () ->
                proof.bindStagedNullaryLiteralCall(two, new Ast.CallExpr(
                        new Ast.NameExpr("foo"),
                        List.of(new Ast.MemberExpr(new Ast.NameExpr("Foo"), "A"),
                                new Ast.NameExpr("runtimeInt")))));
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypes(program));
    }

    @Test
    void rejectedShapesNeverFallbackToDynamicMultimethod() {
        Ast.Program program = Parser.parse("""
                define enum Foo as A, B end
                fnc foo(Foo.A a): void {}
                fnc foo(Foo.B a): void {}
                """);
        var proof = EnumCaseSourceBindings.compileLocalProof(program);
        for (Ast.CallExpr invalid : List.of(
                new Ast.CallExpr(new Ast.NameExpr("foo"),
                        List.of(new Ast.MemberExpr(new Ast.NameExpr("Foo"), "Missing"))),
                new Ast.CallExpr(new Ast.NameExpr("foo"),
                        List.of(new Ast.NameExpr("runtimeValue"))),
                new Ast.CallExpr(new Ast.NameExpr("foo"),
                        List.of(new Ast.MemberExpr(new Ast.NameExpr("Other"), "A"))),
                new Ast.CallExpr(new Ast.MemberExpr(new Ast.NameExpr("object"), "foo"),
                        List.of(new Ast.MemberExpr(new Ast.NameExpr("Foo"), "A"))),
                new Ast.CallExpr(new Ast.NameExpr("bar"),
                        List.of(new Ast.MemberExpr(new Ast.NameExpr("Foo"), "A"))))) {
            assertThrows(IllegalArgumentException.class,
                    () -> proof.bindStagedNullaryLiteralCall(KEY, invalid));
        }
        assertThrows(IllegalArgumentException.class,
                () -> proof.bindStagedNullaryLiteralCall(KEY,
                        new Ast.CallExpr(new Ast.NameExpr("foo"), List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> proof.bindStagedNullaryLiteralCall(KEY,
                        new Ast.CallExpr(new Ast.NameExpr("foo"),
                                List.of(Ast.TypeRef.simple("int")),
                                List.of(new Ast.MemberExpr(new Ast.NameExpr("Foo"), "A")))));
    }

    @Test
    void payloadsAndNonportableUnicodeCannotPretendToBeNullaryAotValues() {
        Ast.Program payload = Parser.parse("""
                define enum Foo as A: int, B: int end
                fnc foo(Foo.A a): void {}
                fnc foo(Foo.B a): void {}
                """);
        var proof = EnumCaseSourceBindings.compileLocalProof(payload);
        assertThrows(IllegalArgumentException.class, () ->
                proof.bindStagedNullaryLiteralCall(KEY,
                        new Ast.CallExpr(new Ast.NameExpr("foo"),
                                List.of(new Ast.MemberExpr(new Ast.NameExpr("Foo"), "A")))));

        Ast.Program unicode = Parser.parse("""
                define enum Café as Été, Hiver end
                fnc foo(Café.Été a): void {}
                fnc foo(Café.Hiver a): void {}
                """);
        var unicodeProof = EnumCaseSourceBindings.compileLocalProof(unicode);
        assertThrows(IllegalArgumentException.class, () ->
                unicodeProof.bindStagedNullaryLiteralCall(KEY,
                        new Ast.CallExpr(new Ast.NameExpr("foo"),
                                List.of(new Ast.MemberExpr(new Ast.NameExpr("Café"), "Été")))));
    }
}
