package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.EnumV2Migration;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Compatibility-only schema tests; do NOT admit enum guest execution. */
final class EnumV2MigrationTest {
    private static Ast.TypeRef payload() {
        return Ast.TypeRef.recordType(Map.of("delta", Ast.TypeRef.simple("int")));
    }

    @Test
    void recordOnlyLegacyDescriptorExactlyMatchesParsedPublicV2Schema() {
        Ast.Program parsed = Parser.parse("""
                define pub enum Messages as
                    Reset = 7,
                    Delta: {delta: int} = 11
                end
                """);
        Ast.EnumDecl schema = assertInstanceOf(Ast.EnumDecl.class,
                parsed.modules().getFirst().declarations().getFirst());
        EnumV2Migration.LegacyEnum old = new EnumV2Migration.LegacyEnum(
                "Messages", Ast.Visibility.PUBLIC, List.of(
                        new EnumV2Migration.LegacyVariant("Reset", null, 7L),
                        new EnumV2Migration.LegacyVariant("Delta", payload(), 11L)));
        assertEquals(Ast.Visibility.PUBLIC, schema.visibility());
        assertEquals(List.of(7, 11), schema.cases().stream().map(Ast.EnumCase::tag).toList());
        assertTrue(EnumV2Migration.losslesslyEquivalent(old, schema));
        assertEquals(schema, EnumV2Migration.toV2(old));
        assertEquals(old, EnumV2Migration.toLegacyRecordSubset(schema));
        assertThrows(IllegalArgumentException.class,
                () -> dev.oreslang.types.TypeChecker.checkTypes(parsed),
                "schema conversion must not silently enable enum runtime");
    }

    @Test
    void rejectOutOfRangeOrReordered64BitLegacyWireTagsWithoutTruncation() {
        var tooLarge = new EnumV2Migration.LegacyEnum(
                "Events", Ast.Visibility.PRIVATE, List.of(
                        new EnumV2Migration.LegacyVariant("Old", null, (long) Integer.MAX_VALUE + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> EnumV2Migration.toV2(tooLarge));
        var reordered = new EnumV2Migration.LegacyEnum(
                "Events", Ast.Visibility.PRIVATE, List.of(
                        new EnumV2Migration.LegacyVariant("Later", null, 9L),
                        new EnumV2Migration.LegacyVariant("Earlier", null, 8L)));
        assertThrows(IllegalArgumentException.class,
                () -> EnumV2Migration.toV2(reordered));
        assertThrows(IllegalArgumentException.class, () ->
                new EnumV2Migration.LegacyVariant("Invalid", null, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new EnumV2Migration.LegacyVariant("Invalid", null, -1));
    }

    @Test
    void rejectDuplicateNamesOrTagsAndUnrepresentableScalarPayloads() {
        assertThrows(IllegalArgumentException.class, () ->
                new EnumV2Migration.LegacyEnum("E", Ast.Visibility.PUBLIC, List.of(
                        new EnumV2Migration.LegacyVariant("A", null, 1L),
                        new EnumV2Migration.LegacyVariant("A", null, 2L))));
        assertThrows(IllegalArgumentException.class, () ->
                new EnumV2Migration.LegacyEnum("E", Ast.Visibility.PUBLIC, List.of(
                        new EnumV2Migration.LegacyVariant("A", null, 1L),
                        new EnumV2Migration.LegacyVariant("B", null, 1L))));
        assertThrows(IllegalArgumentException.class, () ->
                new EnumV2Migration.LegacyVariant(
                        "A", Ast.TypeRef.simple("int"), 1L));
        Ast.EnumDecl modern = new Ast.EnumDecl("Payload", Ast.Visibility.PRIVATE,
                List.of(new Ast.EnumCase("HasScalar", Ast.TypeRef.simple("int"), 1)));
        assertThrows(IllegalArgumentException.class,
                () -> EnumV2Migration.toLegacyRecordSubset(modern));
    }

    @Test
    void payloadAndVisibilityDriftInvalidateDescriptorEquivalence() {
        var old = new EnumV2Migration.LegacyEnum(
                "Event", Ast.Visibility.PUBLIC, List.of(
                        new EnumV2Migration.LegacyVariant("A", payload(), 4L)));
        assertFalse(EnumV2Migration.losslesslyEquivalent(old,
                new Ast.EnumDecl("Event", Ast.Visibility.PRIVATE,
                        List.of(new Ast.EnumCase("A", payload(), 4)))));
        assertFalse(EnumV2Migration.losslesslyEquivalent(old,
                new Ast.EnumDecl("Event", Ast.Visibility.PUBLIC,
                        List.of(new Ast.EnumCase("A", payload(), 5)))));
        assertFalse(EnumV2Migration.losslesslyEquivalent(old,
                new Ast.EnumDecl("Different", Ast.Visibility.PUBLIC,
                        List.of(new Ast.EnumCase("A", payload(), 4)))));
    }

    @Test
    void compatibilityConstructorsDefaultToPrivateRatherThanPublic() {
        Ast.EnumDecl legacySyntax = new Ast.EnumDecl(
                "Foo", List.of(new Ast.EnumCase("A", null, 1)));
        assertEquals(Ast.Visibility.PRIVATE, legacySyntax.visibility());
        assertThrows(IllegalArgumentException.class,
                () -> new Ast.EnumDecl("Foo", null, legacySyntax.cases()));
    }
}
