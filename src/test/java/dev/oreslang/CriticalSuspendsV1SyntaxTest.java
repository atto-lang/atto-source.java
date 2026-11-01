package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Critical/suspends v1 source-admission contract.
 *
 * These are compiler admission tests, NOT proof of critical runtime lowering.
 * A critical source is rejected until admission, scoped guard release and
 * transitive no-suspension checking are wired to the executable AST.
 */
final class CriticalSuspendsV1SyntaxTest {
    private static void rejectsWith(String source, String message) {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source), source);
        assertTrue(error.getMessage().contains(message), error::toString);
    }

    @Test void canonicalSynchronousAndSuspendingFunctionsRemainAccepted() {
        assertDoesNotThrow(() -> Parser.parse("pub fnc sync(): int { return 7; }"));
        assertDoesNotThrow(() -> Parser.parse("pub suspends fnc work(): int { return 7; }"));
        assertDoesNotThrow(() -> Parser.parse("pub suspends routine work(): int { return 7; }"));
    }

    @Test void asyncIsNotAnAliasOfSuspends() {
        rejectsWith("async fnc work(): int { return 7; }", "'async' callable modifier is removed");
        rejectsWith("pub async routine work(): int { return 7; }", "'async' callable modifier is removed");
        rejectsWith("suspends async fnc work(): int { return 7; }", "'async' callable modifier is removed");
    }

    @Test void criticalAndSuspendsCannotBothModifyACallable() {
        rejectsWith("critical suspends fnc work(): void { return; }",
                "'critical suspends' is forbidden");
        rejectsWith("suspends critical fnc work(): void { return; }",
                "'critical suspends' is forbidden");
        rejectsWith("pub critical suspends routine work(): void { return; }",
                "'critical suspends' is forbidden");
    }

    @Test void unloweredCriticalCallableNeverExecutesWithoutExclusion() {
        rejectsWith("pub critical fnc work(): void { return; }",
                "critical callable requires");
    }

    @Test void unloweredCriticalBlockIsNotAnOrdinaryBlock() {
        rejectsWith("pub suspends fnc work(): void { critical { return; } }",
                "critical block requires");
        rejectsWith("pub fnc work(): void { critical { return; } }",
                "critical block requires");
    }

    @Test void unloweredTryCriticalIsNotATryStatementOrFunctionCall() {
        rejectsWith("pub fnc work(): void { try critical { return; } else { return; } }",
                "try critical requires");
    }

    @Test void CriticalWordsInCommentsDoNotChangeExecution() {
        assertDoesNotThrow(() -> Parser.parse("""
                // async fnc and critical suspends are deprecated source spellings
                pub suspends fnc work(): int {
                    // critical { await read() }
                    return 1;
                }
                """));
    }
}
