package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actor.pipe / Actor.connect are reserved typed graph intrinsics, not ordinary
 * guest dynamic calls. These gates are not executable factory implementations.
 */
final class ActorPipelineFactoryAdmissionTest {
    private static String program(String expression) {
        return """
                pub routine main(): void {
                    val candidate = %s;
                    return;
                }
                """.formatted(expression);
    }

    private static void reject(String expression, String operation) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> Parser.parse(program(expression)));
        assertTrue(failure.getMessage().contains("Actor." + operation),
                failure::getMessage);
        assertTrue(failure.getMessage().contains("not yet enabled"),
                failure::getMessage);
    }

    @Test void constructorFactoryMustNotFallThroughAsDynamicCall() {
        reject("Actor.pipe(Reader(), Transformer(), Writer())", "pipe");
    }

    @Test void zeroArgTypeFactoryMustNotTreatTypesAsRuntimeValues() {
        reject("Actor.pipe(Reader, Transformer, Writer)", "pipe");
    }

    @Test void connectingRunningActorRefsRequiresTypedAdmission() {
        reject("Actor.connect(reader_ref, writer_ref)", "connect");
    }

    @Test void namespaceColonAndGenericCallSpellingsCannotBypassTheGate() {
        reject("Actor::pipe(Reader(), Writer())", "pipe");
        reject("Actor.pipe<int>(Reader(), Writer())", "pipe");
        reject("Actor.connect<int>(reader_ref, writer_ref)", "connect");
    }

    @Test void unrelatedObjectPipeAndConnectMethodsRemainOrdinaryCalls() {
        assertDoesNotThrow(() -> Parser.parse(program("pipe_target.pipe(1)")));
        assertDoesNotThrow(() -> Parser.parse(program("pipe_target.connect(2)")));
        assertDoesNotThrow(() -> Parser.parse(program("Other.pipe(1)")));
        assertDoesNotThrow(() -> Parser.parse(program("actor.gc()")));
    }
}
