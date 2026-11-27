package dev.oreslang;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.ActorStateBinder;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** AttoRMQ namespace actor layout parser admission, NOT guest lowering. */
final class AttoRmqBoolStateSchemaAdmissionTest {
    private static final String HEADER = """
            use namespace rmq_transport;
            declare actor ConnectionActor;
            """;

    @Test
    void boolStateInitializerIsTypedAndRemainsSealed() {
        String source = HEADER + """
                define state on actor ConnectionActor as {
                    let int frame_max : 131072;
                    let bool closing : false;
                    let bool admitted : true;
                }
                external mtd (ConnectionActor c) init(): void { return; }
                external mtd (ConnectionActor c) receive(ActorMail<int> mail): void { return; }
                """;
        var program = Parser.parse(source);
        var state = program.modules().getFirst().declarations().stream()
                .filter(d -> d instanceof Ast.ActorStateDecl)
                .map(d -> (Ast.ActorStateDecl) d)
                .findFirst().orElseThrow();
        assertEquals(3, state.fields().size());
        assertEquals("bool", state.fields().get(1).type().name());
        assertEquals(false, ((Ast.LiteralExpr) state.fields().get(1).initializer()).value());
        assertEquals(true, ((Ast.LiteralExpr) state.fields().get(2).initializer()).value());
        var bound = ActorStateBinder.bindSourceSet("atto-rmq@admission",
                Map.of("src/connection.atto", program));
        assertEquals(1, bound.size());
        var error = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(program));
        assertTrue(error.getMessage().contains("LOWERING_UNSUPPORTED"), error.getMessage());
    }

    @Test
    void wrongBooleanInitializersAndCoercionsAreRejected() {
        for (String initializer : new String[] {"0", "1", "2", "-1", "\"false\"",
                "unknown", "convert()", "1 == 1"}) {
            var source = HEADER + "define state on actor ConnectionActor as { "
                    + "let bool closing : " + initializer + "; }";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source),
                    "must reject a non-boolean state initializer: " + initializer);
        }
        for (String initializer : new String[] {"true", "false"}) {
            var source = HEADER + "define state on actor ConnectionActor as { "
                    + "let int generation : " + initializer + "; }";
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source),
                    "must reject boolean-to-integer coercion: " + initializer);
        }
    }

    @Test
    void boolStateDoesNotBypassNominalOwnershipOrVisibility() {
        var state = "define state on actor ConnectionActor as { let bool closing : true; }";
        var program = Parser.parse("use namespace rmq_transport;\n" + state);
        var error = assertThrows(IllegalArgumentException.class,
                () -> ActorStateBinder.bindSourceSet("atto-rmq@admission",
                        Map.of("src/state.atto", program)));
        assertTrue(error.getMessage().contains("has no nominal actor"), error.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(HEADER + "pub " + state));
    }
}
