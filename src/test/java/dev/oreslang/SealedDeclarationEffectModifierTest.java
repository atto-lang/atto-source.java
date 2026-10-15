package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A rejected effect modifier must never be dropped while parsing a nominal
 * type declaration. These are fail-closed compiler admission regressions.
 */
final class SealedDeclarationEffectModifierTest {
    private static void rejects(String source) {
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(source),
                "unsupported declaration modifier must not be silently discarded");
    }

    @Test
    void classHeadersRejectSuspensionModifiers() {
        rejects("define suspends final class Leaf as end");
        rejects("""
                define suspends sealed class Root permits Leaf as end
                define final class Leaf extends Root as end
                """);
    }

    @Test
    void sealedInterfaceHeadersRejectTrapsAndSuspension() {
        rejects("""
                define trap sealed interface Event permits Click as end
                define final class Click implements Event as end
                """);
        rejects("""
                define suspends sealed interface Event permits Click as end
                define final class Click implements Event as end
                """);
    }

    @Test
    void ordinaryVisibilityOnlyDeclarationsRejectSuspension() {
        rejects("define suspends type Value = int;");
        rejects("define suspends interface Shape as end");
    }
}
