package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Callable effect modifiers may not be silently dropped from declarations
 * whose AST does not represent them.
 */
final class DiscardedCallableEffectTest {
    @Test
    void constructorsCannotSilentlyEraseTrap() {
        for (String spelling : new String[] {
                "trap constructor() { }",
                "pub trap constructor() { }",
                "trap pub constructor() { }"
        }) {
            String source = "pub define class Box as " + spelling + " end";
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(source), source);
        }
    }

    @Test
    void suspendsTrapFunctionFailsClosedUntilSuspensionAwareTrapIsImplemented() {
        for (String spelling : new String[] {
                "trap suspends fnc action(): void { return; }",
                "suspends trap fnc action(): void { return; }",
                "trap async fnc action(): void { return; }"
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> Parser.parse(spelling), spelling);
        }
    }

    @Test
    void ordinaryConstructorAndSuspendableFunctionRemainAccepted() {
        assertDoesNotThrow(() -> Parser.parse(
                "pub define class Box as constructor() { } end"));
        assertDoesNotThrow(() -> Parser.parse(
                "suspends fnc action(): void { return; }"));
    }
}
