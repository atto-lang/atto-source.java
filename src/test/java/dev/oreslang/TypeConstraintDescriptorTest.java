package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.TypeConstraintDescriptors;
import dev.oreslang.compiler.IncrementalCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
    void unconstrainedAliasesRemainBackwardCompatible() {
        var checked = TypeChecker.checkTypesWithDiagnostics(Parser.parse("""
                declare type Code = int;
                fnc read(Code code): int { return code; }
                """));
        assertTrue(checked.constraintDescriptors().descriptors().isEmpty());
    }
}
