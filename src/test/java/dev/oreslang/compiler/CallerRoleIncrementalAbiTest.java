package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Parser-only stage: executable enum lowering is intentionally still gated.
 * The linker must invalidate dependent builds when an exported role policy changes.
 */
final class CallerRoleIncrementalAbiTest {
    private static final String ROLES = """
            define enum Callers as
                A
                B
                C
            end
            """;

    private static String fingerprint(String code) {
        Ast.Program program = Parser.parse(ROLES + code);
        return IncrementalCompiler.abiDigest(program);
    }

    @Test
    void changingPublicAllowedRoleChangesIncrementalAbi() {
        String a = fingerprint("""
                @CallableBy(Callers.A)
                pub fnc guarded(): void {}
                """);
        String b = fingerprint("""
                @CallableBy(Callers.B)
                pub fnc guarded(): void {}
                """);
        String none = fingerprint("pub fnc guarded(): void {}");
        assertNotEquals(a, b);
        assertNotEquals(a, none);
        assertNotEquals(b, none);
    }

    @Test
    void changingPublicCallerRoleChangesIncrementalAbi() {
        String a = fingerprint("""
                @CallerRole(Callers.A)
                pub fnc caller(): void {}
                """);
        String b = fingerprint("""
                @CallerRole(Callers.B)
                pub fnc caller(): void {}
                """);
        assertNotEquals(a, b);
    }

    @Test
    void reorderingEquivalentRolesDoesNotCauseSpuriousInvalidation() {
        String a = fingerprint("""
                @CallableBy(Callers.A, Callers.B)
                pub fnc guarded(): void {}
                """);
        String b = fingerprint("""
                @CallableBy(Callers.B, Callers.A)
                pub fnc guarded(): void {}
                """);
        assertEquals(a, b);
    }

    @Test
    void changingPublicStaticMethodPolicyChangesClassAbi() {
        String a = fingerprint("""
                define pub class Worker as
                    @CallableBy(Callers.A)
                    pub static fnc run(): void {}
                end
                """);
        String b = fingerprint("""
                define pub class Worker as
                    @CallableBy(Callers.B)
                    pub static fnc run(): void {}
                end
                """);
        assertNotEquals(a, b);
    }

    @Test
    void implementationChangesDoNotModifyExportedPolicyAbi() {
        String first = fingerprint("""
                @CallableBy(Callers.A)
                pub fnc guarded(): int { return 1; }
                """);
        String second = fingerprint("""
                @CallableBy(Callers.A)
                pub fnc guarded(): int { return 2; }
                """);
        assertEquals(first, second);
    }
}
