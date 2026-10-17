package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.ZordeSchemaGenerator;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.RefinementRules;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class ZordeSchemaGeneratorTest {
    @Test void annotationsKeywordAndSymbolsEmitIdenticalSchema() {
        var ann = ZordeSchemaGenerator.planSource(
                "namespace app; @Min(1) @Max(65535) type Port = int;");
        var named = ZordeSchemaGenerator.planSource(
                "namespace app; @Range(min=1,max=65535) type Port = int;");
        var word = ZordeSchemaGenerator.planSource(
                "namespace app; type Port = int with value gte 1 and value lte 65535;");
        var symbol = ZordeSchemaGenerator.planSource(
                "namespace app; type Port = int with value >= 1 and value <= 65535;");
        var expected = ann.schema("app.Port");
        assertEquals(expected, named.schema("app.Port"));
        assertEquals(expected, word.schema("app.Port"));
        assertEquals(expected, symbol.schema("app.Port"));
        assertEquals(64, expected.fingerprint().length());
        assertTrue(expected.accepts(1L));
        assertTrue(expected.accepts(65535L));
        assertFalse(expected.accepts(0L));
        assertFalse(expected.accepts(65536L));
        assertFalse(expected.accepts(1.0));
        assertThrows(IllegalArgumentException.class, () -> ann.schema("other.Port"));
    }

    @Test void unicodeLengthAnnotationParityAndStrictHostValues() {
        var ann = ZordeSchemaGenerator.planSource(
                "namespace app; @MinLength(1) @MaxLength(2) type Name = string;");
        var expression = ZordeSchemaGenerator.planSource(
                "namespace app; type Name = string with length >= 1 and length <= 2;");
        assertEquals(ann.schema("app.Name"), expression.schema("app.Name"));
        var schema = ann.schema("app.Name");
        assertTrue(schema.accepts("😀"));
        assertTrue(schema.accepts("e\u0301"));
        assertFalse(schema.accepts(""));
        assertFalse(schema.accepts("abc"));
        assertFalse(schema.accepts(String.valueOf((char) 0xD800)));
    }

    @Test void qualifiedNominalNamesAvoidCollisions() {
        var a = ZordeSchemaGenerator.planSource(
                "namespace app; @MinLength(1) define newtype Username(string);");
        var b = ZordeSchemaGenerator.planSource(
                "namespace other; @MinLength(1) define newtype Username(string);");
        var one = a.schema("app.Username");
        var two = b.schema("other.Username");
        assertNotEquals(one.fingerprint(), two.fingerprint());
        assertEquals("app.Username", one.nominalIdentity());
        assertThrows(IllegalArgumentException.class, () -> ZordeSchemaGenerator.planSource(
                "@MinLength(1) define newtype Username(string);"));
    }

    @Test void planIsImmutableDeterministicallyOrderedAndModuleQualified() {
        var plan = ZordeSchemaGenerator.planSource(
                "namespace app; type Z = int with value gte 0; "
                + "type A = int with value lte 10;");
        assertEquals(List.of("app.A", "app.Z"), List.copyOf(plan.schemas().keySet()));
        assertThrows(UnsupportedOperationException.class,
                () -> plan.schemas().clear());
    }

    @Test void unsupportedSemanticsFailClosed() {
        assertThrows(UnsupportedOperationException.class, () -> ZordeSchemaGenerator.planSource(
                "namespace app; type NonBlank = string where it.trim().length > 0;"));
        assertThrows(IllegalArgumentException.class, () -> ZordeSchemaGenerator.planSource(
                "namespace app; type Dup = int with value gte 1; "
                        + "type Dup = int with value lte 2;"));
        assertThrows(IllegalArgumentException.class, () -> ZordeSchemaGenerator.planSource(
                "namespace app; type Missing = int with value gt 9223372036854775807;"));
        assertThrows(IllegalArgumentException.class, () -> ZordeSchemaGenerator.planSource(
                "namespace app; @Range(min=6,max=5) type Invalid = int;"));

        var imported = new Ast.Program("app",
                List.of(new Ast.ImportDecl(Ast.ImportKind.TYPE,
                        List.of("Remote"), false, null, "external")),
                List.of(new Ast.ModuleDecl("module", List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> ZordeSchemaGenerator.plan(imported));
    }

    @Test void schemaPlanningCannotMakeRefinementsExecutable() {
        String source = "namespace app; @Range(min=1,max=65535) type Port = int;";
        assertNotNull(ZordeSchemaGenerator.planSource(source).schema("app.Port"));
        var err = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck(source));
        assertTrue(err.getMessage().contains("not yet executable"), err::getMessage);
    }
}
