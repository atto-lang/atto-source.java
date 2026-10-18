package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.CallerRoleContracts;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class CallerRoleContractsTest {
    private static final String ROLES = """
            define enum Callers as
                A
                B
                C
            end
            """;

    private static Ast.FunctionDecl function(Ast.Program program, String name) {
        return program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(d -> d instanceof Ast.FunctionDecl fn && fn.name().equals(name))
                .map(d -> (Ast.FunctionDecl) d).findFirst().orElseThrow();
    }

    private static String failure(String source) {
        return assertThrows(IllegalArgumentException.class,
                () -> CallerRoleContracts.validate(Parser.parse(source))).getMessage();
    }

    @Test
    void nominalRoleIdentityAllowsOnlyMatchingAnnotatedCaller() {
        Ast.Program program = Parser.parse(ROLES + """
                @CallableBy(Callers.A, Callers.B)
                pub fnc sink(): void { }
                @CallerRole(Callers.A)
                pub fnc a(): void { sink(); }
                @CallerRole(Callers.B)
                pub routine b(): void { sink(); }
                @CallerRole(Callers.C)
                pub fnc c(): void { sink(); }
                pub fnc none(): void { sink(); }
                """);
        CallerRoleContracts.Index policy = CallerRoleContracts.validate(program);
        Ast.FunctionDecl sink = function(program, "sink");
        assertTrue(policy.isRestricted(sink));
        policy.requireDirectCall(sink, function(program, "a"), "sink");
        policy.requireDirectCall(sink, function(program, "b"), "sink");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> policy.requireDirectCall(sink,
                        function(program, "c"), "sink")).getMessage()
                .contains("@CallableBy denies"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireDirectCall(sink,
                        function(program, "none"), "sink"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.rejectReification(sink, "sink"));
        assertDoesNotThrow(() -> policy.rejectReification(function(program, "a"), "a"));
    }

    @Test
    void arbitraryRoleSetsCannotImpersonateLexicallyAnnotatedCallers() {
        Ast.Program program = Parser.parse(ROLES + """
                @CallableBy(Callers.A)
                pub fnc target(): void {}
                @CallerRole(Callers.A)
                pub fnc real(): void {}
                pub fnc fake(): void {}
                """);
        var policy = CallerRoleContracts.validate(program);
        var target = function(program, "target");
        var real = function(program, "real");
        var fake = function(program, "fake");
        assertDoesNotThrow(() -> policy.requireDirectCall(target, real, "target"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireDirectCall(target, fake, "target"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireDirectCall(target, null, "target"));
        // This is intentionally no longer an authorization API accepting strings.
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireDirectCall(target, java.util.Set.of("__root__.Callers.A"), "target"));
    }

    @Test
    void policyCannotBeSilentlyDroppedWhenTargetIsReplacedAfterIndexing() {
        Ast.Program program = Parser.parse(ROLES + """
                @CallableBy(Callers.A)
                pub fnc target(): void {}
                @CallerRole(Callers.A)
                pub fnc real(): void {}
                """);
        var policy = CallerRoleContracts.validate(program);
        Ast.FunctionDecl original = function(program, "target");
        Ast.FunctionDecl copy = new Ast.FunctionDecl(
                original.name(), original.kind(), original.visibility(), original.async(),
                original.suspends(), original.generator(), original.structural(),
                original.nonLexical(), original.trapped(), original.actorKind(),
                original.genericParameters(), original.parameters(), original.returnType(),
                original.annotations(), original.body(), original.genericSpecs());
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireDirectCall(copy, function(program, "real"), "target"));
    }

    @Test
    void memberNamesInDifferentEnumsDoNotGrantEqualRoles() {
        Ast.Program program = Parser.parse("""
                define enum Left as
                    A
                end
                define enum Right as
                    A
                end
                @CallableBy(Left.A)
                pub fnc sink(): void { }
                @CallerRole(Right.A)
                pub fnc wrong(): void { }
                @CallerRole(Left.A)
                pub fnc right(): void { }
                """);
        var policy = CallerRoleContracts.validate(program);
        var sink = function(program, "sink");
        assertThrows(IllegalArgumentException.class, () -> policy.requireDirectCall(
                sink, function(program, "wrong"), "sink"));
        assertDoesNotThrow(() -> policy.requireDirectCall(
                sink, function(program, "right"), "sink"));
    }

    @Test
    void validatesUnknownMalformedAndDuplicateRoleMarkers() {
        assertTrue(failure(ROLES + "@CallerRole(Callers.Missing) fnc f(): void {}").contains("unknown case"));
        assertTrue(failure(ROLES + "@CallerRole(Missing.A) fnc f(): void {}").contains("unknown or unlinked enum"));
        assertTrue(failure(ROLES + "@CallerRole(A) fnc f(): void {}").contains("requires Enum.Case"));
        assertTrue(failure(ROLES + "@CallableBy() fnc f(): void {}").contains("requires 1..64"));
        assertTrue(failure(ROLES + "@CallableBy(Callers.A, Callers.A) fnc f(): void {}").contains("duplicate role"));
        assertTrue(failure(ROLES + "@CallerRole(Callers.A) @CallerRole(Callers.B) fnc f(): void {}").contains("duplicate @CallerRole"));
        assertTrue(failure(ROLES + "@CallableBy(Callers.A) @CallableBy(Callers.B) fnc f(): void {}").contains("duplicate @CallableBy"));
        assertTrue(failure(ROLES + "@CallerRole(\"Callers.A\") fnc f(): void {}").contains("requires Enum.Case"));
    }

    @Test
    void payloadBearingEnumCasesAreNotRoles() {
        assertTrue(failure("""
                define enum Ops as
                    A: int
                    B
                end
                @CallerRole(Ops.A) fnc f(): void {}
                """).contains("payload-free"));
    }

    @Test
    void rejectsCallRoleAnnotationsOutsideCallableDeclarations() {
        assertTrue(failure(ROLES + """
                @CallerRole(Callers.A)
                define module demo as
                end
                """).contains("requires a function"));
        assertTrue(failure(ROLES + """
                @CallableBy(Callers.A)
                val int x = 1;
                """).contains("requires a function"));
    }

    @Test
    void roleAnnotatedMethodMayCallProtectedStaticFunction() {
        Ast.Program program = Parser.parse(ROLES + """
                define class Worker as
                    @CallerRole(Callers.A)
                    pub work(): void {}
                    @CallableBy(Callers.A)
                    pub static fnc run(): void {}
                end
                """);
        var policy = CallerRoleContracts.validate(program);
        Ast.ClassDecl klass = program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(d -> d instanceof Ast.ClassDecl)
                .map(d -> (Ast.ClassDecl) d).findFirst().orElseThrow();
        var work = klass.methods().stream().filter(m -> m.name().equals("work")).findFirst().orElseThrow();
        var run = klass.methods().stream().filter(m -> m.name().equals("run")).findFirst().orElseThrow();
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireDirectCall(run, null, "run"));
        assertDoesNotThrow(() -> policy.requireDirectCall(run, work, "run"));
    }

    @Test
    void failClosedOnPotentialVirtualOverridePolicyBypass() {
        assertTrue(failure(ROLES + """
                define class Worker as
                    @CallableBy(Callers.A)
                    pub run(): void {}
                end
                """).contains("sealed virtual dispatch"));
    }

    @Test
    void protectedHostEntryPointIsRejected() {
        assertTrue(failure(ROLES + """
                @CallableBy(Callers.A)
                pub fnc main(): void {}
                """).contains("host/runtime entry point"));
    }

    @Test
    void executableCompilerIngressStillFailsClosedUntilEnumLoweringIsReady() {
        Ast.Program program = Parser.parse(ROLES + """
                @CallableBy(Callers.A)
                pub fnc guarded(): void {}
                """);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(program)).getMessage().contains("syntax-only"));
    }
}
