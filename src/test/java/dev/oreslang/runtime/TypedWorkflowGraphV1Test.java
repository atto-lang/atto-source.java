package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class TypedWorkflowGraphV1Test {

    @Test
    void canonicalDevLoopIsTypedReachableAndBounded() {
        var graph = DevLoopWorkflowDefinitionV1.create(5);

        assertEquals("goal", graph.startNode());
        assertEquals(10, graph.nodes().size());
        assertTrue(graph.edges().stream().anyMatch(edge ->
                edge.fromNode().equals("bug-report")
                        && edge.toNode().equals("code")
                        && edge.maxTraversals() == 5));
    }

    @Test
    void typeMismatchFailsClosed() {
        var nodes = List.of(
                node("start", TypedWorkflowGraphV1.NodeKind.TRIGGER,
                        "in", Map.of("go", "type-a")),
                node("done", TypedWorkflowGraphV1.NodeKind.TERMINAL,
                        "type-b", Map.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new TypedWorkflowGraphV1(
                        "start",
                        nodes,
                        List.of(new TypedWorkflowGraphV1.Edge(
                                "start", "go", "done", null))));
    }

    @Test
    void unboundedCycleIsRejected() {
        var nodes = List.of(
                node("start", TypedWorkflowGraphV1.NodeKind.TRIGGER,
                        "a", Map.of("go", "a")),
                node("loop-a", TypedWorkflowGraphV1.NodeKind.ACTIVITY,
                        "a", Map.of("next", "a")),
                node("loop-b", TypedWorkflowGraphV1.NodeKind.ACTIVITY,
                        "a", Map.of(
                                "again", "a",
                                "done", "end")),
                node("done", TypedWorkflowGraphV1.NodeKind.TERMINAL,
                        "end", Map.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new TypedWorkflowGraphV1(
                        "start",
                        nodes,
                        List.of(
                                edge("start", "go", "loop-a", null),
                                edge("loop-a", "next", "loop-b", null),
                                edge("loop-b", "again", "loop-a", null),
                                edge("loop-b", "done", "done", null))));
    }

    @Test
    void boundedCycleIsAccepted() {
        var nodes = List.of(
                node("start", TypedWorkflowGraphV1.NodeKind.TRIGGER,
                        "a", Map.of("go", "a")),
                node("loop-a", TypedWorkflowGraphV1.NodeKind.ACTIVITY,
                        "a", Map.of("next", "a")),
                node("loop-b", TypedWorkflowGraphV1.NodeKind.ACTIVITY,
                        "a", Map.of(
                                "again", "a",
                                "done", "end")),
                node("done", TypedWorkflowGraphV1.NodeKind.TERMINAL,
                        "end", Map.of()));

        var graph = new TypedWorkflowGraphV1(
                "start",
                nodes,
                List.of(
                        edge("start", "go", "loop-a", null),
                        edge("loop-a", "next", "loop-b", null),
                        edge("loop-b", "again", "loop-a", 3),
                        edge("loop-b", "done", "done", null)));
        assertEquals(4, graph.nodes().size());
    }

    @Test
    void oneOutcomeCannotFanOutToTwoTransitions() {
        var nodes = List.of(
                node("start", TypedWorkflowGraphV1.NodeKind.TRIGGER,
                        "a", Map.of("go", "end")),
                node("one", TypedWorkflowGraphV1.NodeKind.TERMINAL,
                        "end", Map.of()),
                node("two", TypedWorkflowGraphV1.NodeKind.TERMINAL,
                        "end", Map.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new TypedWorkflowGraphV1(
                        "start",
                        nodes,
                        List.of(
                                edge("start", "go", "one", null),
                                edge("start", "go", "two", null))));
    }

    @Test
    void unreachableNodesAreRejected() {
        var nodes = List.of(
                node("start", TypedWorkflowGraphV1.NodeKind.TRIGGER,
                        "a", Map.of("go", "end")),
                node("done", TypedWorkflowGraphV1.NodeKind.TERMINAL,
                        "end", Map.of()),
                node("orphan", TypedWorkflowGraphV1.NodeKind.TERMINAL,
                        "unused", Map.of()));

        assertThrows(
                IllegalArgumentException.class,
                () -> new TypedWorkflowGraphV1(
                        "start",
                        nodes,
                        List.of(edge(
                                "start", "go", "done", null))));
    }

    @Test
    void boundedEdgeElsewhereInSccDoesNotHideUnboundedSubcycle() {
        var nodes = java.util.List.of(
                node(
                        "start",
                        TypedWorkflowGraphV1.NodeKind.TRIGGER,
                        "input",
                        java.util.Map.of("go", "x")),
                node(
                        "a",
                        TypedWorkflowGraphV1.NodeKind.ACTIVITY,
                        "x",
                        java.util.Map.of("to_b", "x")),
                node(
                        "b",
                        TypedWorkflowGraphV1.NodeKind.ACTIVITY,
                        "x",
                        java.util.Map.of(
                                "to_a", "x",
                                "to_c", "x")),
                node(
                        "c",
                        TypedWorkflowGraphV1.NodeKind.ACTIVITY,
                        "x",
                        java.util.Map.of(
                                "to_b", "x",
                                "done", "x")),
                node(
                        "done",
                        TypedWorkflowGraphV1.NodeKind.TERMINAL,
                        "x",
                        java.util.Map.of()));

        var edges = java.util.List.of(
                new TypedWorkflowGraphV1.Edge(
                        "start", "go", "a", null),
                new TypedWorkflowGraphV1.Edge(
                        "a", "to_b", "b", 3),
                new TypedWorkflowGraphV1.Edge(
                        "b", "to_a", "a", null),
                new TypedWorkflowGraphV1.Edge(
                        "b", "to_c", "c", null),
                new TypedWorkflowGraphV1.Edge(
                        "c", "to_b", "b", null),
                new TypedWorkflowGraphV1.Edge(
                        "c", "done", "done", null));

        var error = assertThrows(
                IllegalArgumentException.class,
                () -> new TypedWorkflowGraphV1(
                        "start", nodes, edges));
        assertTrue(error.getMessage().contains(
                "entirely of unbounded edges"));
    }

    private static TypedWorkflowGraphV1.Node node(
            String id,
            TypedWorkflowGraphV1.NodeKind kind,
            String input,
            Map<String, String> outcomes) {
        return new TypedWorkflowGraphV1.Node(
                id, kind, input, outcomes);
    }

    private static TypedWorkflowGraphV1.Edge edge(
            String from,
            String outcome,
            String to,
            Integer max) {
        return new TypedWorkflowGraphV1.Edge(
                from, outcome, to, max);
    }
}
