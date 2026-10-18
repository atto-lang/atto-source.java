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
        assertEquals("Foo.A", proof.selectStagedTypeRefs(key,
                List.of(Ast.TypeRef.simple("Foo.A"), Ast.TypeRef.simple("int")))
                .function().parameters().getFirst().type().name());
        assertEquals("Foo.B", proof.selectStagedTypeRefs(key,
                List.of(Ast.TypeRef.simple("Foo.B"), Ast.TypeRef.simple("int")))
                .function().parameters().getFirst().type().name());
        assertEquals("Foo", proof.selectStagedTypeRefs(key,
                List.of(Ast.TypeRef.simple("Foo"), Ast.TypeRef.simple("int")))
                .function().parameters().getFirst().type().name());
        assertEquals("Foo", proof.selectStagedTypeRefs(key,
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
                proof.selectStagedTypeRefs(key, List.of(Ast.TypeRef.simple("Foo"))));
        assertEquals("Foo.B", proof.selectStagedTypeRefs(key, List.of(Ast.TypeRef.simple("Foo.B")))
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
        assertNotEquals(proof.selectStagedTypeRefs(instance, List.of(Ast.TypeRef.simple("Foo.A")))
                .signature().symbolId(),
                proof.selectStagedTypeRefs(statik, List.of(Ast.TypeRef.simple("Foo.A")))
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
    void genericActorAndInheritedSpecializationsRequireSeparateProof() {
        Ast.Program generic = Parser.parse("""
                define enum Foo as A, B end
                fnc f<T>(Foo.A a, T v): void {}
                """);
        assertThrows(IllegalArgumentException.class,
                () -> EnumCaseSourceBindings.compileLocalProof(generic));

        Ast.Program inherited = Parser.parse("""
                define enum Foo as A, B end
                define class Base as
                    pub f(int n): void {}
                end
                define class Derived extends Base as
                    pub f(Foo.A a): void {}
                end
                """);
        assertThrows(IllegalArgumentException.class,
                () -> EnumCaseSourceBindings.compileLocalProof(inherited));
    }

    @Test
    void lexicalNominalCollisionAndUnprovenFixedTypesFailClosed() {
        for (String declaration : List.of(
                "define class Foo as end",
                "define type Foo = int;")) {
            Ast.Program parsed = Parser.parse(
                    "define enum Foo as A end\n" + declaration
                    + "\nfnc f(Foo.A a): void {}");
            assertThrows(IllegalArgumentException.class,
                    () -> EnumCaseSourceBindings.compileLocalProof(parsed));
        }
        Ast.Program unsafe = Parser.parse("""
                define enum Foo as A, B end
                fnc f(Foo.A a, User u): void {}
                fnc f(Foo.B a, User u): void {}
                """);
        assertThrows(IllegalArgumentException.class,
                () -> EnumCaseSourceBindings.compileLocalProof(unsafe));
    }

    @Test
    void scalarAliasesAreCanonicalizedBeforeSelection() {
        Ast.Program parsed = Parser.parse("""
                define enum Foo as A, B end
                fnc f(Foo.A a, String s): void {}
                fnc f(Foo.B a, string s): void {}
                """);
        var proof = EnumCaseSourceBindings.compileLocalProof(parsed);
        Key key = new Key(MODULE, "local_unit.__root__", "f", 2);
        assertEquals("Foo.A",
                proof.selectStagedTypeRefs(key, List.of(
                        Ast.TypeRef.simple("Foo.A"), Ast.TypeRef.simple("string")))
                        .function().parameters().getFirst().type().name());
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
    @Test
    void nullaryAotValueTagsAreDerivedFromRealSourceWithSchemaInvalidation() {
        Ast.Program program = Parser.parse("""
                define enum Foo as A = 5, B = 17, C = 31 end
                fnc use(Foo.A v): void {}
                fnc use(Foo.B v): void {}
                """);
        var proof = EnumCaseSourceBindings.compileLocalProof(program);
        var layout = proof.nullaryTagLayouts().get("local_unit.__root__.Foo");
        assertNotNull(layout);
        assertEquals(3, layout.caseCount());
        assertEquals(5, layout.constantTag("A"));
        assertEquals(17, layout.constantTag("B"));
        assertEquals(31, layout.constantTag("C"));
        assertTrue(layout.containsWireTag(17));
        assertFalse(layout.containsWireTag(16));
        assertFalse(layout.containsWireTag(-1));
        assertThrows(IllegalArgumentException.class, () -> layout.constantTag("Unknown"));
        assertEquals(64, layout.schemaFingerprint().length());
        // Golden shared with LLVM's canonical length-prefixed wire schema.
        assertEquals("d1348f4be12ad08ecddbd97fda273dcaf65b0c492958ef69f74e477f87112844",
                layout.schemaFingerprint());
        assertDoesNotThrow(() -> layout.requireExactSchema(layout.schemaFingerprint()));
        assertThrows(IllegalArgumentException.class, () -> layout.requireExactSchema("0".repeat(64)));

        Ast.Program retagged = Parser.parse("""
                define enum Foo as A = 5, B = 18, C = 31 end
                fnc use(Foo.A v): void {}
                fnc use(Foo.B v): void {}
                """);
        var replacement = EnumCaseSourceBindings.compileLocalProof(retagged)
                .nullaryTagLayouts().get("local_unit.__root__.Foo");
        assertNotEquals(layout.schemaFingerprint(), replacement.schemaFingerprint());
        assertThrows(IllegalArgumentException.class,
                () -> replacement.requireExactSchema(layout.schemaFingerprint()));
    }

    @Test
    void aotNullaryTagsNeverAuthorizeUnsafePayloadOrForgedValueDispatch() {
        Ast.Program payload = Parser.parse("""
                define enum Foo as A, B: int end
                fnc use(Foo.A value): void {}
                """);
        assertFalse(EnumCaseSourceBindings.compileLocalProof(payload)
                .nullaryTagLayouts().containsKey("local_unit.__root__.Foo"));
        Ast.Program ordinary = Parser.parse("fnc main(): int { return 42; }");
        assertTrue(EnumCaseSourceBindings.compileLocalProof(ordinary).nullaryTagLayouts().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> new dev.oreslang.types.EnumTag32Plan(
                        "unit." + "A".repeat(508), List.of(
                                new dev.oreslang.types.EnumTag32Plan.CaseTag("A", 1))));
        assertThrows(IllegalArgumentException.class,
                () -> new dev.oreslang.types.EnumTag32Plan(
                        "unqualified", List.of(
                                new dev.oreslang.types.EnumTag32Plan.CaseTag("A", 1))));
        assertThrows(IllegalArgumentException.class,
                () -> new dev.oreslang.types.EnumTag32Plan("unit.Foo", List.of(
                        new dev.oreslang.types.EnumTag32Plan.CaseTag("A", 2),
                        new dev.oreslang.types.EnumTag32Plan.CaseTag("B", 1))));
    }

}
