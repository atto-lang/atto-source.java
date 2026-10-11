package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class SupervisorActorSourceAdmissionTest {
    @Test
    void runtimeEntitySyntaxFailsClosedUntilBothCompilersCanLowerIt() {
        for (String prefix : new String[] {
                "supervisor actor", "supervisor", "mailman",
                "scheduler", "proxy actor"
        }) {
            String source = "define " + prefix + " Example as\nend\n";
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source),
                    prefix);
            assertTrue(failure.getMessage().contains("compiler-checked runtime entity"),
                    failure::getMessage);
        }
    }

    @Test
    void ordinaryActorDefinitionsRemainSupported() {
        assertDoesNotThrow(() -> Parser.parse("""
                define actor Worker as
                end
                """));
    }
}
