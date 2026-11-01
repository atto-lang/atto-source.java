package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CriticalSuspensionAdmissionV1Test {
    private static void rejected(String src, String diagnostic) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Parser.parse(src));
        assertTrue(error.getMessage().contains(diagnostic), error.getMessage());
    }

    @Test void declarationOrdersRejectUnsoundCriticalSuspends() {
        rejected("critical suspends fnc wrong(): void { return; }", "E_CRITICAL_SUSPENDS_DECL");
        rejected("suspends critical fnc wrong(): void { return; }", "E_CRITICAL_SUSPENDS_DECL");
        rejected("pub suspends critical fnc wrong(): void { return; }", "E_CRITICAL_SUSPENDS_DECL");
        rejected("define module app\n pub critical suspends fnc wrong(): void { return; }\nend",
                "E_CRITICAL_SUSPENDS_DECL");
    }

    @Test void criticalCallableRequiresRealRuntimeLowering() {
        rejected("critical fnc run(): void { return; }", "E_CRITICAL_UNLOWERED");
        rejected("pub critical routine run(): void { return; }", "E_CRITICAL_UNLOWERED");
    }

    @Test void criticalBlockIsReservedNotMisparsedAsExpression() {
        rejected("suspends fnc run(): void { critical { return; } }", "E_CRITICAL_UNLOWERED");
        rejected("suspends fnc run(): void { critical gate { return; } }", "E_CRITICAL_UNLOWERED");
        rejected("fnc run(): void { try critical { return; } else { return; } }",
                "E_CRITICAL_UNLOWERED");
        rejected("fnc run(): void { try critical gate { return; } else { return; } }",
                "E_CRITICAL_UNLOWERED");
    }

    @Test void lexerDoesNotTreatCommentsAndStringsAsCriticalSyntax() {
        assertDoesNotThrow(() -> Parser.parse("""
                fnc critical(): int { return 7; }
                fnc run(): void {
                    // critical { await slow(); }
                    val x = "critical suspends fnc";
                    critical();
                    return;
                }
                """));
    }

    @Test void existingAsyncGeneratorAndSuspendsRemainAdmittedForMigration() {
        assertDoesNotThrow(() -> Parser.parse("""
                suspends fnc helper(): int { return 7; }
                async generator fnc numbers(): int { yield 1; return; }
                """));
    }
}
