package dev.oreslang;

import dev.oreslang.compiler.CompilerTypedEffectV1;
import dev.oreslang.compiler.CompilerTypedEffectV1SelfTest;
import dev.oreslang.compiler.OresCompiler;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real checked compiler frontdoor and independent typed-AST inference regressions.
 * Static source/model tests only: no Graal guest or native image proof.
 */
final class CompilerTypedEffectV1Test {
    @Test
    void checkedScalarAndTransitiveHelperHaveNoAmbientEffects() {
        String source = "fnc helper(): int { return 42; } "
                + "pub fnc main(): int { return helper(); }";
        for (var profile : CompilerTypedEffectV1.Profile.values()) {
            var report = OresCompiler.analyzeEffectProfileV1(source, profile);
            assertFalse(report.runtimeCertified());
            assertEquals(2, report.rows().size());
            assertTrue(report.rows().stream().allMatch(
                    row -> row.inferredEffects().isEmpty()));
        }
    }

    @Test
    void checkedArithmeticDoesNotBecomeAFalseNoThrowClaim() {
        var report = OresCompiler.analyzeEffectProfileV1(
                "pub fnc main(): int { return 1 + 2; }",
                CompilerTypedEffectV1.Profile.NO_ALLOC);
        assertTrue(report.rows().stream().anyMatch(
                row -> row.inferredEffects().contains("panic")));
        assertFalse(report.runtimeCertified());
    }

    @Test
    void syntheticTypedAstNegativeAndCycleCases() {
        // Exercises the exact compiler class in a dedicated standalone test.
        // Its synthetic programs are not substitutes for checked guest fixtures.
        assertDoesNotThrow(() -> CompilerTypedEffectV1SelfTest.main(new String[0]));
    }
}
