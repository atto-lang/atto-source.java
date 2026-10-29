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
        assertSame(minEvidence, ConstraintValidationBridge.requireCompatible(
                minimum, catalog, "Code"));
        assertSame(maxEvidence, ConstraintValidationBridge.requireCompatible(
                maximum, catalog, "Code"));
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
        assertThrows(IllegalStateException.class,
                () -> ConstraintValidationBridge.requireCompatible(
                        tooSmall, catalog, "Code"));

        Ast.Expr tooLarge = new Ast.LiteralExpr(6);
        ConstraintValidationBridge.Violation maximum = assertThrows(
                ConstraintValidationBridge.Violation.class,
                () -> ConstraintValidationBridge.validateAndRecord(
                        tooLarge, catalog, "Code", 6));
        assertEquals("ORES_CONSTRAINT_MAX_INCLUSIVE_I64", maximum.errorId());
        assertThrows(IllegalStateException.class,
                () -> ConstraintValidationBridge.requireCompatible(
                        tooLarge, catalog, "Code"));
    }

    @Test
    void structurallyEqualAstAndMetadataCopyCannotForgeConstraintProof() {
        TypeConstraintDescriptors.Catalog catalog = catalog();
        Ast.Expr checked = new Ast.LiteralExpr(3);
        Ast.Expr equalValueReplacement = new Ast.LiteralExpr(3);

        ConstraintValidationBridge.validateAndRecord(checked, catalog, "Code", 3);
        assertEquals("Code", ConstraintValidationBridge.requireCompatible(
                checked, catalog, "Code").qualifiedTypeName());
        assertThrows(IllegalStateException.class,
                () -> ConstraintValidationBridge.requireCompatible(
                        equalValueReplacement, catalog, "Code"));

        Ast.copyCheckedMetadata(checked, equalValueReplacement);
        assertThrows(IllegalStateException.class,
                () -> ConstraintValidationBridge.requireCompatible(
                        equalValueReplacement, catalog, "Code"),
                "constraint provenance must not be copied by generic AST metadata transfer");
    }

    @Test
    void unrelatedSameRepresentationAliasCannotReuseEvidence() {
        TypeConstraintDescriptors.Catalog catalog = catalog();
        Ast.Expr checked = new Ast.LiteralExpr(3);
        ConstraintValidationBridge.validateAndRecord(checked, catalog, "Code", 3);

        assertEquals("Code", ConstraintValidationBridge.requireCompatible(
                checked, catalog, "Code").qualifiedTypeName());
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
    void callerSuppliedValueCannotProveAnUnrelatedLiteral() {
        TypeConstraintDescriptors.Catalog catalog = catalog();
        Ast.Expr actualHundred = new Ast.LiteralExpr(100L);
        IllegalArgumentException mismatch = assertThrows(
                IllegalArgumentException.class,
                () -> ConstraintValidationBridge.validateAndRecord(
                        actualHundred, catalog, "Code", 3L));
        assertTrue(mismatch.getMessage().startsWith(
                "CONSTRAINT_VALUE_EXPRESSION_MISMATCH"));
        assertThrows(IllegalStateException.class,
                () -> ConstraintValidationBridge.requireCompatible(
                        actualHundred, catalog, "Code"));

        // A correct caller-supplied value still fails the real min/max rule.
        ConstraintValidationBridge.Violation aboveMax = assertThrows(
                ConstraintValidationBridge.Violation.class,
                () -> ConstraintValidationBridge.validateAndRecord(
                        actualHundred, catalog, "Code", 100L));
        assertEquals("ORES_CONSTRAINT_MAX_INCLUSIVE_I64", aboveMax.errorId());
        assertThrows(IllegalStateException.class,
                () -> ConstraintValidationBridge.requireCompatible(
                        actualHundred, catalog, "Code"));
    }

    @Test
    void dynamicAndNonIntegerAstValuesCannotMintStaticEvidence() {
        TypeConstraintDescriptors.Catalog catalog = catalog();
        for (Ast.Expr expression : List.<Ast.Expr>of(
                new Ast.NameExpr("user_input"),
                new Ast.LiteralExpr("3"),
                new Ast.LiteralExpr(3.0d),
                new Ast.LiteralExpr(true),
                new Ast.LiteralExpr(java.math.BigInteger.valueOf(3)))) {
            assertThrows(IllegalArgumentException.class,
                    () -> ConstraintValidationBridge.validateAndRecord(
                            expression, catalog, "Code", 3),
                    "dynamic/float/string/bool/foreign-numeric input needs a "
                            + "trusted runtime checked-conversion gateway");
            assertThrows(IllegalStateException.class,
                    () -> ConstraintValidationBridge.requireCompatible(
                            expression, catalog, "Code"));
        }
    }

    @Test
    void identicalDescriptorInNewCompilationCannotReplayOldAstEvidence() {
        TypeConstraintDescriptors.Catalog oldCatalog = catalog();
        TypeConstraintDescriptors.Catalog newCatalog = catalog();
        assertNotSame(oldCatalog, newCatalog);
        assertEquals(oldCatalog.find("Code"), newCatalog.find("Code"));

        Ast.Expr checked = new Ast.LiteralExpr(3L);
        ConstraintValidationBridge.validateAndRecord(checked, oldCatalog, "Code", 3L);
        assertNotNull(ConstraintValidationBridge.requireCompatible(
                checked, oldCatalog, "Code"));
        IllegalStateException replay = assertThrows(
                IllegalStateException.class,
                () -> ConstraintValidationBridge.requireCompatible(
                        checked, newCatalog, "Code"));
        assertEquals("CONSTRAINT_PROVENANCE_CATALOG_CONTEXT_MISMATCH",
                replay.getMessage());

        IllegalStateException rebinding = assertThrows(
                IllegalStateException.class,
                () -> ConstraintValidationBridge.validateAndRecord(
                        checked, newCatalog, "Code", 3L));
        assertTrue(rebinding.getMessage().startsWith("CONSTRAINT_PROVENANCE_REBIND"));
    }

    @Test
    void evidenceHandleHasNoPublicConstructionRoute() {
        var constructors = ConstraintValidationBridge.Evidence.class
                .getDeclaredConstructors();
        assertTrue(constructors.length > 0);
        for (var constructor : constructors) {
            assertFalse(java.lang.reflect.Modifier.isPublic(constructor.getModifiers()),
                    "guest/codegen must not be able to manufacture evidence holders");
        }
    }

    @Test
    void fullSignedI64LiteralBoundsRemainExact() {
        TypeConstraintDescriptors.Catalog bounds =
                TypeChecker.checkTypesWithDiagnostics(Parser.parse("""
                        declare type I64 = int with min -9223372036854775808
                          and max 9223372036854775807;
                        """)).constraintDescriptors();
        Ast.Expr minimum = new Ast.LiteralExpr(Long.MIN_VALUE);
        Ast.Expr maximum = new Ast.LiteralExpr(Long.MAX_VALUE);
        assertEquals(Long.MIN_VALUE,
                ConstraintValidationBridge.validateAndRecord(
                        minimum, bounds, "I64", Long.MIN_VALUE).value());
        assertEquals(Long.MAX_VALUE,
                ConstraintValidationBridge.validateAndRecord(
                        maximum, bounds, "I64", Long.MAX_VALUE).value());
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
