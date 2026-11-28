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

    private static String sharedFixture(String name) throws java.io.IOException {
        try (var in = ActorFlatMachineParserTest.class.getResourceAsStream(
                "/actor-machine-inspection-v1/" + name + ".atto")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test void sharedInspectionVectorsHaveCanonicalGraphOrder() throws java.io.IOException {
        String cycle = sharedFixture("flat-cycle"), order = sharedFixture("flat-order");
        var actor = Parser.inspectActorMachines(cycle).get(0);
        var graph = actor.machine();
        assertEquals(0, graph.initial());
        assertEquals("Idle", graph.nodes().get(0).name());
        assertEquals(java.util.List.of(new Parser.FlatMachineEdge("Start", 1)), graph.nodes().get(0).transitions());
        assertEquals("Running", graph.nodes().get(1).name());
        assertEquals(java.util.List.of(new Parser.FlatMachineEdge("Stop", 0)), graph.nodes().get(1).transitions());
        actor = Parser.inspectActorMachines(order).get(0);
        graph = actor.machine();
        assertTrue(actor.storageDeclared());
        assertTrue(actor.storage().isEmpty());
        assertEquals(1, graph.initial());
        assertEquals("Alpha", graph.nodes().get(0).name());
        assertTrue(graph.nodes().get(0).terminal());
        assertTrue(graph.nodes().get(0).transitions().isEmpty());
        assertEquals("Zed", graph.nodes().get(1).name());
        assertEquals(java.util.List.of(new Parser.FlatMachineEdge("AlphaEvent", 0),
                new Parser.FlatMachineEdge("Zulu", 1)), graph.nodes().get(1).transitions());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(cycle));
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(order));
    }

    @Test void sharedStorageVectorsRejectInvalidInitializers() throws java.io.IOException {
        for (String name : new String[]{"wrong-scalar", "effectful-initializer"}) {
            String src = sharedFixture(name);
            assertThrows(IllegalArgumentException.class, () -> Parser.inspectActorMachines(src));
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(src));
        }
    }

    @Test void graphTableVerifiesSlotsAndRejectsForgedMetadata() throws java.io.IOException {
        var graph = Parser.inspectActorMachines(sharedFixture("flat-order")).get(0).machine();
        var table = graph.transitionTable();
        assertEquals(1, table.initial());
        assertEquals(java.util.List.of("Alpha", "Zed"), table.names());
        assertEquals(java.util.List.of("AlphaEvent", "Zulu"), table.events());
        assertEquals(0, table.successor(1, 0).orElseThrow());
        assertEquals(1, table.successor(1, 1).orElseThrow());
        assertTrue(table.successor(0, 0).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> table.successor(2, 0));
        assertThrows(IllegalArgumentException.class, () -> table.successor(1, 2));
        var terminal = graph.nodes().get(0);
        var bad = new Parser.FlatActorMachine(1, java.util.List.of(terminal,
                new Parser.FlatMachineNode("Zed", false,
                        java.util.List.of(new Parser.FlatMachineEdge("AlphaEvent", 9)))));
        assertThrows(IllegalArgumentException.class, bad::transitionTable);
        var reversed = new Parser.FlatActorMachine(1, java.util.List.of(terminal,
                new Parser.FlatMachineNode("Zed", false,
                        java.util.List.of(new Parser.FlatMachineEdge("Zulu", 1),
                                new Parser.FlatMachineEdge("AlphaEvent", 0)))));
        assertThrows(IllegalArgumentException.class, reversed::transitionTable);
    }

    private static String nominalFixture(String name) throws java.io.IOException {
        try (var in = ActorFlatMachineParserTest.class.getResourceAsStream(
                "/actor-machine-nominal-inspection-v1/" + name + ".atto")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test void nominalEventsResolveFromSourceDeclarations() throws java.io.IOException {
        String source = nominalFixture("nominal-forward");
        var inspected = Parser.inspectNominalMachines(source);
        assertEquals(1, inspected.size());
        var machine = inspected.get(0);
        assertEquals("Foo", machine.actor());
        assertEquals(java.util.List.of("Start", "Stop"), machine.declarations());
        assertEquals(java.util.List.of("Start", "Stop"), machine.table().events());
        assertEquals(java.util.List.of(0, 1), machine.declarationIds());
        assertEquals(java.util.List.of(1, 0), machine.parsedClassIds());
        assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
    }

    @Test void nominalInspectionRejectsIneligibleDeclarations() throws java.io.IOException {
        for (String name : new String[]{"nominal-unknown", "nominal-actor", "nominal-payload", "nominal-duplicate"}) {
            String source = nominalFixture(name);
            assertThrows(IllegalArgumentException.class, () -> Parser.inspectNominalMachines(source));
            assertThrows(IllegalArgumentException.class, () -> Parser.parse(source));
        }
    }

    @Test void scalarHostCommitReference() throws InterruptedException {
        ScalarMachineCommitSmoke.main(new String[0]);
    }
}
