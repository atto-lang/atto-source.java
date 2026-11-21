package dev.oreslang;

import dev.oreslang.types.EffectContracts;
import dev.oreslang.types.SourceEffectAudit;
import dev.oreslang.compiler.OresCompiler;
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

    @Test void checkerResolvedSameModuleFunctionPropagatesPureEffectRow() {
        var report = SourceEffectAudit.inspect("""
                pub fnc helper(): int {
                    return 42;
                }

                pub fnc entry(): int {
                    return helper();
                }
                """);
        assertEquals(2, report.functions().size());
        assertTrue(report.functions().values().stream().allMatch(Set::isEmpty));
        assertTrue(report.conservativelyEffectFree());
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

    @Test void compilerGateAcceptsOnlyTrivialVerifiedSource() {
        var admitted = OresCompiler.requireConservativelyEffectFree("""
                pub fnc answer(): int {
                    return 42;
                }
                """);
        assertTrue(admitted.conservativelyEffectFree());

        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.requireConservativelyEffectFree("""
                        pub fnc answer(): int {
                            return 40 + 2;
                        }
                        """));
    }

    @Test void compilerGateAdmitsCheckedSameModuleDirectCallOnly() {
        var report = OresCompiler.requireConservativelyEffectFree("""
                pub fnc answer(): int { return 42; }
                pub fnc run(): int { return answer(); }
                """);
        assertTrue(report.conservativelyEffectFree());
        assertEquals(2, report.functions().size());
    }

    @Test void checkerResolvedDirectCallPropagatesUnknownOperatorEffect() {
        var report = SourceEffectAudit.inspect("""
                pub fnc helper(): int { return 40 + 2; }
                pub fnc run(): int { return helper(); }
                """);
        assertEquals(2, report.functions().size());
        assertTrue(report.functions().values().stream().allMatch(
                row -> row.contains(EffectContracts.Effect.UNKNOWN_CALL)));
        assertThrows(IllegalArgumentException.class, () ->
                OresCompiler.requireConservativelyEffectFree("""
                        pub fnc helper(): int { return 40 + 2; }
                        pub fnc run(): int { return helper(); }
                        """));
    }

    @Test void emptySourceCannotForgeVacuousEffectPurity() {
        assertThrows(IllegalArgumentException.class,
                () -> SourceEffectAudit.inspect(""));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.requireConservativelyEffectFree(""));
    }

}
