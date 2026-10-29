package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Early fail-closed admission for the proposed actor init contract.
 * These tests exercise only the real Java parser; they are NOT a positive
 * native guest execution claim or a substitute for atomic spawn lowering.
 */
final class ActorInitReservedAdmissionTest {

    private static String actor(String member) {
        return """
                define actor Worker as
                  state {
                    int count = 0;
                  }
                """ + member + "\nend\n";
    }

    private static void rejects(String declaration) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(actor(declaration)));
        assertTrue(failure.getMessage().contains("one-shot spawn initialization"),
                failure.getMessage());
    }

    @Test
    void plainInitCannotBeRegisteredAsOrdinaryCallable() {
        rejects("init(): void { return; }");
    }

    @Test
    void namedInitWithParametersCannotBeRegisteredAsOrdinaryCallable() {
        rejects("init(int seed): void { return; }");
    }

    @Test
    void proposedCompleteStateBuilderFailsClosedUntilLoweringExists() {
        rejects("init(int seed) -> state { return state { count: seed }; }");
    }

    @Test
    void publicInitCannotLeakPrepublicationActorState() {
        rejects("pub init(): void { return; }");
    }

    @Test
    void asyncInitCannotSuspendInPrepublicationPhase() {
        rejects("async init(): void { return; }");
    }

    @Test
    void fncAndStaticMarkersDoNotBypassInitReservation() {
        rejects("fnc init(): void { return; }");
        rejects("static fnc init(): void { return; }");
    }

    @Test
    void actorConstructorRemainsForbidden() {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(actor("constructor(): void { return; }")));
        assertTrue(failure.getMessage().contains("actors do not declare constructors"),
                failure.getMessage());
    }

    @Test
    void existingActorOnStartRemainsAdmitted() {
        assertDoesNotThrow(() -> Parser.parse(actor("on_start(): void { return; }")));
    }

    @Test
    void ordinaryClassInitMethodIsNotAReservedActorHook() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class Ordinary as
                  init(): void { return; }
                end
                """));
    }
}
