package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actor-local physical carrier cap (not ActorGroup.maxParallelTurns).
 * Until per-turn ownership is compiled/lowered, LOW is the only executable
 * setting; HIGH and MEDIUM must never be treated as a best-effort hint.
 */
final class ActorMaxThreadsAnnotationTest {
    private static Ast.ClassDecl actor(String source) {
        var module = Parser.parse(source).modules().getFirst();
        return (Ast.ClassDecl) module.declarations().getFirst();
    }

    @Test void defaultAndLowRemainSingleFlight() {
        assertEquals(Ast.MaxActorThreads.LOW,
                actor("define actor Ordinary as end").maxActorThreads());
        assertEquals(1, actor("define actor Ordinary as end").maxActorThreads().value());
        assertEquals(Ast.MaxActorThreads.LOW,
                actor("@ActorMaxThreads(MaxActorThreads.LOW) define actor Explicit as end")
                        .maxActorThreads());
    }

    @Test void parallelActorRequestsFailClosedUntilRuntimeIsSafe() {
        for (String level : new String[] {"MEDIUM", "HIGH"}) {
            var failure = assertThrows(RuntimeException.class, () ->
                    Parser.parse("@ActorMaxThreads(MaxActorThreads." + level
                            + ") define actor Hungry as end"));
            assertTrue(failure.getMessage().contains("per-turn actor state isolation"),
                    failure.getMessage());
        }
    }

    @Test void rejectMalformedAndNonActorAnnotations() {
        for (String source : new String[] {
                "@ActorMaxThreads(3) define actor Hungry as end",
                "@ActorMaxThreads(MaxActorThreads.EXTREME) define actor Hungry as end",
                "@ActorMaxThreads() define actor Hungry as end",
                "@ActorMaxThreads(MaxActorThreads.LOW, MaxActorThreads.LOW) define actor Hungry as end",
                "@ActorMaxThreads(MaxActorThreads.LOW) @ActorMaxThreads(MaxActorThreads.LOW) define actor Hungry as end",
                "@ActorMaxThreads(MaxActorThreads.LOW) define class Ordinary as end"
        }) {
            assertThrows(RuntimeException.class, () -> Parser.parse(source), source);
        }
    }
}
