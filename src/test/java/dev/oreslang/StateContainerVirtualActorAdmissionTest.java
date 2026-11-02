package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class StateContainerVirtualActorAdmissionTest {
    @Test
    void reservedTwoWordDeclarationsFailClosedAtFileRoot() {
        assertReserved("define state container Counter as\nend\n",
                "state container", "protected-heap ownership");
        assertReserved("define virtual actor Cart as\nend\n",
                "virtual actor", "activation fencing");
    }

    @Test
    void reservedDeclarationsFailClosedWithinModuleAndAfterVisibility() {
        assertReserved("""
                define module sample as
                  define pub state container Counter as
                  end
                end
                """, "state container", "protected-heap ownership");
        assertReserved("""
                define module sample as
                  define pub virtual actor Cart as
                  end
                end
                """, "virtual actor", "activation fencing");
    }

    @Test
    void oldActorSyntaxIsNotRewrittenAndGrainDoesNotAliasVirtualActor() {
        assertDoesNotThrow(() -> Parser.parse("define actor Worker as\nend\n"));
        IllegalArgumentException wrongAlias = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define grain ShoppingCart as\nend\n"));
        assertFalse(wrongAlias.getMessage().contains("activation fencing"),
                wrongAlias::getMessage);
    }

    @Test
    void otherIdentifierSpellingsCannotBypassFailClosedGate() {
        assertReserved("define virtual /* trivia */ actor Cart as\nend\n",
                "virtual actor", "activation fencing");
        assertReserved("define state /* trivia */ container Counter as\nend\n",
                "state container", "protected-heap ownership");
    }

    private static void assertReserved(String source, String kind, String contract) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source), kind);
        assertTrue(error.getMessage().contains(kind), error::getMessage);
        assertTrue(error.getMessage().contains(contract), error::getMessage);
        assertTrue(error.getMessage().contains("not yet enabled"), error::getMessage);
    }
}
