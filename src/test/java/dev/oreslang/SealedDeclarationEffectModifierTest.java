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

    private static void rejectsWith(String source, String expectedDiagnostic) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(failure.getMessage().contains(expectedDiagnostic),
                "expected [" + expectedDiagnostic + "] but got " + failure.getMessage());
    }

    @Test
    void classHeadersRejectSuspensionModifiers() {
        rejectsWith("define suspends final class Leaf as end", "classes accept only");
        rejectsWith("""
                define suspends sealed class Root permits Leaf as end
                define final class Leaf extends Root as end
                """, "classes accept only");
    }

    @Test
    void sealedInterfaceHeadersRejectTrapsAndSuspension() {
        rejectsWith("""
                define trap sealed interface Event permits Click as end
                define final class Click implements Event as end
                """, "interfaces/traits accept only");
        rejectsWith("""
                define suspends sealed interface Event permits Click as end
                define final class Click implements Event as end
                """, "interfaces/traits accept only");
    }

    @Test
    void actorStateFieldsRejectSuspendingOrTrappingModifiers() {
        rejectsWith("""
                define actor Worker as
                    suspends val int count = 0;
                end
                """, "actor state fields do not accept");
        rejectsWith("""
                define actor Worker as
                    trap val int count = 0;
                end
                """, "actor state fields do not accept");
    }

    @Test
    void contextualModifiersMayInterleaveWithVisibilityAndAbstract() {
        assertDoesNotThrow(() -> Parser.parse("""
                define sealed pub abstract class Root permits Leaf as end
                define pub final class Leaf from Root as end
                """));
        assertDoesNotThrow(() -> Parser.parse("""
                define pub sealed interface Event permits Click as end
                define final class Click implements Event as end
                """));
        assertDoesNotThrow(() -> Parser.parse("""
                define private sealed abstract class Root permits Leaf as end
                define final class Leaf extends Root as end
                """));
    }

    @Test
    void contextualModifierDuplicatesAreRejectedInMixedOrder() {
        rejects("define sealed pub sealed class Root as end");
        rejects("define final private final class Leaf as end");
        rejects("define final pub sealed class Root as end");
    }

    @Test
    void ordinaryVisibilityOnlyDeclarationsRejectSuspension() {
        rejectsWith("define suspends type Value = int;", "type aliases accept only");
        rejectsWith("define suspends interface Shape as end", "interfaces/traits accept only");
    }
}
