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
    void sourcePipeActorDeclarationsAndOperatorCannotSilentlyExecute() {
        for (String role : new String[] {"readable", "transform", "writable", "duplex"}) {
            String source = "define " + role + " pipe actor Foo as\nend\n";
            IllegalArgumentException error = assertThrows(
                    IllegalArgumentException.class, () -> Parser.parse(source));
            assertTrue(error.getMessage().contains("compiler-checked pipe role typing"),
                    error::getMessage);
        }
        String chain = """
                pub fnc connect_actors(): ActorStream {
                    return spawn A1() pipe spawn A2() pipe spawn A3();
                }
                """;
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class, () -> Parser.parse(chain));
        assertTrue(error.getMessage().contains("actor pipe expressions require compiler-checked"),
                error::getMessage);
    }

    @Test
    void ordinaryActorDefinitionsRemainSupported() {
        assertDoesNotThrow(() -> Parser.parse("""
                define actor Worker as
                end
                """));
    }
}
