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
        String source = """
                fnc leaf(): int { return 42; }
                fnc helper(): int { return leaf(); }
                fnc main(): int { return helper(); }
                """;
        var report = analyze(source);
        assertEquals(java.util.Set.of(), report.effectsOf("main"));
        assertDoesNotThrow(() -> report.requireNoEffects("main", source));
        assertThrows(IllegalArgumentException.class, () -> report.requireNoEffects("main", source + " "));
        assertEquals(0, ScalarSourceEffectFactsV1.Report.class.getConstructors().length,
                "untrusted code must not mint a source-authorized Report");
        assertThrows(UnsupportedOperationException.class, () -> report.closure().clear());
        assertThrows(IllegalArgumentException.class, () -> report.effectsOf("not_declared"));
    }

    @Test
    void observedAllocationFromRealSourcePropagatesThroughCallers() {
        String source = """
                fnc leaf(): string { return "allocated"; }
                fnc entry(): string { return leaf(); }
                """;
        var report = analyze(source);
        assertTrue(report.effectsOf("entry").contains(Effect.ALLOC));
        assertThrows(IllegalArgumentException.class, () -> report.requireNoEffects("entry", source));
    }

    @Test
    void actualAsyncAndAwaitAreSuspendingThroughTypedSourceCalls() {
        String source = """
                async fnc leaf(): int { return 42; }
                async fnc outer(): int { return await leaf(); }
                """;
        var report = analyze(source);
        assertTrue(report.effectsOf("leaf").contains(Effect.SUSPEND));
        assertTrue(report.effectsOf("outer").contains(Effect.SUSPEND));
        assertThrows(IllegalArgumentException.class, () -> report.requireNoEffects("outer", source));
    }

    @Test
    void expressionFormsWithoutVerifiedTypedLoweringFailClosed() {
        String source = """
                fnc scalar_arithmetic(): int { return 1 + 2; }
                fnc wrapper(): int { return scalar_arithmetic(); }
                """;
        var report = analyze(source);
        assertTrue(report.effectsOf("wrapper").contains(Effect.UNKNOWN_CALL),
                "Unproven operator overload/typed dispatch cannot silently be pure");
        assertThrows(IllegalArgumentException.class, () -> report.requireNoEffects("wrapper", source));
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
    void namespaceMustNotBeMistakenForUnqualifiedRootCallIdentity() {
        assertThrows(IllegalArgumentException.class, () -> analyze("""
                namespace workload;
                fnc leaf(): int { return 42; }
                fnc caller(): int { return leaf(); }
                """));
    }

    @Test
    void procedureCallableKindMustNotMasqueradeAsPureFunctionWitness() {
        assertThrows(IllegalArgumentException.class, () -> analyze("""
                routine worker(): void { return; }
                fnc answer(): int { return 42; }
                """));
    }

    @Test
    void nonRootModuleMustNotContributeUnqualifiedCallIdentities() {
        assertThrows(IllegalArgumentException.class, () -> analyze("""
                define module handlers
                  fnc leaf(): int { return 42; }
                end
                """));
    }

    @Test
    void unprovenReturnRepresentationsAreRejectedBySourceEffectAdmission() {
        // A scalar literal does not establish the machine representation of
        // every return type, including floating-point and dynamic boxing.
        var rejected = assertThrows(IllegalArgumentException.class, () -> analyze("""
                fnc floating(): float { return 3.5; }
                """));
        assertTrue(rejected.getMessage().contains("unsupported callable"),
                "Must fail the source effect admission, not accidentally the parser");
        assertThrows(IllegalArgumentException.class, () -> analyze("""
                fnc boxed(): any { return 42; }
                """));
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
