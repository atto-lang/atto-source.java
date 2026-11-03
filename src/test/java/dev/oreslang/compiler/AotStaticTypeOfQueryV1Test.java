package dev.oreslang.compiler;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiler-only type_of query admission; NOT executable AttoLang guest AOT.
 * The normal parser, type checker, ownership checker and descriptor decoder
 * are used rather than token scanning or host Java reflection.
 */
final class AotStaticTypeOfQueryV1Test {
    private static final String SOURCE = """
            pub define class Person as
              pub int visible: 7
              int hidden_secret_marker: 9
            end
            """;

    @Test
    void sourceGenericCallParsesAndBindsToResolvedPublicNominal() {
        var parsed = Parser.parseStaticTypeOfQueryV1("type_of<Person>()");
        assertEquals("Person", parsed.name());
        assertTrue(parsed.arguments().isEmpty());

        var result = OresCompiler.compileStaticTypeOfQueryV1(
                SOURCE, "pkg@revision1", "type_of<Person>()");
        assertEquals("__root__.Person", result.qualifiedType());
        assertEquals(64, result.typeIdHex().length());

        var decoded = AotReflectionTableV1Reader.decode(result.descriptorTable());
        assertEquals(1, decoded.types().size());
        var descriptor = decoded.types().getFirst();
        assertEquals(result.typeIdHex(), descriptor.typeIdHex());
        assertEquals("pkg@revision1", descriptor.packageIdentity());
        assertEquals("__root__.Person", descriptor.resolvedSymbol());
        assertEquals(1, descriptor.publicFields().size());
        assertEquals("visible", descriptor.publicFields().getFirst().name());
        assertFalse(new String(result.descriptorTable(), StandardCharsets.UTF_8)
                .contains("hidden_secret_marker"));
    }

    @Test
    void sameSourceQueryProducesStableImmutableDescriptor() {
        var first = OresCompiler.compileStaticTypeOfQueryV1(
                SOURCE, "pkg@revision1", "type_of<Person>()");
        var second = OresCompiler.compileStaticTypeOfQueryV1(
                SOURCE, "pkg@revision1", "type_of<Person>()");
        assertEquals(first.typeIdHex(), second.typeIdHex());
        assertArrayEquals(first.descriptorTable(), second.descriptorTable());
        byte[] output = first.descriptorTable();
        output[0] = (byte) 0;
        assertEquals((byte) 'A', first.descriptorTable()[0]);
        var differentPackage = OresCompiler.compileStaticTypeOfQueryV1(
                SOURCE, "different-pkg@revision", "type_of<Person>()");
        assertNotEquals(first.typeIdHex(), differentPackage.typeIdHex());
    }

    @Test
    void parserRejectsCallArgumentsDynamicTargetsAndGenericShapes() {
        for (String query : new String[]{
                "", "type_of<Person>", "type_of()", "type_of<Person>(7)",
                "type_of<Person,Other>()", "type_of<Person<int>>()",
                "type_of<Person>().fields()", "api.type_of<Person>()",
                "type_of<Person>() + 1", "type_of<_>()",
                "type_of<Person>() trailing", "type_of<Person>() ; type_of<Person>()"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parseStaticTypeOfQueryV1(query), query);
        }
    }

    @Test
    void privateOrMissingNominalsCannotBeReflected() {
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.compileStaticTypeOfQueryV1(
                        SOURCE, "pkg@revision1", "type_of<Missing>()"));
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.compileStaticTypeOfQueryV1(
                        "define class Secret as\n pub int exposed: 5\nend",
                        "pkg@revision1", "type_of<Secret>()"));
    }

    @Test
    void userFunctionCannotShadowTheStaticQueryIntrinsic() {
        String spoof = SOURCE + """
                fnc type_of(): int { return 0; }
                """;
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.compileStaticTypeOfQueryV1(
                        spoof, "pkg@revision1", "type_of<Person>()"));
    }

    @Test
    void queryAndPackageInputsAreBounded() {
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parseStaticTypeOfQueryV1("x".repeat(513)));
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.compileStaticTypeOfQueryV1(
                        SOURCE, "x".repeat(513), "type_of<Person>()"));
    }

    @Test
    void publicFieldTypeMustBeClosedAndExplicit() {
        String unresolved = """
                pub define class K as
                  pub UnknownType value;
                end
                """;
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.compileStaticTypeOfQueryV1(
                        unresolved, "pkg@revision1", "type_of<K>()"));
    }

    @Test
    void guestCallMustRejectUntilNativeLoweringExists() throws Exception {
        final String guestSource;
        try (var stream = AotStaticTypeOfQueryV1Test.class.getResourceAsStream(
                "/aot-reflection/type_of_unlowered_reject.atto")) {
            assertNotNull(stream, "missing committed .atto guest rejection fixture");
            guestSource = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(guestSource));
        assertTrue(error.getMessage().contains(
                "type_of<T>() requires compiler-backed guest AOT lowering"),
                error.getMessage());
    }


    @Test
    void committedAttoDeclarationFixtureBindsDeterministically() throws Exception {
        final String attoSource;
        try (var stream = AotStaticTypeOfQueryV1Test.class.getResourceAsStream(
                "/aot-reflection/closed_nominal.atto")) {
            assertNotNull(stream, "missing committed .atto public nominal fixture");
            attoSource = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var result = OresCompiler.compileStaticTypeOfQueryV1(
                attoSource, "pkg@revision1", "type_of<Person>()");
        var table = AotReflectionTableV1Reader.decode(result.descriptorTable());
        assertEquals(1, table.types().size());
        assertEquals(result.typeIdHex(), table.types().getFirst().typeIdHex());
        assertEquals("visible", table.types().getFirst().publicFields().getFirst().name());
        assertFalse(new String(result.descriptorTable(), StandardCharsets.UTF_8)
                .contains("hidden_secret_marker"));
    }

}
