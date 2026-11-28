package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser boundary only: these cases do not claim a critical runtime,
 * and do not disable existing legacy async fnc without a migration.
 */
final class CriticalSuspendsDeclarationBoundaryTest {
    private static void reject(String source, String diagnostic) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source), source);
        assertTrue(failure.getMessage().contains(diagnostic),
                source + ": " + failure.getMessage());
    }

    @Test
    void impossibleCriticalSuspendsCombinationsNeverBecomeCallables() {
        reject("critical suspends fnc work(): void { return; }",
                "E_CALLABLE_MODIFIERS");
        reject("suspends critical fnc work(): void { return; }",
                "E_CALLABLE_MODIFIERS");
        reject("pub critical suspends fnc work(): void { return; }",
                "E_CALLABLE_MODIFIERS");
        reject("pub suspends critical fnc work(): void { return; }",
                "E_CALLABLE_MODIFIERS");
    }

    @Test
    void criticalMethodNeverFallsBackToUnsynchronizedCallable() {
        reject("critical fnc work(): void { return; }",
                "E_CRITICAL_UNSUPPORTED");
        reject("pub critical fnc work(): void { return; }",
                "E_CRITICAL_UNSUPPORTED");
        reject("define module app\n  pub critical fnc update(): void { return; }\nend",
                "E_CRITICAL_UNSUPPORTED");
    }

    @Test
    void explicitSuspendsModifierRemainsParseableWithoutImplyingRuntimeExecution() {
        assertDoesNotThrow(() -> Parser.parse(
                "pub suspends fnc work(): int { return 42; }"));
        assertDoesNotThrow(() -> Parser.parse(
                "pub fnc work(): int { return 42; }"));
    }

    @Test
    void commentsDoNotBecomeCriticalModifiers() {
        assertDoesNotThrow(() -> Parser.parse(
                "// critical suspends fnc malformed()\n" +
                "pub fnc work(): int { return 42; }"));
    }

    @Test
    void contextualCriticalMethodNameIsNotMistakenForModifier() {
        assertDoesNotThrow(() -> Parser.parse(
                "define class Worker as\n  pub critical(): int { return 42; }\nend"));
    }
}
