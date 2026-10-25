package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ConstraintValidationBridge;
import dev.oreslang.compiler.TypeConstraintDescriptors;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ConstraintValidationBridgeTest {
    private static TypeConstraintDescriptors.Catalog catalog() {
        return TypeChecker.checkTypesWithDiagnostics(Parser.parse("""
                declare type Code = int with min 0 and max 5;
                declare type Other = int with min 10 and max 20;
                """)).constraintDescriptors();
    }

    @Test
    void validatesInclusiveEndpointsAndMintsIdentityBoundEvidence() {
        TypeConstraintDescriptors.Catalog catalog = catalog();

        Ast.Expr minimum = new Ast.LiteralExpr(0);
        Ast.Expr maximum = new Ast.LiteralExpr(5);

        var minEvidence = ConstraintValidationBridge.validateAndRecord(
                minimum, catalog, "Code", 0);
        var maxEvidence = ConstraintValidationBridge.validateAndRecord(
                maximum, catalog, "Code", 5);

        assertEquals("ores.type-constraint.v1", minEvidence.schemaId());
        assertEquals("Code", minEvidence.qualifiedTypeName());
        assertEquals(0L, minEvidence.value());
        assertEquals(5L, maxEvidence.value());
        assertEquals(catalog.find("Code").fingerprint(), minEvidence.fingerprint());
        assertSame(minEvidence, ConstraintValidationBridge.lookup(minimum));
        assertSame(maxEvidence, ConstraintValidationBridge.require(maximum));
    }

    @Test
    void reportsDescriptorOwnedErrorIdsAndNeverMintsOnFailure() {
        TypeConstraintDescriptors.Catalog catalog = catalog();

        Ast.Expr tooSmall = new Ast.LiteralExpr(-1);
        ConstraintValidationBridge.Violation minimum = assertThrows(
                ConstraintValidationBridge.Violation.class,
                () -> ConstraintValidationBridge.validateAndRecord(
                        tooSmall, catalog, "Code", -1));
        assertEquals("ORES_CONSTRAINT_MIN_INCLUSIVE_I64", minimum.errorId());
        assertNull(ConstraintValidationBridge.lookup(tooSmall));

        Ast.Expr tooLarge = new Ast.LiteralExpr(6);
        ConstraintValidationBridge.Violation maximum = assertThrows(
                ConstraintValidationBridge.Violation.class,
                () -> ConstraintValidationBridge.validateAndRecord(
                        tooLarge, catalog, "Code", 6));
        assertEquals("ORES_CONSTRAINT_MAX_INCLUSIVE_I64", maximum.errorId());
        assertNull(ConstraintValidationBridge.lookup(tooLarge));
    }

    @Test
    void structurallyEqualAstAndMetadataCopyCannotForgeConstraintProof() {
        TypeConstraintDescriptors.Catalog catalog = catalog();
        Ast.Expr checked = new Ast.LiteralExpr(3);
        Ast.Expr equalValueReplacement = new Ast.LiteralExpr(3);

        ConstraintValidationBridge.validateAndRecord(checked, catalog, "Code", 3);
        assertNotNull(ConstraintValidationBridge.lookup(checked));
        assertNull(ConstraintValidationBridge.lookup(equalValueReplacement));

        Ast.copyCheckedMetadata(checked, equalValueReplacement);
        assertNull(ConstraintValidationBridge.lookup(equalValueReplacement),
                "constraint provenance must not be copied by generic AST metadata transfer");
        assertThrows(IllegalStateException.class,
                () -> ConstraintValidationBridge.require(equalValueReplacement));
    }

    @Test
    void unrelatedSameRepresentationAliasCannotReuseEvidence() {
        TypeConstraintDescriptors.Catalog catalog = catalog();
        Ast.Expr checked = new Ast.LiteralExpr(3);
        ConstraintValidationBridge.validateAndRecord(checked, catalog, "Code", 3);

        assertEquals("Code", ConstraintValidationBridge.require(checked).qualifiedTypeName());
        ConstraintValidationBridge.Violation other = assertThrows(
                ConstraintValidationBridge.Violation.class,
                () -> ConstraintValidationBridge.validateAndRecord(
                        new Ast.LiteralExpr(3), catalog, "Other", 3));
        assertEquals("ORES_CONSTRAINT_MIN_INCLUSIVE_I64", other.errorId());
    }

    @Test
    void staleOrMalformedDescriptorCannotEnterCatalog() {
        var predicates = List.of(
                new TypeConstraintDescriptors.Predicate(
                        "min_inclusive_i64", ">=", 0,
                        "ORES_CONSTRAINT_MIN_INCLUSIVE_I64"),
                new TypeConstraintDescriptors.Predicate(
                        "max_inclusive_i64", "<=", 5,
                        "ORES_CONSTRAINT_MAX_INCLUSIVE_I64"));

        IllegalArgumentException stale = assertThrows(
                IllegalArgumentException.class,
                () -> new TypeConstraintDescriptors.Descriptor(
                        1,
                        TypeConstraintDescriptors.SCHEMA_ID,
                        "Code",
                        TypeConstraintDescriptors.REPRESENTATION_I64,
                        predicates,
                        true,
                        "0".repeat(64)));
        assertEquals("CONSTRAINT_FINGERPRINT_MISMATCH", stale.getMessage());

        TypeConstraintDescriptors.Descriptor valid = catalog().find("Code");
        IllegalArgumentException identity = assertThrows(
                IllegalArgumentException.class,
                () -> new TypeConstraintDescriptors.Catalog(Map.of("Other", valid)));
        assertEquals("CONSTRAINT_CATALOG_IDENTITY_MISMATCH", identity.getMessage());
    }

    @Test
    void importCompatibilityRequiresExactSchemaAndFingerprint() {
        TypeConstraintDescriptors.Catalog catalog = catalog();
        TypeConstraintDescriptors.Descriptor descriptor = catalog.find("Code");

        assertSame(descriptor, catalog.requireCompatible(
                "Code", descriptor.schemaId(), descriptor.fingerprint()));

        IllegalArgumentException schema = assertThrows(
                IllegalArgumentException.class,
                () -> catalog.requireCompatible(
                        "Code", "ores.type-constraint.v2", descriptor.fingerprint()));
        assertTrue(schema.getMessage().startsWith("CONSTRAINT_IMPORT_SCHEMA_MISMATCH"));

        IllegalArgumentException fingerprint = assertThrows(
                IllegalArgumentException.class,
                () -> catalog.requireCompatible(
                        "Code", descriptor.schemaId(), "0".repeat(64)));
        assertTrue(fingerprint.getMessage().startsWith(
                "CONSTRAINT_IMPORT_FINGERPRINT_MISMATCH"));

        assertThrows(IllegalArgumentException.class,
                () -> catalog.requireCompatible(
                        "Missing", descriptor.schemaId(), descriptor.fingerprint()));
    }

    @Test
    void evidenceConsumptionRechecksActiveCatalogAndAliasIdentity() {
        TypeConstraintDescriptors.Catalog original = catalog();
        Ast.Expr checked = new Ast.LiteralExpr(3);
        ConstraintValidationBridge.validateAndRecord(checked, original, "Code", 3);

        assertEquals("Code", ConstraintValidationBridge.requireCompatible(
                checked, original, "Code").qualifiedTypeName());

        IllegalStateException wrongAlias = assertThrows(
                IllegalStateException.class,
                () -> ConstraintValidationBridge.requireCompatible(
                        checked, original, "Other"));
        assertTrue(wrongAlias.getMessage().startsWith(
                "CONSTRAINT_PROVENANCE_TYPE_MISMATCH"));

        TypeConstraintDescriptors.Catalog changed =
                TypeChecker.checkTypesWithDiagnostics(Parser.parse("""
                        declare type Code = int with min 0 and max 6;
                        """)).constraintDescriptors();
        IllegalArgumentException stale = assertThrows(
                IllegalArgumentException.class,
                () -> ConstraintValidationBridge.requireCompatible(
                        checked, changed, "Code"));
        assertTrue(stale.getMessage().startsWith(
                "CONSTRAINT_IMPORT_FINGERPRINT_MISMATCH"));
    }

    @Test
    void existingRuntimeFenceRemainsUntilCheckedConversionIsIntegrated() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        declare type Code = int with min 0 and max 5;
                        fnc consume(Code code): int { return code; }
                        """)));
        assertTrue(error.getMessage().contains("CONSTRAINT_VALIDATION_BRIDGE_REQUIRED"),
                error::getMessage);
    }
}
