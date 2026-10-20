package dev.oreslang;

import dev.oreslang.types.EffectContracts;
import dev.oreslang.types.SourceEffectAudit;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class SourceEffectAuditTest {
    @Test void literalFunctionIsProvablyTrivialButNotACompilerCertificate() {
        var report = SourceEffectAudit.inspect("""
                pub fnc answer(): int {
                    return 42;
                }
                """);
        assertTrue(report.unsupportedDeclarations().isEmpty());
        assertEquals(1, report.functions().size());
        assertTrue(report.conservativelyEffectFree());
        assertEquals(Set.of(), report.functions().values().iterator().next());
    }

    @Test void sameModuleDirectLookingCallIsStillUnresolvedWithoutHirProof() {
        var report = SourceEffectAudit.inspect("""
                pub fnc helper(): int {
                    return 42;
                }

                pub fnc entry(): int {
                    return helper();
                }
                """);
        assertEquals(2, report.functions().size());
        assertTrue(report.functions().values().stream()
                .anyMatch(effects -> effects.contains(EffectContracts.Effect.UNKNOWN_CALL)));
        assertFalse(report.conservativelyEffectFree());
    }

    @Test void syntacticArithmeticMustNotInventAResolvedOperatorProof() {
        var report = SourceEffectAudit.inspect("""
                pub fnc answer(): int {
                    return 40 + 2;
                }
                """);
        assertEquals(Set.of(EffectContracts.Effect.UNKNOWN_CALL),
                report.functions().values().iterator().next());
        assertFalse(report.conservativelyEffectFree());
    }

    @Test void invalidGuestSourceNeverBypassesTyping() {
        assertThrows(IllegalArgumentException.class,
                () -> SourceEffectAudit.inspect("pub fnc broken(): int { return nope; }"));
    }
}
