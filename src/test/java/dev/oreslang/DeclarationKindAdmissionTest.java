package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Declarative kinds must not silently downgrade into classes, actors or functions.
 * These tests are admission-only; they do NOT establish runtime backend parity.
 */
final class DeclarationKindAdmissionTest {
    @Test
    void unloweredNominalKindsFailClosedAtRootAndModuleScope() {
        String[][] kinds = {
                // main admits first-class protected 'state container' declarations
                // (181ee5afd5); a body without a sealed state block still fails closed
                {"state container", "sealed state block"},
                {"grain", "stable keyed identity"},
                {"model", "typed ModelGraph"},
                {"enum", "nominal ADT case"}
        };
        for (String[] kind : kinds) {
            String declaration = "define " + kind[0] + " Demo as\nend\n";
            for (String source : new String[] {
                    declaration,
                    "define module demo as\n" + declaration + "end\n"
            }) {
                IllegalArgumentException error = assertThrows(
                        IllegalArgumentException.class, () -> Parser.parse(source),
                        kind[0] + " must reject");
                assertTrue(error.getMessage().contains(kind[1]),
                        () -> kind[0] + ": " + error.getMessage());
            }
        }
    }

    @Test
    void contextualNamesRemainAvailableAndActorParsingIsUnchanged() {
        assertDoesNotThrow(() -> Parser.parse("define actor Worker as\nend\n"));
        assertDoesNotThrow(() -> Parser.parse(
                "fnc grain(int state): int { return state; }\n"));
        assertDoesNotThrow(() -> Parser.parse(
                "fnc model(int container): int { return container; }\n"));
    }

    @Test
    void sourceOnlyRuntimeEntitiesCannotBecomeGenericActors() {
        for (String kind : new String[] {
                "supervisor", "scheduler", "mailman", "proxy actor"
        }) {
            IllegalArgumentException e = assertThrows(
                    IllegalArgumentException.class,
                    () -> Parser.parse("define " + kind + " Foo as\nend\n"));
            assertTrue(e.getMessage().contains("compiler-checked runtime entity"),
                    e::getMessage);
        }
    }
}
