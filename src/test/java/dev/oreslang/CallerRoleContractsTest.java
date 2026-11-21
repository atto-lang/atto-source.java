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
    void strippingAnnotationsFromRewrittenCallableNeverErasesAccessPolicy() {
        Ast.Program program = Parser.parse(ROLES + """
                @CallableBy(Callers.A)
                pub fnc guarded(): void {}
                @CallerRole(Callers.A)
                pub fnc authorized(): void {}
                pub fnc ordinary(): void {}
                """);
        var policy = CallerRoleContracts.validate(program);
        Ast.FunctionDecl guarded = function(program, "guarded");
        Ast.FunctionDecl authorized = function(program, "authorized");
        Ast.FunctionDecl ordinary = function(program, "ordinary");

        Ast.FunctionDecl stripped = new Ast.FunctionDecl(
                guarded.name(), guarded.kind(), guarded.visibility(),
                guarded.async(), guarded.suspends(), guarded.generator(),
                guarded.structural(), guarded.nonLexical(), guarded.trapped(),
                guarded.actorKind(), guarded.genericParameters(),
                guarded.parameters(), guarded.returnType(), java.util.List.of(),
                guarded.body(), guarded.genericSpecs());
        assertFalse(stripped.annotations().stream().anyMatch(
                a -> a.name().equals("CallableBy")));
        assertTrue(policy.isRestricted(stripped),
                "erased metadata is not a checked callable identity");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> policy.requireDirectCall(stripped, authorized, "guarded"))
                .getMessage().contains("not bound"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.rejectReification(stripped, "guarded"));

        // Legitimately unrestricted local declarations remain callable and
        // reifiable when they retain their checked AST identities.
        assertDoesNotThrow(() -> policy.requireDirectCall(
                ordinary, null, "ordinary"));
        assertDoesNotThrow(() -> policy.rejectReification(ordinary, "ordinary"));
        Ast.FunctionDecl plainCopy = new Ast.FunctionDecl(
                ordinary.name(), ordinary.kind(), ordinary.visibility(),
                ordinary.async(), ordinary.suspends(), ordinary.generator(),
                ordinary.structural(), ordinary.nonLexical(), ordinary.trapped(),
                ordinary.actorKind(), ordinary.genericParameters(),
                ordinary.parameters(), ordinary.returnType(),
                ordinary.annotations(), ordinary.body(), ordinary.genericSpecs());
        assertTrue(policy.isRestricted(plainCopy));
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireDirectCall(plainCopy, null, "ordinary"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.rejectReification(plainCopy, "ordinary"));
    }

    @Test
    void nullNamesAndForeignObjectsCannotMasqueradeAsCallableTargets() {
        Ast.Program program = Parser.parse(ROLES + """
                @CallerRole(Callers.A)
                pub fnc allowed(): void {}
                pub fnc plain(): void {}
                """);
        var index = CallerRoleContracts.validate(program);
        var sourceCaller = function(program, "allowed");
        assertThrows(IllegalArgumentException.class,
                () -> index.requireDirectCall(null, sourceCaller, "absent"));
        assertThrows(IllegalArgumentException.class,
                () -> index.requireDirectCall("plain", sourceCaller, "plain"));
        assertThrows(IllegalArgumentException.class,
                () -> index.requireDirectCall(java.util.Set.of(
                        "__root__.Callers.A"), sourceCaller, "fake"));
        assertThrows(IllegalArgumentException.class,
                () -> index.rejectReification(null, "missing"));
        assertThrows(IllegalArgumentException.class,
                () -> index.rejectReification("plain", "plain"));
        assertDoesNotThrow(() -> index.requireDirectCall(
                function(program, "plain"), sourceCaller, "plain"));
    }

    @Test
    void duplicateCallableIdentityInRewrittenProgramFailsIndexValidation() {
        Ast.Program parsed = Parser.parse(ROLES + """
                pub fnc safe(): void {}
                """);
        Ast.FunctionDecl safe = function(parsed, "safe");
        Ast.ModuleDecl root = parsed.modules().stream()
                .filter(m -> m.declarations().contains(safe)).findFirst().orElseThrow();
        Ast.ModuleDecl duplicate = new Ast.ModuleDecl(
                root.name(), root.annotations(), root.contracts(),
                java.util.List.of(safe, safe));
        Ast.Program repeated = new Ast.Program(
                parsed.namespace(), parsed.imports(), java.util.List.of(duplicate));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> CallerRoleContracts.validate(repeated)).getMessage()
                .contains("duplicate callable declaration identity"));
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
    void parserRejectsDiscardedClassLevelCallerAndCalleePolicy() {
        for (String annotation : java.util.List.of(
                "@CallerRole(Callers.A)", "@CallableBy(Callers.A)")) {
            String top = ROLES + annotation + """
                    
                    define class Worker as end
                    """;
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(top)).getMessage().contains(
                            "silently discarded"),
                    "class metadata must never disappear during parsing");
            String nested = """
                    define module RolesModule as
                        define enum Callers as A end
                    end
                    define module Example as
                    """ + annotation + """
                    
                        define pub class Worker as end
                    end
                    """;
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(nested)).getMessage().contains(
                            "silently discarded"));
        }
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
    void unindexedProtectedLinkedMethodCannotBeReifiedOrCalled() {
        Ast.Program program = Parser.parse(ROLES + """
                @CallerRole(Callers.A)
                pub fnc authorized(): void {}
                """);
        var policy = CallerRoleContracts.validate(program);
        Ast.MethodDecl external = new Ast.MethodDecl(
                "remote", Ast.Visibility.PUBLIC, true, false,
                false, false, false, null, java.util.List.of(),
                java.util.List.of(), Ast.TypeRef.simple("void"),
                java.util.List.of(new Ast.Annotation("CallableBy",
                        java.util.List.of(Ast.TypeRef.simple("Callers.A")))),
                java.util.List.of());
        assertTrue(policy.isRestricted(external));
        assertThrows(IllegalArgumentException.class,
                () -> policy.rejectReification(external, "Linked.remote"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> policy.requireDirectCall(external,
                        function(program, "authorized"), "Linked.remote"))
                .getMessage().contains("not bound"));
    }

    @Test
    void crossModuleRoleReferencesCannotSpoofAnImport() {
        assertTrue(failure("""
                define module First as
                    define enum Roles as
                        A
                    end
                end
                define module Second as
                    @CallerRole(First.Roles.A)
                    pub fnc f(): void {}
                end
                """).contains("cross-module enum roles"));
    }

    @Test
    void guardedModuleNamespaceCannotBecomeAnErasedRecordValue() {
        Ast.Program program = Parser.parse("""
                define module Locked as
                    define enum Roles as
                        A
                    end
                    @CallableBy(Roles.A)
                    pub fnc guarded(): void {}
                    @CallerRole(Roles.A)
                    pub fnc admitted(): void {}
                end
                define module Plain as
                    pub fnc helper(): void {}
                end
                """);
        var policy = CallerRoleContracts.validate(program);
        Ast.ModuleDecl locked = program.modules().stream()
                .filter(m -> m.name().equals("Locked")).findFirst().orElseThrow();
        Ast.ModuleDecl plain = program.modules().stream()
                .filter(m -> m.name().equals("Plain")).findFirst().orElseThrow();
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> policy.rejectModuleNamespaceValue(locked)).getMessage()
                .contains("lose its verified callable declaration identity"));
        assertDoesNotThrow(() -> policy.rejectModuleNamespaceValue(plain));
        Ast.FunctionDecl guarded = locked.declarations().stream()
                .filter(d -> d instanceof Ast.FunctionDecl fn && fn.name().equals("guarded"))
                .map(d -> (Ast.FunctionDecl) d).findFirst().orElseThrow();
        Ast.FunctionDecl admitted = locked.declarations().stream()
                .filter(d -> d instanceof Ast.FunctionDecl fn && fn.name().equals("admitted"))
                .map(d -> (Ast.FunctionDecl) d).findFirst().orElseThrow();
        // Disallow aliasing the entire namespace without breaking verified,
        // direct module-qualified calls.
        assertDoesNotThrow(() -> policy.requireDirectCall(guarded, admitted, "Locked.guarded"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireDirectCall(guarded, null, "Locked.guarded"));
    }

    @Test
    void deferAndUnannotatedCallbacksNeverBorrowRegisteringCallersRole() {
        Ast.Program program = Parser.parse(ROLES + """
                @CallableBy(Callers.A)
                pub fnc guarded(): void {}
                @CallerRole(Callers.A)
                pub fnc outer(): void {}
                """);
        var policy = CallerRoleContracts.validate(program);
        Ast.FunctionDecl guarded = function(program, "guarded");
        assertDoesNotThrow(() -> policy.requireDirectCall(guarded, function(program, "outer"), "guarded"));
        assertThrows(IllegalArgumentException.class,
                () -> policy.requireDirectCall(guarded, null, "guarded"));
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
