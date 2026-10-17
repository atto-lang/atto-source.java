package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ZordeSchemaGenerator;
import dev.oreslang.types.RefinementRules;
import dev.oreslang.types.StaticZordeSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Closed-world schema-first prototypes are not executable guest declarations yet. */
final class StaticZordeSchemaTest {
    @Test void canonicalDescriptorControlsTheInferredTypeAcrossAllSpellings() {
        var withExclusive = ZordeSchemaGenerator.planSource(
                "namespace app; type NonEmpty = string with length gt 0;");
        var annotated = ZordeSchemaGenerator.planSource(
                "namespace app; @MinLength(1) type NonEmpty = string;");
        var first = ZordeSchemaGenerator.planStaticSchemas("app",
                Map.of("NonEmpty", StaticZordeSchema.string().minLength(1)), Set.of());

        var canonicalType = first.inferType("app.NonEmpty").outputRefinement();
        assertEquals(canonicalType, withExclusive.inferType("app.NonEmpty").outputRefinement());
        assertEquals(canonicalType, annotated.inferType("app.NonEmpty").outputRefinement());
        assertEquals(List.of(new Ast.RefinementBound("length", "gte", 1)),
                canonicalType.bounds());
        assertEquals(first.schema("app.NonEmpty").fingerprint(),
                annotated.schema("app.NonEmpty").fingerprint());

        var exact = ZordeSchemaGenerator.planStaticSchemas("app",
                Map.of("Exactly42", StaticZordeSchema.int64().min(42).max(42)), Set.of());
        assertEquals(List.of(new Ast.RefinementBound("value", "eq", 42)),
                exact.inferType("app.Exactly42").outputRefinement().bounds());
        assertTrue(exact.tryFrom("app.Exactly42", 42L).ok());
        assertFalse(exact.tryFrom("app.Exactly42", 43L).ok());

        var redundant = ZordeSchemaGenerator.planStaticSchemas("app",
                Map.of("Range", StaticZordeSchema.int64().min(1).max(3)
                        .excluding(0).excluding(2).excluding(4)), Set.of());
        assertEquals(List.of(
                new Ast.RefinementBound("value", "gte", 1),
                new Ast.RefinementBound("value", "lte", 3),
                new Ast.RefinementBound("value", "ne", 2)),
                redundant.inferType("app.Range").outputRefinement().bounds());
        assertFalse(redundant.tryFrom("app.Range", 2L).ok());
        assertTrue(redundant.tryFrom("app.Range", 1L).ok());
    }

    @Test void schemaFirstAndAnnotationFirstHaveIdenticalTypeAndValidator() {
        var port = StaticZordeSchema.int64().min(1).max(65535);
        var first = ZordeSchemaGenerator.planStaticSchemas(
                "app", Map.of("Port", port), Set.of());
        var second = ZordeSchemaGenerator.planSource(
                "namespace app; @Range(min=1,max=65535) type Port = int;");
        var third = ZordeSchemaGenerator.planSource(
                "namespace app; type Port = int with value >= 1 and value <= 65535;");
        var built = first.schema("app.Port");
        assertEquals(second.schema("app.Port"), built);
        assertEquals(third.schema("app.Port"), built);
        assertEquals(second.inferType("app.Port").outputRefinement().bounds().size(), 2);
        assertEquals("int", first.inferType("app.Port").inputBase().name());
        assertEquals(second.inferType("app.Port").schemaFingerprint(),
                first.inferType("app.Port").schemaFingerprint());
        assertEquals(built, third.schema("app.Port"));
        for (long value : new long[]{Long.MIN_VALUE, -1, 0, 1, 443, 65535, 65536, Long.MAX_VALUE}) {
            boolean expected = first.schema("app.Port").accepts(value);
            assertEquals(expected, port.acceptsLiteral(value));
            assertEquals(expected, second.schema("app.Port").accepts(value));
            assertEquals(expected, first.tryFrom("app.Port", value).ok());
        }
        assertFalse(first.tryFrom("app.Port", 443.0).ok());
        assertFalse(first.tryFrom("app.Port", 443).ok());
    }

    @Test void schemaFirstStringLengthAndNominalTypeRemainSafe() {
        var username = StaticZordeSchema.string().minLength(1).maxLength(32);
        var first = ZordeSchemaGenerator.planStaticSchemas(
                "app", Map.of("Username", username), Set.of("Username"));
        var second = ZordeSchemaGenerator.planSource(
                "namespace app; @MinLength(1) @MaxLength(32) define newtype Username(string);");
        assertEquals(second.schema("app.Username"), first.schema("app.Username"));
        assertEquals(second.inferType("app.Username").outputRefinement(),
                first.inferType("app.Username").outputRefinement());
        assertEquals("app.Username", first.inferType("app.Username").nominalIdentity());
        for (String value : new String[]{"", "a", "😀", "e\u0301", "x".repeat(32),
                "x".repeat(33), String.valueOf((char)0xD800), String.valueOf((char)0xDC00)}) {
            boolean expected = first.schema("app.Username").accepts(value);
            assertEquals(expected, username.acceptsLiteral(value));
            assertEquals(expected, second.schema("app.Username").accepts(value));
            assertEquals(expected, first.tryFrom("app.Username", value).ok());
        }
    }

    @Test void buildersAreImmutableAndRejectUnsupportedConstraints() {
        var base = StaticZordeSchema.int64();
        var limited = base.min(1);
        assertTrue(base.acceptsLiteral(0L));
        assertFalse(limited.acceptsLiteral(0L));
        assertThrows(IllegalArgumentException.class, () -> limited.max(0));
        assertThrows(IllegalArgumentException.class,
                () -> StaticZordeSchema.string().minLength(-1));
        assertThrows(IllegalArgumentException.class,
                () -> StaticZordeSchema.string().maxLength(-1));
        assertThrows(IllegalArgumentException.class,
                () -> StaticZordeSchema.string().min(1));
        assertThrows(IllegalArgumentException.class,
                () -> StaticZordeSchema.int64().minLength(1));
        assertThrows(IllegalArgumentException.class,
                () -> StaticZordeSchema.int64().greaterThan(Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> StaticZordeSchema.int64().lessThan(Long.MIN_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> StaticZordeSchema.int64().min(1).max(2).excluding(1).excluding(2));
        assertThrows(UnsupportedOperationException.class, () ->
                StaticZordeSchema.fromRefinement(Ast.TypeRef.simple("string"),
                        new Ast.RefinementSpec(List.of(), "it.trim().length > 0", false)));
    }

    @Test void plainAndBrandedStaticSchemasInferDifferentTypes() {
        var primitive = ZordeSchemaGenerator.planStaticSchemas(
                "app", Map.of("Plain", StaticZordeSchema.int64()), Set.of());
        var nominal = ZordeSchemaGenerator.planStaticSchemas(
                "app", Map.of("Tagged", StaticZordeSchema.int64()), Set.of("Tagged"));
        assertNull(primitive.inferType("app.Plain").outputRefinement());
        assertTrue(nominal.inferType("app.Tagged").outputRefinement().nominal());
        assertTrue(nominal.tryFrom("app.Tagged", Long.MIN_VALUE).ok());
        assertNotEquals(primitive.schema("app.Plain").fingerprint(),
                nominal.schema("app.Tagged").fingerprint());
        assertThrows(IllegalArgumentException.class, () ->
                ZordeSchemaGenerator.planStaticSchemas("app", Map.of(), Set.of("Ghost")));
        assertThrows(IllegalArgumentException.class, () ->
                ZordeSchemaGenerator.planStaticSchemas(null,
                        Map.of("Plain", StaticZordeSchema.int64()), Set.of()));
        assertThrows(IllegalArgumentException.class, () ->
                ZordeSchemaGenerator.planStaticSchemas("app",
                        Map.of("Illegal.Name", StaticZordeSchema.int64()), Set.of()));
    }

    @Test void proofAndRuntimeScalarEvaluatorHaveOneAcceptanceSemantics() {
        Ast.TypeRef intType = Ast.TypeRef.simple("int");
        var spec = new Ast.RefinementSpec(List.of(
                new Ast.RefinementBound("value", "gte", 0),
                new Ast.RefinementBound("value", "lte", 100),
                new Ast.RefinementBound("value", "ne", 42)), null, false);
        var descriptor = RefinementRules.descriptor(null, intType, spec);
        for (long candidate : new long[]{Long.MIN_VALUE, -1, 0, 1, 41, 42, 43,
                100, 101, Long.MAX_VALUE}) {
            boolean proof = RefinementRules.normalize(intType, spec.bounds()).contains(candidate);
            assertEquals(proof, descriptor.accepts(candidate));
            assertEquals(proof, RefinementRules.accepts(intType, spec, candidate));
        }
    }
}
