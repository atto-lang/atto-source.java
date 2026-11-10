package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.BorrowMirAuditV1;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the actual Parser -> TypeChecker -> OwnershipChecker path.
 * No Rust differential execution or compiled .atto guest is implied.
 */
final class BorrowMirCompilerAdmissionV1Test {
    @Test
    void straightLineTypedGuestSourceGeneratesModelOnlyMIRFromSameOwnershipPass() {
        String source = "fnc echo(int x): int { return x; }";
        assertDoesNotThrow(() -> Parser.parse(source));
        assertDoesNotThrow(() -> OresCompiler.parseAndTypeCheck(source));
        var report = OresCompiler.analyzeBorrowMirV1(source);
        assertEquals(BorrowMirAuditV1.CONTRACT_VERSION, report.version());
        assertFalse(report.completeSourceInventory());
        assertFalse(report.verifiedEmittedCode());
        assertEquals(1, report.functions().size());
        assertEquals(BorrowMirAuditV1.MODEL_ONLY, report.functions().getFirst().status());
        assertEquals(3, report.functions().getFirst().instructions().size());
    }

    @Test
    void binaryExpressionCannotMasqueradeAsCompleteBorrowMIR() {
        String source = "fnc echo(int x): int { return x + 1; }";
        assertDoesNotThrow(() -> Parser.parse(source));
        var report = OresCompiler.analyzeBorrowMirV1(source);
        assertEquals(BorrowMirAuditV1.UNSUPPORTED, report.functions().getFirst().status());
        assertTrue(report.functions().getFirst().instructions().isEmpty());
    }

    @Test
    void ownershipSemanticRejectionStillWinsOverShadowObservation() {
        String source = """
                define class Resource as
                  pub val int id = 7;
                end
                fnc bad(Resource resource): int {
                  let Resource moved = rt take resource;
                  return resource.id;
                }
                """;
        assertDoesNotThrow(() -> Parser.parse(source));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyzeBorrowMirV1(source));
        assertTrue(error.getMessage().toLowerCase().contains("moved"),
                "expected genuine ownership diagnostic: " + error.getMessage());
    }
}
