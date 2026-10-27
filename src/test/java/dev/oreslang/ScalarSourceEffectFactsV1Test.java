package dev.oreslang;

import dev.oreslang.types.EffectContracts.Effect;
import dev.oreslang.types.ScalarSourceEffectFactsV1;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ScalarSourceEffectFactsV1Test {
    private static ScalarSourceEffectFactsV1.Report analyze(String source) {
        return ScalarSourceEffectFactsV1.fromSource(source);
    }

    @Test
    void actualCheckedSourceDirectCallChainHasClosedEffectSummary() {
        var report = analyze("""
                fnc leaf(): int { return 42; }
                fnc helper(): int { return leaf(); }
                fnc main(): int { return helper(); }
                """);
        assertEquals(java.util.Set.of(), report.effectsOf("main"));
        assertDoesNotThrow(() -> report.requireNoEffects("main"));
        assertThrows(IllegalArgumentException.class, () -> report.effectsOf("not_declared"));
    }

    @Test
    void observedAllocationFromRealSourcePropagatesThroughCallers() {
        var report = analyze("""
                fnc leaf(): string { return "allocated"; }
                fnc entry(): string { return leaf(); }
                """);
        assertTrue(report.effectsOf("entry").contains(Effect.ALLOC));
        assertThrows(IllegalArgumentException.class, () -> report.requireNoEffects("entry"));
    }

    @Test
    void actualAsyncAndAwaitAreSuspendingThroughTypedSourceCalls() {
        var report = analyze("""
                async fnc leaf(): int { return 42; }
                async fnc outer(): int { return await leaf(); }
                """);
        assertTrue(report.effectsOf("leaf").contains(Effect.SUSPEND));
        assertTrue(report.effectsOf("outer").contains(Effect.SUSPEND));
        assertThrows(IllegalArgumentException.class, () -> report.requireNoEffects("outer"));
    }

    @Test
    void expressionFormsWithoutVerifiedTypedLoweringFailClosed() {
        var report = analyze("""
                fnc scalar_arithmetic(): int { return 1 + 2; }
                fnc wrapper(): int { return scalar_arithmetic(); }
                """);
        assertTrue(report.effectsOf("wrapper").contains(Effect.UNKNOWN_CALL),
                "Unproven operator overload/typed dispatch cannot silently be pure");
        assertThrows(IllegalArgumentException.class, () -> report.requireNoEffects("wrapper"));
    }

    @Test
    void runtimeBindingAndUnknownCallShapesAreNeverTrustedProofs() {
        var report = analyze("""
                fnc with_binding(): int {
                  const int value = 17;
                  return value;
                }
                """);
        assertTrue(report.effectsOf("with_binding").contains(Effect.UNKNOWN_CALL));
    }

    @Test
    void rejectionIsFrontendOwnedRatherThanFromUserSuppliedEdges() {
        assertThrows(IllegalArgumentException.class, () -> analyze("""
                fnc root(): int { return missing_callable(); }
                """));
        assertThrows(IllegalArgumentException.class, () -> analyze("""
                fnc unbound(): int { return unknown_name; }
                """));
        assertThrows(IllegalArgumentException.class, () -> analyze("x".repeat(1_048_577)));
    }

    @Test
    void syntacticCallChainsDoNotProveTerminationOrHardRealtime() {
        // Effect closure is independent of termination: this zero-direct-effect
        // recursive program would still fail hard-RT recursion/WCET checking.
        var report = analyze("""
                fnc recur(): int { return recur(); }
                """);
        assertEquals(java.util.Set.of(), report.effectsOf("recur"));
    }
}
