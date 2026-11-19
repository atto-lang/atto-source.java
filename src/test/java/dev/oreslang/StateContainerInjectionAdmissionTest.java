package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source injection is a compiler-owned capability, not a parse-only annotation.
 * Until typed container storage, lifecycle and borrow lowering exist, every
 * spelling must fail at admission (not become inert metadata or a parser error
 * from trying to parse nested annotation calls).
 *
 * These are negative compiler-front-end tests, NOT executable state parity.
 */
final class StateContainerInjectionAdmissionTest {
    @Test
    void unsupportedAnnotationsRejectBeforeNestedArgumentParsing() {
        String[][] candidates = {
                {"@Inject(MutableStateContainer())", "Inject"},
                {"@Inject(MutableStateContainer(scope = #instance))", "Inject"},
                {"@StateBacked(MutableStateContainer(scope = #shared))", "StateBacked"},
                {"@MutableStateContainer()", "MutableStateContainer"},
                {"@StateContainer()", "StateContainer"},
                {"@Inject", "Inject"}
        };
        for (String[] candidate : candidates) {
            String source = candidate[0] + "\ndefine class Foo as\nend\n";
            assertUnsupported(source, candidate[1]);
        }
    }

    @Test
    void unsupportedInjectionCannotHideInOtherDeclarationPositions() {
        String[] examples = {
                "@Inject(SessionState)\ndefine actor Worker as\nend\n",
                "@Inject(SessionState)\nfnc get_state(): int { return 1; }\n",
                "define module M as\n@Inject(MutableStateContainer())\n"
                        + "define class Foo as\nend\nend\n",
                "@StateBacked(MutableStateContainer())\ndefine class Foo as\n"
                        + "let int count = 0;\nend\n"
        };
        for (String source : examples) {
            assertUnsupported(source, source.contains("@StateBacked") ? "StateBacked" : "Inject");
        }
    }

    @Test
    void plainClassIsStillAdmitted() {
        assertDoesNotThrow(() -> Parser.parse("define class Foo as\nend\n"));
    }

    private static void assertUnsupported(String source, String annotation) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(source), "must reject @"+annotation);
        String message = error.getMessage();
        assertNotNull(message);
        assertTrue(message.contains("@" + annotation), message);
        assertTrue(message.contains("compile-time semantic validation"), message);
        assertTrue(message.contains("guest-AOT lowering"), message);
    }
}
