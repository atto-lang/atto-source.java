package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Effects may not disappear from the AST when a declaration cannot represent them.
 * This is essential for generated SDK/client contracts and async ownership safety.
 */
final class SuspendsDeclarationLeakTest {
    @Test
    void interfaceAndContractMethodsMustNotSilentlyEraseSuspends() {
        for (String source : new String[] {
                "pub define contract ClientContract as suspends fnc fetch(String request) => String; end",
                "define interface Api as suspends fnc fetch(String request) => String; end",
                "pub define contract ClientContract as trap fnc fetch(String request) => String; end"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void nonCallableDeclarationsMustNotLoseSuspensionEffects() {
        for (String source : new String[] {
                "suspends define module Demo as end",
                "suspends define class Box as end",
                "suspends pub define contract ClientContract as fnc fetch(String req) => String; end",
                "define class Box as suspends val int number = 0; end"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source), source);
        }
    }

    @Test
    void actualSuspendingCallableRemainsLegal() {
        assertDoesNotThrow(() -> Parser.parse(
                "suspends fnc resume(): void { return; }"));
    }
}
