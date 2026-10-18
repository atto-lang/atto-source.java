package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.EnumCaseSourceBindings;
import dev.oreslang.types.EnumCaseSourceBindings.Key;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.oreslang.types.EnumCaseOverloadPolicy.Domain.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Parsed-source -> static overload proof only. The production TypeChecker
 * deliberately rejects these EnumDecls pending full value/runtime integration.
 */
final class EnumCaseSourceBindingsTest {
    private static final String SOURCE = """
            define enum Foo as A, B, C end
            pub fnc foo(Foo.A a, int b): void {}
            pub fnc foo(Foo.B a, int b): void {}
            pub fnc foo(Foo all, int b): void {}
            """;

    @Test
    void actualParserAstAdmitsCanonicalExampleToStaticProofOnly() {
        Ast.Program program = Parser.parse(SOURCE);
        EnumCaseSourceBindings.Proof proof =
                EnumCaseSourceBindings.compileLocalProof(program);
        Key key = new Key(MODULE, "local_unit.__root__", "foo", 2);
        assertEquals(3, proof.families().get(key).size());
        assertEquals("Foo.A", proof.select(key,
                List.of(Ast.TypeRef.simple("Foo.A"), Ast.TypeRef.simple("int")))
                .function().parameters().getFirst().type().name());
        assertEquals("Foo.B", proof.select(key,
                List.of(Ast.TypeRef.simple("Foo.B"), Ast.TypeRef.simple("int")))
                .function().parameters().getFirst().type().name());
        assertEquals("Foo", proof.select(key,
                List.of(Ast.TypeRef.simple("Foo"), Ast.TypeRef.simple("int")))
                .function().parameters().getFirst().type().name());
        assertEquals("Foo", proof.select(key,
                List.of(Ast.TypeRef.simple("Foo.C"), Ast.TypeRef.simple("int")))
                .function().parameters().getFirst().type().name());
        IllegalArgumentException blocked = assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypes(program));
        assertTrue(blocked.getMessage().contains("syntax-only"));
    }

    @Test
    void noParentFallbackMakesUnrefinedFooCompileProofInvalid() {
        Ast.Program parsed = Parser.parse("""
                define enum Foo as A, B end
                fnc foo(Foo.A a): void {}
                fnc foo(Foo.B a): void {}
                """);
        var proof = EnumCaseSourceBindings.compileLocalProof(parsed);
        Key key = new Key(MODULE, "local_unit.__root__", "foo", 1);
        assertThrows(IllegalArgumentException.class, () ->
                proof.select(key, List.of(Ast.TypeRef.simple("Foo"))));
        assertEquals("Foo.B", proof.select(key, List.of(Ast.TypeRef.simple("Foo.B")))
                .function().parameters().getFirst().type().name());
    }

    @Test
    void crossedSpecializationIsRejectedAtDeclarationTime() {
        Ast.Program parsed = Parser.parse("""
                define enum Foo as A, B end
                define enum Bar as A, B end
                fnc f(Foo.A a, Bar b): void {}
                fnc f(Foo a, Bar.B b): void {}
                """);
        assertThrows(IllegalArgumentException.class,
                () -> EnumCaseSourceBindings.compileLocalProof(parsed));
    }

    @Test
    void ordinaryPrimitivesNeverCreateAnOverloadSlot() {
        Ast.Program parsed = Parser.parse("""
                define enum Foo as A, B end
                fnc f(Foo.A a, int n): void {}
                fnc f(Foo.B a, string n): void {}
                """);
        assertThrows(IllegalArgumentException.class,
                () -> EnumCaseSourceBindings.compileLocalProof(parsed));
    }

    @Test
    void typeQualifiedCasesAreCheckedAgainstActualEnums() {
        Ast.Program parsed = Parser.parse("""
                define enum Foo as A, B end
                fnc f(Foo.A a): void {}
                fnc f(Foo.Missing b): void {}
                """);
        assertThrows(IllegalArgumentException.class,
                () -> EnumCaseSourceBindings.compileLocalProof(parsed));
    }

    @Test
    void regularClassMethodsUseDistinctStaticAndInstanceDomains() {
        Ast.Program parsed = Parser.parse("""
                define enum Foo as A, B end
                define class C as
                    pub handle(Foo.A a): void {}
                    pub handle(Foo.B a): void {}
                    pub static fnc handle(Foo.A a): void {}
                    pub static fnc handle(Foo.B a): void {}
                end
                """);
        var proof = EnumCaseSourceBindings.compileLocalProof(parsed);
        Key instance = new Key(INSTANCE, "local_unit.__root__.C", "handle", 1);
        Key statik = new Key(STATIC, "local_unit.__root__.C", "handle", 1);
        assertEquals(2, proof.families().get(instance).size());
        assertEquals(2, proof.families().get(statik).size());
        assertNotEquals(proof.select(instance, List.of(Ast.TypeRef.simple("Foo.A")))
                .signature().symbolId(),
                proof.select(statik, List.of(Ast.TypeRef.simple("Foo.A")))
                .signature().symbolId());
    }

    @Test
    void duplicateEnumFamiliesAndImportedUnprovenLinkageFailClosed() {
        Ast.Program parsed = Parser.parse("""
                define enum Foo as A, B end
                define enum Foo as A, B end
                fnc foo(Foo.A a): void {}
                """);
        assertThrows(IllegalArgumentException.class,
                () -> EnumCaseSourceBindings.compileLocalProof(parsed));
        Ast.Program imported = Parser.parse("""
                import type Foo from "lib"
                define enum Foo as A end
                fnc foo(Foo.A a): void {}
                """);
        assertThrows(IllegalArgumentException.class,
                () -> EnumCaseSourceBindings.compileLocalProof(imported));
    }

    @Test
    void unsafeEnumPayloadDisqualifiesTheWholeFamily() {
        Ast.Program parsed = Parser.parse("""
                define enum Foo as A, B: List<int> end
                fnc f(Foo.A a): void {}
                fnc f(Foo.B a): void {}
                """);
        assertThrows(IllegalArgumentException.class,
                () -> EnumCaseSourceBindings.compileLocalProof(parsed));
    }
}
