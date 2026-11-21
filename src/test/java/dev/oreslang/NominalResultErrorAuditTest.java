package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.NominalResultErrorAudit;
import dev.oreslang.types.TaggedMessageContracts;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Type-only nominal error constructor audit; the runtime is still denied. */
final class NominalResultErrorAuditTest {
    private static final String ERRORS = """
            define enum TrainingError as
                INVALID_INPUT = 1
                STALE_GRAPH = 2
            end
            """;

    private static void audit(String body) {
        Ast.Program ast = Parser.parse(ERRORS + body);
        NominalResultErrorAudit.verify(ast, TaggedMessageContracts.forTypes(ast));
    }

    @Test void acceptsDirectNominalErrorAtTypeOnlyStage() {
        assertDoesNotThrow(() -> audit("""
            fnc failure(): Result<int, TrainingError> {
                return Err(TrainingError.INVALID_INPUT());
            }
            """));
    }

    @Test void rejectsStringInNominalErrorPosition() {
        assertThrows(IllegalArgumentException.class, () -> audit("""
            fnc failure(): Result<int, TrainingError> {
                return Err("INVALID_INPUT");
            }
            """));
    }

    @Test void distinguishesIdenticallyTaggedForeignEnum() {
        assertThrows(IllegalArgumentException.class, () -> audit("""
            define enum ForeignTrainingError as
                INVALID_INPUT = 1
                STALE_GRAPH = 2
            end
            fnc failure(): Result<int, TrainingError> {
                return Err(ForeignTrainingError.INVALID_INPUT());
            }
            """));
    }

    @Test void checksUnknownCasesAndPayloadArity() {
        assertThrows(IllegalArgumentException.class, () -> audit("""
            fnc failure(): Result<int, TrainingError> {
                return Err(TrainingError.UNKNOWN());
            }
            """));
        assertThrows(IllegalArgumentException.class, () -> audit("""
            fnc failure(): Result<int, TrainingError> {
                return Err(TrainingError.STALE_GRAPH(17));
            }
            """));
    }

    @Test void checksTypedResultBindingsAndBranches() {
        assertThrows(IllegalArgumentException.class, () -> audit("""
            fnc failure(): void {
                if true then
                    val Result<int, TrainingError> problem = Err("bad");
                fi
                return;
            }
            """));
    }

    @Test void payloadBearingCaseRequiresRealTypeAndOwnershipChecking() {
        String program = ERRORS + """
            define enum PayloadError as
                BAD_NUMBER: int = 10
            end
            fnc failure(): Result<int, PayloadError> {
                return Err(PayloadError.BAD_NUMBER("not a number"));
            }
            """;
        Ast.Program ast = assertDoesNotThrow(() -> Parser.parse(program));
        TaggedMessageContracts.Registry types =
                assertDoesNotThrow(() -> TaggedMessageContracts.forTypes(ast));
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> NominalResultErrorAudit.verify(ast, types));
        assertTrue(rejected.getMessage().contains(
                "unverified nominal case payload type or ownership"));
    }

    @Test void validPayloadShapeIsStillNotTypeOrOwnershipCertified() {
        String program = ERRORS + """
            define enum PayloadError as
                BAD_NUMBER: int = 10
            end
            fnc failure(): Result<int, PayloadError> {
                return Err(PayloadError.BAD_NUMBER(17));
            }
            """;
        Ast.Program ast = assertDoesNotThrow(() -> Parser.parse(program));
        TaggedMessageContracts.Registry types =
                assertDoesNotThrow(() -> TaggedMessageContracts.forTypes(ast));
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> NominalResultErrorAudit.verify(ast, types));
        assertTrue(rejected.getMessage().contains(
                "unverified nominal case payload type or ownership"));
    }

    @Test void typeOnlyAcceptanceDoesNotUnlockRuntime() {
        Ast.Program ast = Parser.parse(ERRORS + """
            fnc failure(): Result<int, TrainingError> {
                return Err(TrainingError.STALE_GRAPH());
            }
            """);
        assertDoesNotThrow(() -> NominalResultErrorAudit.verify(
                ast, TaggedMessageContracts.forTypes(ast)));
        IllegalArgumentException blocked =
                assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(ast));
        assertTrue(blocked.getMessage().contains("not yet executable"));
    }
}
