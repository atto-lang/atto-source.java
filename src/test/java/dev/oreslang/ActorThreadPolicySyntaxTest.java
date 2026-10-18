package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.compiler.ActorThreadAdmission;
import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorThreads;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class ActorThreadPolicySyntaxTest {
    private static Ast.ClassDecl actor(String source) {
        var program = Parser.parse(source);
        return program.modules().stream()
                .flatMap(m -> m.declarations().stream())
                .filter(Ast.ClassDecl.class::isInstance)
                .map(Ast.ClassDecl.class::cast)
                .findFirst().orElseThrow();
    }

    @Test
    void defaultsToExclusiveExecutionInBothActorDeclarationRoutes() {
        for (String source : List.of("define actor Worker as end", "actor Worker as end")) {
            assertEquals(new Ast.ActorThreadPolicy(1, 1), actor(source).actorThreads(), source);
        }
    }

    @Test
    void nativeAndAnnotationSyntaxNormalizeIdentically() {
        String[] sources = {
                "define actor Worker from private actor with threads(min: 1, max: 1) as end",
                "@ActorThreads(min = 1, max = 1) define actor Worker from private actor as end",
                "@ActorThreads(max = 1, min = 1) actor Worker from private actor as end",
                "actor Worker from private actor with threads(max: 1, min: 1) as end"
        };
        for (String source : sources) {
            var declaration = actor(source);
            assertEquals(Ast.ActorKind.PRIVATE, declaration.actorKind(), source);
            assertEquals(new Ast.ActorThreadPolicy(1, 1), declaration.actorThreads(), source);
        }
    }

    @Test
    void explicitlyChosenKindsAreNotMistakenForDefaultKinds() {
        assertEquals(Ast.ActorKind.SHARED,
                actor("define actor Worker from shared actor with threads(min: 1, max: 1) as end").actorKind());
        assertEquals(Ast.ActorKind.UNTRUSTED,
                actor("define actor Worker from untrusted actor with threads(min: 1, max: 1) as end").actorKind());
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("define shared actor Worker from private actor as end"));
    }

    @Test
    void omittedParametersHaveExclusiveDefaults() {
        String[] sources = {
                "@ActorThreads define actor Worker as end",
                "@ActorThreads() define actor Worker as end",
                "define actor Worker with threads() as end",
                "define actor Worker with threads(max: 1) as end",
                "@ActorThreads(min = 1) define actor Worker as end"
        };
        for (String source : sources) {
            assertEquals(new Ast.ActorThreadPolicy(1, 1), actor(source).actorThreads(), source);
        }
    }

    @Test
    void invalidPolicyIsRejectedAtParseTime() {
        String[] invalid = {
                "define actor Worker with threads(min: 2, max: 1) as end",
                "define actor Worker with threads(max: 0) as end",
                "define actor Worker with threads(max: 3) as end",
                "define actor Worker with threads(max: 4) as end",
                "@ActorThreads(max = 3) define actor Worker as end",
                "@ActorThreads(min = 1, max = 1) define actor Worker with threads(min: 1, max: 1) as end",
                "@ActorThreads(min = -1) define actor Worker as end",
                "@ActorThreads(min = 0) define actor Worker as end",
                "@ActorThreads(min = 2, max = 1) define actor Worker as end",
                "@ActorThreads(min = 1, min = 1) define actor Worker as end",
                "@ActorThreads @ActorThreads define actor Worker as end",
                "define actor Worker with threads(min: 1, min: 1) as end",
                "define actor Worker with threads(unknown: 1) as end",
                "@ActorThreads(unknown = 1) define actor Worker as end",
                "@ActorThreads(1, 1) define actor Worker as end",
                "@ActorThreads(max = \"3\") define actor Worker as end",
                "@ActorThreads(max = true) define actor Worker as end",
                "define actor Worker with threads(max = 1) as end",
                "@ActorThreads(max: 1) define actor Worker as end",
                "define actor Worker with threads(max: 1.5) as end",
                "define actor Worker with threads(max: 2147483648) as end",
                "@ActorThreads(max = 2147483648) define actor Worker as end",
                "@ActorThreads(max = 1.0) define actor Worker as end",
                "@ActorThreads(max = 1 + 0) define actor Worker as end",
                "define actor Worker with threads(max: -1) as end",
                "@ActorThreads(min = 1, max = 1) define actor Worker with threads() as end"
        };
        for (String source : invalid) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void annotationMustTargetActorClass() {
        String[] invalid = {
                "@ActorThreads(max = 1) fnc work(): void { return; }",
                "@ActorThreads(max = 1) define class Worker as end",
                "@ActorThreads(max = 1) define module M as end",
                "define actor Worker as @ActorThreads(max = 1) val int n = 0; end",
                "define actor Worker as @ActorThreads(max = 1) fnc on_start(): void { return; } end",
                "define class Box as @ActorThreads(max = 1) constructor() { } end",
                "define class Box as @ActorThreads(max = 1) val int n = 0; end",
                "@ActorThreads(max = 1) val int n = 0;"
        };
        for (String source : invalid) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void forgedAstCannotBypassTypeCheckerOrCompiler() {
        var parsed = Parser.parse("define actor Worker as end");
        var module = parsed.modules().getFirst();
        var original = (Ast.ClassDecl) module.declarations().getFirst();
        var forged = new Ast.ClassDecl(original.name(), original.visibility(), original.isAbstract(),
                original.actorKind(), original.genericParameters(), original.parents(),
                original.interfaces(), original.fields(), original.constructor(), original.methods(),
                new Ast.ActorThreadPolicy(1, 3));
        var rewritten = new Ast.Program(parsed.namespace(), parsed.imports(),
                List.of(new Ast.ModuleDecl(module.name(), module.annotations(),
                        module.contracts(), List.of(forged))));
        assertThrows(IllegalArgumentException.class,
                () -> ActorThreadAdmission.requireExclusiveBackend(rewritten));
        assertThrows(IllegalArgumentException.class, () -> OresCompiler.analyze(rewritten));
        assertThrows(IllegalArgumentException.class, () -> TypeChecker.checkTypes(rewritten));
    }

    @ActorThreads(min = 1, max = 1)
    static final class ValidJavaAdapter { }
    @ActorThreads(min = 2, max = 1)
    static final class InvertedJavaAdapter { }
    @ActorThreads(min = 0, max = 1)
    static final class ZeroJavaAdapter { }
    @ActorThreads(min = 1, max = 3)
    static final class ParallelJavaAdapter { }
    static final class UnannotatedJavaAdapter { }

    @Test
    void javaMetadataMustPassRegistrationAdmissionBeforeUse() {
        var annotation = ValidJavaAdapter.class.getAnnotation(ActorThreads.class);
        assertNotNull(annotation);
        assertEquals(1, annotation.min());
        assertEquals(1, annotation.max());
        assertEquals(new Ast.ActorThreadPolicy(1, 1),
                ActorThreadAdmission.fromJavaAnnotation(ValidJavaAdapter.class));
        assertEquals(new Ast.ActorThreadPolicy(1, 1),
                ActorThreadAdmission.fromJavaAnnotation(UnannotatedJavaAdapter.class));
        assertThrows(IllegalArgumentException.class,
                () -> ActorThreadAdmission.fromJavaAnnotation(InvertedJavaAdapter.class));
        assertThrows(IllegalArgumentException.class,
                () -> ActorThreadAdmission.fromJavaAnnotation(ZeroJavaAdapter.class));
        assertThrows(IllegalArgumentException.class,
                () -> ActorThreadAdmission.fromJavaAnnotation(ParallelJavaAdapter.class));
        assertThrows(NullPointerException.class,
                () -> ActorThreadAdmission.fromJavaAnnotation(null));
    }
    @Test
    void preResolvedHostMetadataCanBeValidatedWithoutReflection() {
        assertEquals(Ast.ActorThreadPolicy.defaults(),
                ActorThreadAdmission.requireExclusiveJavaAnnotation(
                        ValidJavaAdapter.class.getAnnotation(ActorThreads.class)));
        assertEquals(Ast.ActorThreadPolicy.defaults(),
                ActorThreadAdmission.requireExclusiveJavaAnnotation(null));
        assertThrows(IllegalArgumentException.class,
                () -> ActorThreadAdmission.requireExclusiveJavaAnnotation(
                        InvertedJavaAdapter.class.getAnnotation(ActorThreads.class)));
        assertThrows(IllegalArgumentException.class,
                () -> ActorThreadAdmission.requireExclusiveJavaAnnotation(
                        ParallelJavaAdapter.class.getAnnotation(ActorThreads.class)));
    }

}
