package dev.oreslang;

import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ExtensibleActorDeclarationTest {
    private static final String HEADER = "define keyword durable_object as actor end\n";
    private static final String KIND = "define durable_object Counter as ";
    private static final String ORDINARY = "define actor Counter as ";

    @Test void ordinaryActorAstIsIdentical() {
        assertEquals(Parser.parseStrictAtto(ORDINARY + "end"),
                Parser.parseStrictAtto(HEADER + KIND + "end"));
        assertEquals(Parser.parseStrictAtto("define pub actor Counter as end"),
                Parser.parseStrictAtto(HEADER + "define pub durable_object Counter as end"));
    }

    @Test void sealedStateIsNotConvertedToOrdinaryFields() {
        String body = "state { let int total = 0; } end";
        assertEquals(Parser.parseStrictAtto(ORDINARY + body),
                Parser.parseStrictAtto(HEADER + KIND + body));
    }

    @Test void markerRequirementUsesOrdinaryActorInterface() {
        String marker = "define interface DurableMarker as end\n";
        String descriptor = "define keyword durable_object as actor requires DurableMarker; end\n";
        var ordinary = Parser.parseStrictAtto(marker + "define actor Counter implements DurableMarker as end");
        var custom = Parser.parseStrictAtto(descriptor + marker + KIND + "end");
        assertEquals(ordinary, custom);
        // Same-file marker interfaces are also subject to the existing actor
        // source-unit isolation rule. Do not claim executable marker support.
        var expected = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(ordinary));
        var actual = assertThrows(IllegalArgumentException.class, () -> TypeChecker.check(custom));
        assertEquals(expected.getMessage(), actual.getMessage());
        assertTrue(actual.getMessage().contains("cannot use same-file interface"), actual::getMessage);
    }

    @Test void callableInterfaceCannotBypassMailboxAdmission() {
        String iface = "define interface DurableApi as fnc fetch(): int; end\n";
        String ordinary = iface + "define actor Counter implements DurableApi as pub fnc fetch(): int { return 42; } end";
        String custom = "define keyword durable_object as actor requires DurableApi; end\n" +
                iface + KIND + "pub fnc fetch(): int { return 42; } end";
        var expected = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parseStrictAtto(ordinary)));
        var actual = assertThrows(IllegalArgumentException.class,
                () -> TypeChecker.check(Parser.parseStrictAtto(custom)));
        assertEquals(expected.getMessage(), actual.getMessage());
        // Existing same-file interface admission denies this before callable shape checks.
        assertTrue(actual.getMessage().contains("cannot use same-file interface"), actual::getMessage);
    }

    @Test void forgedStateAuthorityAndMalformedStateFailLikeOrdinaryActors() {
        for (String body : new String[] {
                "handle_state(): void { return; } end",
                "state { let int total = true; } end",
                "state { let int total = 0; let int total = 1; } end"
        }) {
            var expected = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parseStrictAtto(ORDINARY + body));
            var actual = assertThrows(IllegalArgumentException.class,
                    () -> Parser.parseStrictAtto(HEADER + KIND + body));
            assertTrue(!expected.getMessage().isBlank() && !actual.getMessage().isBlank());
        }
    }

    @Test void standaloneAndReservedTransportHeadsStayUnsupported() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parseStrictAtto(HEADER + "define durable_object Counter as receiver;"));
        for (String reserved : new String[]{"serial", "http", "tcp", "websocket", "ws", "_"}) {
            var e = assertThrows(IllegalArgumentException.class, () ->
                    Parser.parseStrictAtto("define keyword " + reserved + " as actor end\ndefine class Control as end"));
            assertTrue(e.getMessage().contains("KIND_RESERVED"), e::getMessage);
        }
    }
}
