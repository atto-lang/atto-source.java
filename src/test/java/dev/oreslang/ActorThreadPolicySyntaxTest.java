package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.ActorThreads;
import org.junit.jupiter.api.Test;

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
    void defaultsToExclusiveExecution() {
        var worker = actor("define actor Worker as end");
        assertEquals(1, worker.actorThreads().min());
        assertEquals(1, worker.actorThreads().max());
    }

    @Test
    void nativeAndAnnotationSyntaxNormalizeIdentically() {
        var nativeWorker = actor("define actor Worker from private actor with threads(min: 1, max: 1) as end");
        var annotatedWorker = actor("@ActorThreads(min = 1, max = 1) define actor Worker as end");
        assertEquals(Ast.ActorKind.PRIVATE, nativeWorker.actorKind());
        assertEquals(nativeWorker.actorThreads(), annotatedWorker.actorThreads());
        assertEquals(new Ast.ActorThreadPolicy(1, 1), nativeWorker.actorThreads());
    }

    @Test
    void omittedParametersHaveExclusiveDefaults() {
        assertEquals(new Ast.ActorThreadPolicy(1, 1),
                actor("@ActorThreads define actor Worker as end").actorThreads());
        assertEquals(new Ast.ActorThreadPolicy(1, 1),
                actor("define actor Worker with threads(max: 1) as end").actorThreads());
    }

    @Test
    void invalidPolicyIsRejectedAtParseTime() {
        String[] invalid = {
                "define actor Worker with threads(min: 2, max: 1) as end",
                "define actor Worker with threads(max: 0) as end",
                "define actor Worker with threads(min: 1, max: 3) as end",
                "@ActorThreads(max = 3) define actor Worker as end",
                "@ActorThreads(min = 1, max = 1) define actor Worker with threads(min: 1, max: 1) as end",
                "@ActorThreads(min = -1) define actor Worker as end",
                "@ActorThreads(min = 1, min = 1) define actor Worker as end",
                "define actor Worker with threads(min: 1, min: 1) as end",
                "@ActorThreads(unknown = 1) define actor Worker as end",
                "@ActorThreads(max = \"3\") define actor Worker as end",
                "define actor Worker with threads(max: 2147483648) as end"
        };
        for (String source : invalid) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void annotationMustTargetActorClass() {
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("@ActorThreads(max = 1) fnc work(): void { return; }"));
        assertThrows(IllegalArgumentException.class, () ->
                Parser.parse("@ActorThreads(max = 1) define class Worker as end"));
    }

    @ActorThreads(min = 1, max = 1)
    static final class JavaActorBridgeFixture { }

    @Test
    void javaMetadataIsAvailableWithoutGrantingSchedulerAuthority() {
        var annotation = JavaActorBridgeFixture.class.getAnnotation(ActorThreads.class);
        assertNotNull(annotation);
        assertEquals(1, annotation.min());
        assertEquals(1, annotation.max());
    }
}
