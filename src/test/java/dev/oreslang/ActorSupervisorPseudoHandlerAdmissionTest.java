package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class ActorSupervisorPseudoHandlerAdmissionTest {
    @Test
    void actorUnhandledFailureHandlersAreRejectedUntilCompilerCheckedDispatchExists() {
        for (String name : new String[] {
                "on_throw", "on_throws", "on_raise", "on_recover"
        }) {
            String source = "define actor Worker as\n"
                    + "  pub " + name + "(): void { return; }\n"
                    + "end\n";
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), name);
            assertTrue(error.getMessage().contains("compiler-enforced exhaustiveness"),
                    () -> name + ": " + error.getMessage());
        }
    }

    @Test
    void actorPanicCannotEverBeHandledLocally() {
        for (String declaration : new String[] {
                "on_panic(): void { return; }",
                "pub on_panic(): void { return; }",
                "@Implements\n  on_panic(): void { return; }"
        }) {
            String source = "define actor Worker as\n  " + declaration + "\nend\n";
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), declaration);
            assertTrue(error.getMessage().contains("supervisor CONTROL"),
                    () -> declaration + ": " + error.getMessage());
        }
    }

    @Test
    void teardownIsReservedRatherThanSilentlyCompiledAsOrdinaryActorMethod() {
        for (String declaration : new String[] {
                "tear_down(): void { return; }",
                "pub tear_down(): void { return; }",
                "@Implements\n  tear_down(): void { return; }"
        }) {
            String source = "define actor Worker as\n  " + declaration + "\nend\n";
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source), declaration);
            assertTrue(error.getMessage().contains("actor tear_down lifecycle hook"),
                    () -> declaration + ": " + error.getMessage());
        }
    }

    @Test
    void ordinaryNonActorMethodsAndActorMethodsAreUnaffected() {
        assertDoesNotThrow(() -> Parser.parse("""
                define class Example as
                  pub on_panic(): void { return; }
                  pub tear_down(): void { return; }
                end
                define actor Worker as
                  pub run(): void { return; }
                end
                """));
    }
}
