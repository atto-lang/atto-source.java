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
    void publicEnumCaseAndTagChangesInvalidateSourceAbi() {
        String baseline = IncrementalCompiler.abiDigest(Parser.parse("""
                define pub enum Roles as
                    A = 1
                    B = 3
                end
                """));
        String changedTag = IncrementalCompiler.abiDigest(Parser.parse("""
                define pub enum Roles as
                    A = 1
                    B = 4
                end
                """));
        String changedVariant = IncrementalCompiler.abiDigest(Parser.parse("""
                define pub enum Roles as
                    A = 1
                    C = 3
                end
                """));
        String changedPayload = IncrementalCompiler.abiDigest(Parser.parse("""
                define pub enum Roles as
                    A = 1
                    B: int = 3
                end
                """));
        assertNotEquals(baseline, changedTag);
        assertNotEquals(baseline, changedVariant);
        assertNotEquals(baseline, changedPayload);
    }

    @Test
    void privateRoleEnumSchemaChangesInvalidatePublicCallablePolicyAbi() {
        String baseline = IncrementalCompiler.abiDigest(Parser.parse("""
                define enum Roles as
                    A = 1
                end
                @CallableBy(Roles.A)
                pub fnc protectedFn(): void {}
                """));
        String retagged = IncrementalCompiler.abiDigest(Parser.parse("""
                define enum Roles as
                    A = 7
                end
                @CallableBy(Roles.A)
                pub fnc protectedFn(): void {}
                """));
        assertNotEquals(baseline, retagged);
    }

    @Test
    void privateEnumWithoutExportedCallerPolicyDoesNotAlterPublicAbi() {
        String baseline = IncrementalCompiler.abiDigest(Parser.parse("""
                define enum Internal as
                    A = 1
                end
                pub fnc ordinary(): void {}
                """));
        String modified = IncrementalCompiler.abiDigest(Parser.parse("""
                define enum Internal as
                    A = 7
                end
                pub fnc ordinary(): void {}
                """));
        assertEquals(baseline, modified);
    }

    @Test
    void unrelatedPrivateEnumDoesNotInvalidateExportedRolePolicy() {
        String a = IncrementalCompiler.abiDigest(Parser.parse("""
                define enum Roles as A = 1 end
                define enum Unrelated as B = 1 end
                @CallableBy(Roles.A)
                pub fnc guarded(): void {}
                """));
        String b = IncrementalCompiler.abiDigest(Parser.parse("""
                define enum Roles as A = 1 end
                define enum Unrelated as B = 2 end
                @CallableBy(Roles.A)
                pub fnc guarded(): void {}
                """));
        assertEquals(a, b);
    }

    @Test
    void referencedPrivateEnumRetaggingInvalidatesPublicStaticMethodAbi() {
        String a = IncrementalCompiler.abiDigest(Parser.parse("""
                define module Gates as
                    define enum Roles as Enter = 1 end
                    define pub class Door as
                        @CallableBy(Roles.Enter)
                        pub static fnc open(): void {}
                    end
                end
                """));
        String b = IncrementalCompiler.abiDigest(Parser.parse("""
                define module Gates as
                    define enum Roles as Enter = 5 end
                    define pub class Door as
                        @CallableBy(Roles.Enter)
                        pub static fnc open(): void {}
                    end
                end
                """));
        assertNotEquals(a, b);
    }

    @Test
    void referencedPrivateEnumRetaggingInvalidatesPublicCallerGrantAbi() {
        String a = IncrementalCompiler.abiDigest(Parser.parse("""
                define enum Roles as A = 1 end
                @CallerRole(Roles.A)
                pub fnc caller(): void {}
                """));
        String b = IncrementalCompiler.abiDigest(Parser.parse("""
                define enum Roles as A = 4 end
                @CallerRole(Roles.A)
                pub fnc caller(): void {}
                """));
        assertNotEquals(a, b);
    }

    @Test
    void changingGuardedPrivateParentPolicyInvalidatesPublicSubclassAbi() {
        String a = IncrementalCompiler.abiDigest(Parser.parse("""
                define enum Roles as A, B end
                define class Base as
                    @CallableBy(Roles.A)
                    pub static fnc guarded(): void {}
                end
                define pub class Child extends Base as end
                """));
        String b = IncrementalCompiler.abiDigest(Parser.parse("""
                define enum Roles as A, B end
                define class Base as
                    @CallableBy(Roles.B)
                    pub static fnc guarded(): void {}
                end
                define pub class Child extends Base as end
                """));
        assertNotEquals(a, b);
    }

    @Test
    void retaggingPrivateRoleEnumOnInheritedGuardInvalidatesAbi() {
        String a = IncrementalCompiler.abiDigest(Parser.parse("""
                define enum Roles as A = 1 end
                define class Base as
                    @CallableBy(Roles.A)
                    pub static fnc guarded(): void {}
                end
                define pub class Child extends Base as end
                """));
        String b = IncrementalCompiler.abiDigest(Parser.parse("""
                define enum Roles as A = 5 end
                define class Base as
                    @CallableBy(Roles.A)
                    pub static fnc guarded(): void {}
                end
                define pub class Child extends Base as end
                """));
        assertNotEquals(a, b);
    }

    @Test
    void addingGuardToPreviouslyUnguardedPrivateParentInvalidatesExportedSubclass() {
        String a = IncrementalCompiler.abiDigest(Parser.parse("""
                define enum Roles as A end
                define class Base as
                    pub static fnc inherited(): void {}
                end
                define pub class Child extends Base as end
                """));
        String b = IncrementalCompiler.abiDigest(Parser.parse("""
                define enum Roles as A end
                define class Base as
                    @CallableBy(Roles.A)
                    pub static fnc inherited(): void {}
                end
                define pub class Child extends Base as end
                """));
        assertNotEquals(a, b);
    }

    @Test
    void crossModulePrivateParentGuardChangeInvalidatesPublicSubclassAbi() {
        String a = IncrementalCompiler.abiDigest(Parser.parse("""
                define module Guards as
                    define enum Roles as A, B end
                    define class Base as
                        @CallableBy(Roles.A)
                        pub static fnc guarded(): void {}
                    end
                end
                define module Api as
                    define pub class Child extends Guards.Base as end
                end
                """));
        String b = IncrementalCompiler.abiDigest(Parser.parse("""
                define module Guards as
                    define enum Roles as A, B end
                    define class Base as
                        @CallableBy(Roles.B)
                        pub static fnc guarded(): void {}
                    end
                end
                define module Api as
                    define pub class Child extends Guards.Base as end
                end
                """));
        assertNotEquals(a, b,
                "public subclasses must depend on cross-module private-parent guards");
    }

    @Test
    void crossModulePrivateParentRoleRetaggingInvalidatesPublicAbi() {
        String a = IncrementalCompiler.abiDigest(Parser.parse("""
                define module Guards as
                    define enum Roles as A = 1 end
                    define class Base as
                        @CallableBy(Roles.A)
                        pub static fnc guarded(): void {}
                    end
                end
                define module Api as
                    define pub class Child extends Guards.Base as end
                end
                """));
        String b = IncrementalCompiler.abiDigest(Parser.parse("""
                define module Guards as
                    define enum Roles as A = 9 end
                    define class Base as
                        @CallableBy(Roles.A)
                        pub static fnc guarded(): void {}
                    end
                end
                define module Api as
                    define pub class Child extends Guards.Base as end
                end
                """));
        assertNotEquals(a, b,
                "private role enum schema must be reachable across module-qualified ancestry");
    }

    @Test
    void unrelatedCrossModulePrivateClassChangesDoNotInvalidatePublicAbi() {
        String a = IncrementalCompiler.abiDigest(Parser.parse("""
                define module Internal as
                    define class Unused as
                        pub static fnc method(): int { return 1; }
                    end
                end
                define module Api as
                    define pub class Child as end
                end
                """));
        String b = IncrementalCompiler.abiDigest(Parser.parse("""
                define module Internal as
                    define class Unused as
                        pub static fnc method(): int { return 2; }
                    end
                end
                define module Api as
                    define pub class Child as end
                end
                """));
        assertEquals(a, b,
                "ABI fingerprinting must not include unreachable private implementation details");
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
