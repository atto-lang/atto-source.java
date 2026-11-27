package dev.oreslang;

import dev.oreslang.parser.Parser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Production parser preflight only; no executable machine claim. */
final class ActorFlatMachineParserTest {
    private static final String GOOD = """
            define actor Foo as
                state machine as
                    initial state Idle;
                    state Idle as
                        on Start() -> Running;
                    end
                    state Running as
                        on Finish() -> Complete;
                    end
                    final state Complete;
                end
            end
            """;

    private static void rejects(String source, String diagnostic) {
        var error = assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
        assertTrue(error.getMessage().contains(diagnostic), error.getMessage());
    }

    @Test void validGraphFailsClosed() {
        rejects(GOOD, "E_MACHINE_LOWERING_UNSUPPORTED");
        rejects(GOOD.replace("state machine as", "state { let int count: 0; } state machine as"),
                "E_MACHINE_LOWERING_UNSUPPORTED");
    }

    @Test void graphValidationMatchesRust() {
        String[][] variants = {
            {"initial state Idle;", ""},
            {"initial state Idle;", "initial state Idle; initial state Idle;"},
            {"initial state Idle;", "initial state Unknown;"},
            {"Start() -> Running;", "Start() -> Missing;"},
            {"Start() -> Running;", "Start() -> Running; on Start() -> Running;"},
            {"final state Complete;", "final state Complete; final state Complete;"},
            {"final state Complete;", "final state Complete; final state Orphan;"}
        };
        for (var v : variants)
            rejects(GOOD.replace(v[0], v[1]), "E_MACHINE_GRAPH");
    }

    @Test void storageAndContextualIdentifiersRemainDistinct() {
        assertDoesNotThrow(() -> Parser.parse(
                "define actor Foo as state { let int machine: 0; } end"));
    }

    @Test void edgeAndNodeBounds() {
        var source = new StringBuilder("define actor Foo as state machine as initial state Idle; state Idle as ");
        for (int i = 0; i < 257; i++) source.append("on Event").append(i).append("() -> Idle; ");
        rejects(source.append("end end end").toString(), "E_SOURCE_LIMIT");
        var nodes = new StringBuilder("define actor Foo as state machine as initial state Idle; final state Idle; ");
        for (int i = 0; i < 256; i++) nodes.append("final state Node").append(i).append("; ");
        rejects(nodes.append("end end").toString(), "E_SOURCE_LIMIT");
    }

    @Test void unsupportedSyntaxNeverBecomesAnOrdinaryActor() {
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(GOOD.replace("Start()", "Start(int)")));
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse(GOOD.replace("state machine as", "pub state machine as")));
    }

    @Test void duplicateMachineAndTrailingActorSyntaxAreChecked() {
        String machine = "state machine as initial state Idle; final state Idle; end";
        rejects("define actor Foo as " + machine + " " + machine + " end", "E_MACHINE_GRAPH");
        assertThrows(IllegalArgumentException.class,
                () -> Parser.parse("define actor Foo as " + machine));
        rejects("define actor Foo as " + machine + " state { let int count: 0; } end",
                "E_MACHINE_LOWERING_UNSUPPORTED");
    }

    @Test void inspectionRetainsGraphAndSeparateStorage() {
        String src = GOOD.replace("state machine as", "state { let int count: 0; } state machine as");
        var actor = Parser.inspectActorMachines(src).get(0);
        assertEquals("Foo", actor.actor());
        assertTrue(actor.storageDeclared());
        assertEquals("count", actor.storage().get(0).name());
        var machine = actor.machine();
        assertEquals("Idle", machine.nodes().get(machine.initial()).name());
        var edge = machine.nodes().get(machine.initial()).transitions().get(0);
        assertEquals("Start", edge.event());
        assertEquals("Running", machine.nodes().get(edge.target()).name());
        var terminal = machine.nodes().stream().filter(n -> n.name().equals("Complete")).findFirst().orElseThrow();
        assertTrue(terminal.terminal());
        assertTrue(terminal.transitions().isEmpty());
        rejects(src, "E_MACHINE_LOWERING_UNSUPPORTED");
    }

    @Test void emptyStorageInspectionCannotEnableEmission() {
        String src = "define actor Foo as state {} end";
        var actor = Parser.inspectActorMachines(src).get(0);
        assertTrue(actor.storageDeclared());
        assertTrue(actor.storage().isEmpty());
        assertNull(actor.machine());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(src));
    }

    @Test void inspectionRejectsDuplicateOrMixedStorage() {
        for (String src : new String[] {
                "define actor Foo as state {} state {} end",
                "define actor Foo as state {} let int count: 0; end",
                "define actor Foo as state { let int n: 0; let int n: 1; } end"
        }) {
            assertThrows(IllegalArgumentException.class, () -> Parser.inspectActorMachines(src));
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(src));
        }
    }
}
