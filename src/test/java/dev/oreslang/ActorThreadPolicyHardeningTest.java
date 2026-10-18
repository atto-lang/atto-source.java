package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.ast.AnnotationExpander;
import dev.oreslang.compiler.ActorThreadAdmission;
import dev.oreslang.compiler.BuildOptions;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.compiler.TreeShaker;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Independent entrypoint checks: parser admission alone does not protect host AST APIs. */
final class ActorThreadPolicyHardeningTest {
    private static Ast.Program sourceProgram() {
        return Parser.parse("define actor Worker as end");
    }

    private static Ast.ClassDecl actor(Ast.Program program) {
        return program.modules().stream()
                .flatMap(module -> module.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst().orElseThrow();
    }

    private static Ast.Program withForgedPolicy(int min, int max) {
        var input = sourceProgram();
        var original = actor(input);
        var forged = new Ast.ClassDecl(
                original.name(), original.visibility(), original.isAbstract(),
                original.actorKind(), original.genericParameters(), original.parents(),
                original.interfaces(), original.fields(), original.constructor(),
                original.methods(), new Ast.ActorThreadPolicy(min, max));
        List<Ast.ModuleDecl> modules = new ArrayList<>();
        for (Ast.ModuleDecl module : input.modules()) {
            modules.add(new Ast.ModuleDecl(module.name(), module.annotations(),
                    module.contracts(), List.of(forged)));
        }
        return new Ast.Program(input.namespace(), input.imports(), modules);
    }

    @Test
    void forgedParallelMetadataIsRejectedAtEveryPublicCompilerBoundary() {
        Ast.Program forged = withForgedPolicy(1, 3);
        assertThrows(IllegalArgumentException.class,
                () -> ActorThreadAdmission.requireExclusiveBackend(forged));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyze(forged));
        assertThrows(IllegalArgumentException.class,
                () -> OresCompiler.analyzeWithDiagnostics(forged));
        assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.checkTypes(forged));
        assertThrows(IllegalArgumentException.class,
                () -> TreeShaker.shake(forged, BuildOptions.library(java.util.Map.of())));
    }

    @Test
    void actorThreadMetadataIsPreservedThroughAnnotationExpansion() {
        Ast.Program forged = withForgedPolicy(1, 3);
        var expanded = AnnotationExpander.expand(forged);
        assertEquals(new Ast.ActorThreadPolicy(1, 3), actor(expanded).actorThreads());
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.analyze(expanded));
    }

    @Test
    void defaultSerialPolicySurvivesCompilerAndTreeShaker() {
        var admitted = OresCompiler.analyze(sourceProgram());
        assertEquals(Ast.ActorThreadPolicy.defaults(), actor(admitted).actorThreads());
        // Keep the private actor explicitly rooted; library mode retains only public API.
        var result = TreeShaker.shake(admitted,
                new BuildOptions(java.util.Map.of(), java.util.Set.of("Worker"), false));
        assertEquals(Ast.ActorThreadPolicy.defaults(), actor(result.program()).actorThreads());
    }

    @Test
    void maliciousMinCannotBypassThroughDirectAstConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new Ast.ActorThreadPolicy(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new Ast.ActorThreadPolicy(2, 1));
        assertThrows(IllegalArgumentException.class, () -> new Ast.ActorThreadPolicy(1, 4));
        assertThrows(IllegalArgumentException.class,
                () -> ActorThreadAdmission.requireExclusiveBackend(withForgedPolicy(2, 2)));
        assertDoesNotThrow(() ->
                ActorThreadAdmission.requireExclusiveBackend(withForgedPolicy(1, 1)));
    }

    @Test
    void sourceAnnotationCannotBeSilentlyDiscardedOnOtherDeclarations() {
        String[] bad = {
                "@ActorThreads(max = 1) define module M as end",
                "@ActorThreads(max = 1) define class C as end",
                "@ActorThreads(max = 1) fnc work(): void { return; }",
                "define class C as @ActorThreads(max = 1) constructor() {} end",
                "define class C as @ActorThreads(max = 1) fnc work(): void { return; } end",
                "define class C as @ActorThreads(max = 1) let int value = 0; end",
                "define actor Worker as @ActorThreads(max = 1) fnc work(): void { return; } end",
                "define actor Worker as @ActorThreads(max = 1) let int value = 0; end"
        };
        for (String example : bad) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(example), example);
        }
    }

    @Test
    void malformedAndConflictingActorPolicyMatrix() {
        String[] bad = {
                "@ActorThreads(min = 1, max = 3) define actor Worker as end",
                "@ActorThreads(min = 3, max = 1) define actor Worker as end",
                "@ActorThreads(min = 0) define actor Worker as end",
                "@ActorThreads(max = 2147483648) define actor Worker as end",
                "@ActorThreads(max = false) define actor Worker as end",
                "@ActorThreads(max = 1, unknown = 1) define actor Worker as end",
                "@ActorThreads(max = 1) @ActorThreads(max = 1) define actor Worker as end",
                "@ActorThreads(max = 1) define actor Worker with threads(max: 1) as end",
                "define actor Worker with threads(max: 3) as end",
                "define actor Worker with threads(min: 2, max: 2) as end",
                "define actor Worker with threads(min: 0) as end",
                "define actor Worker with threads(max: 2147483648) as end",
                "define actor Worker with threads(foo: 1) as end",
                "define actor Worker with threads(min: 1, min: 1) as end",
                "define actor Worker with threads(max: 1, max: 1) as end",
                "define actor Worker with threads(min: true) as end",
                "define actor Worker with threads(min = 1) as end"
        };
        for (String example : bad) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(example), example);
        }
    }

    @Test
    void kindClauseAndAnnotationPolicyAreIndependent() {
        var annotated = actor(Parser.parse(
                "@ActorThreads(min = 1, max = 1) define actor Worker from private actor as end"));
        assertEquals(Ast.ActorKind.PRIVATE, annotated.actorKind());
        assertEquals(Ast.ActorThreadPolicy.defaults(), annotated.actorThreads());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(
                "define shared actor Worker from private actor with threads(max: 1) as end"));
    }
}
