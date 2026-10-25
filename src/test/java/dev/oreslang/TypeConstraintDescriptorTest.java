package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.TypeConstraintDescriptors;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

final class TypeConstraintDescriptorTest {
    @Test
    void parsesAndCanonicalizesInclusiveBounds() {
        Ast.Program program = Parser.parse("""
                declare type Code = int with max 5 and min 0;
                """);
        Ast.TypeAliasDecl alias = assertInstanceOf(
                Ast.TypeAliasDecl.class,
                program.modules().getFirst().declarations().getFirst());
        assertTrue(alias.constrained());
        assertEquals(2, alias.constraints().size());
        assertEquals(Ast.TypeConstraintKind.MIN_INCLUSIVE,
                alias.constraints().get(0).kind());
        assertEquals(0L, alias.constraints().get(0).value());
        assertEquals(Ast.TypeConstraintKind.MAX_INCLUSIVE,
                alias.constraints().get(1).kind());
        assertEquals(5L, alias.constraints().get(1).value());
    }

    @Test
    void checkedTypeResultExposesStableZordeConsumableDescriptor() {
        var checked = TypeChecker.checkTypesWithDiagnostics(Parser.parse("""
                declare type Code = int with min 0 and max 5;
                """));
        TypeConstraintDescriptors.Descriptor descriptor =
                checked.constraintDescriptors().find("Code");
        assertNotNull(descriptor);
        assertEquals(1, descriptor.schemaVersion());
        assertEquals("ores.type-constraint.v1", descriptor.schemaId());
        assertEquals("Code", descriptor.qualifiedTypeName());
        assertEquals("i64", descriptor.representation());
        assertTrue(descriptor.validationRequired());
        assertEquals(2, descriptor.predicates().size());
        assertEquals("min_inclusive_i64", descriptor.predicates().get(0).id());
        assertEquals(">=", descriptor.predicates().get(0).operator());
        assertEquals(0L, descriptor.predicates().get(0).operand());
        assertEquals("ORES_CONSTRAINT_MIN_INCLUSIVE_I64",
                descriptor.predicates().get(0).errorId());
        assertEquals("max_inclusive_i64", descriptor.predicates().get(1).id());
        assertEquals("<=", descriptor.predicates().get(1).operator());
        assertEquals(5L, descriptor.predicates().get(1).operand());
        assertEquals("ORES_CONSTRAINT_MAX_INCLUSIVE_I64",
                descriptor.predicates().get(1).errorId());
        assertEquals(
                "ff6740c2be58b79a926282c2b51690ee5810fdc3f61d5038730719c04a9a7280",
                descriptor.fingerprint());
    }

    @Test
    void declareAndDefineProduceIdenticalConstraintDescriptors() {
        TypeConstraintDescriptors.Descriptor declared =
                TypeChecker.checkTypesWithDiagnostics(Parser.parse("""
                        declare type Code = int with max 5 and min 0;
                        """)).constraintDescriptors().find("Code");
        TypeConstraintDescriptors.Descriptor defined =
                TypeChecker.checkTypesWithDiagnostics(Parser.parse("""
                        define type Code = int with min 0 and max 5;
                        """)).constraintDescriptors().find("Code");
        assertEquals(declared, defined);
    }

    @Test
    void moduleDescriptorUsesStableQualifiedName() {
        var checked = TypeChecker.checkTypesWithDiagnostics(Parser.parse("""
                define module api as
                  declare type Code = int with min -2 and max 5;
                end
                """));
        TypeConstraintDescriptors.Descriptor descriptor =
                checked.constraintDescriptors().find("api.Code");
        assertNotNull(descriptor);
        assertEquals(-2L, descriptor.predicates().getFirst().operand());
    }

    @Test
    void explicitNamespaceParticipatesInQualifiedDescriptorIdentity() {
        var checked = TypeChecker.checkTypesWithDiagnostics(Parser.parse("""
                namespace acme;
                define module api as
                  declare type Code = int with min 0 and max 5;
                end
                """));
        TypeConstraintDescriptors.Descriptor descriptor =
                checked.constraintDescriptors().find("acme.api.Code");
        assertNotNull(descriptor);
        assertEquals("acme.api.Code", descriptor.qualifiedTypeName());
        assertNull(checked.constraintDescriptors().find("api.Code"));
    }

    @Test
    void rejectsMalformedDuplicateEmptyAndOverflowingRanges() {
        for (String source : new String[]{
                "declare type Code = int with min 2 and min 3;",
                "declare type Code = int with max 2 and max 3;",
                "declare type Code = int with min 6 and max 5;",
                "declare type Code = int with min 9223372036854775808;",
                "declare type Code = int with min -9223372036854775809;",
                "declare type Code = int with min 1 and;"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
        assertDoesNotThrow(() -> Parser.parse(
                "declare type Code = int with min -9223372036854775808 and max 9223372036854775807;"));
    }

    @Test
    void descriptorV1RejectsGenericAndNonIntegerConstraintRepresentations() {
        for (String source : new String[]{
                "declare type Flag = bool with min 0;",
                "declare type Box<T> = int with min 0;",
                "declare type Text = string with max 5;"
        }) {
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class,
                    () -> TypeChecker.checkTypesWithDiagnostics(Parser.parse(source)),
                    source);
            assertTrue(error.getMessage().startsWith("CONSTRAINT_"), error::getMessage);
        }
    }

    @Test
    void publicConstraintDomainParticipatesInIncrementalAbi() {
        String declared = new IncrementalCompiler()
                .compile(Map.of("types.ores",
                        "declare pub type Code = int with max 5 and min 0;"))
                .units().get("types.ores").abiDigest();
        String defined = new IncrementalCompiler()
                .compile(Map.of("types.ores",
                        "define pub type Code = int with min 0 and max 5;"))
                .units().get("types.ores").abiDigest();
        String widened = new IncrementalCompiler()
                .compile(Map.of("types.ores",
                        "declare pub type Code = int with min 0 and max 6;"))
                .units().get("types.ores").abiDigest();

        assertEquals(declared, defined,
                "source introducer and predicate order must not alter public ABI");
        assertNotEquals(declared, widened,
                "changing the accepted constraint domain must invalidate public ABI");
    }

    @Test
    void runtimeValueUseFailsClosedUntilValidationBridgeExists() {
        IllegalArgumentException parameter = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        declare type Code = int with min 0 and max 5;
                        fnc read(Code code): int { return code; }
                        """)));
        assertTrue(parameter.getMessage().contains("CONSTRAINT_VALIDATION_BRIDGE_REQUIRED"),
                parameter::getMessage);

        IllegalArgumentException extension = assertThrows(
                IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parse("""
                        declare type Code = int with min 0 and max 5;
                        define mtd double on Code(): int { return self + self; }
                        """)));
        assertTrue(extension.getMessage().contains("CONSTRAINT_VALIDATION_BRIDGE_REQUIRED"),
                extension::getMessage);
    }

    @Test
    void sharedGoldenContractMatchesCompilerDescriptors() {
        Properties golden = goldenContract();
        assertEquals("ores.alias-extension-constraint.v1", golden.getProperty("schema"));
        assertEquals(TypeConstraintDescriptors.SCHEMA_ID,
                golden.getProperty("constraint.schema"));
        assertEquals(TypeConstraintDescriptors.REPRESENTATION_I64,
                golden.getProperty("constraint.representation"));
        assertEquals("true",
                golden.getProperty("constraint.validation_required"));

        record Case(String source, String qualifiedName, String key) { }
        for (Case item : new Case[]{
                new Case(
                        "declare type Code = int with min 0 and max 5;",
                        "Code", "root.Code"),
                new Case(
                        "define module api as declare type Code = int with min -2 and max 5; end",
                        "api.Code", "api.Code"),
                new Case(
                        "namespace acme; define module api as declare type Code = int with min 0 and max 5; end",
                        "acme.api.Code", "acme.api.Code")
        }) {
            TypeConstraintDescriptors.Descriptor descriptor =
                    TypeChecker.checkTypesWithDiagnostics(Parser.parse(item.source()))
                            .constraintDescriptors().find(item.qualifiedName());
            assertNotNull(descriptor, item.qualifiedName());
            assertTrue(descriptor.validationRequired(), item.qualifiedName());
            assertEquals(
                    golden.getProperty("constraint." + item.key() + ".canonical"),
                    TypeConstraintDescriptors.canonicalForm(
                            descriptor.qualifiedTypeName(), descriptor.predicates()),
                    item.qualifiedName());
            assertEquals(
                    golden.getProperty("constraint." + item.key() + ".fingerprint"),
                    descriptor.fingerprint(),
                    item.qualifiedName());
        }
    }

    @Test
    void unconstrainedAliasesRemainBackwardCompatible() {
        var checked = TypeChecker.checkTypesWithDiagnostics(Parser.parse("""
                declare type Code = int;
                fnc read(Code code): int { return code; }
                """));
        assertTrue(checked.constraintDescriptors().descriptors().isEmpty());
    }

    private static Properties goldenContract() {
        Properties result = new Properties();
        Path path = Path.of(
                "contracts", "v1", "type-alias-extension-constraint-v1.properties");
        try (var reader = Files.newBufferedReader(path)) {
            result.load(reader);
        } catch (IOException error) {
            throw new AssertionError("cannot load shared alias/extension golden contract", error);
        }
        return result;
    }
}
