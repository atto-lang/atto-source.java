package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Parser admission only: not actor guest execution or runtime wiring. */
final class ActorEmitPipeV3ParserAdmissionTest {
    private static String program(String expression) {
        return "pub routine main(): void { val result = " + expression
                + "; return; }";
    }

    private static void reject(String expression) {
        var failure = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(program(expression)));
        assertTrue(failure.getMessage().contains("actor pipe")
                        || failure.getMessage().contains("actor pipeline"),
                failure::getMessage);
        assertTrue(failure.getMessage().contains("not yet enabled")
                        || failure.getMessage().contains("lowering"),
                failure::getMessage);
    }

    @Test void keywordsAndSymbolicStagesCannotDegradeToScalarCalls() {
        reject("spawn Parse() pipe spawn Validate()");
        reject("spawn Parse() | spawn Validate()");
        reject("spawn Parse() | spawn Validate() | spawn Sink()");
        reject("spawn Parse() | (spawn Validate())");
        reject("spawn Parse() | spawn Validate() pipe spawn Sink()");
    }

    @Test void nonActorBitwiseExpressionsRetainTheirGrammar() {
        assertDoesNotThrow(() -> Parser.parse(program("1 | 2")));
        assertDoesNotThrow(() -> Parser.parse(
                "pub routine main(): void { val n = 1 | 2; return; }"));
    }
}
