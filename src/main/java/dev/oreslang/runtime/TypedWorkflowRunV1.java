package dev.oreslang.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic execution cursor for a validated {@link TypedWorkflowGraphV1}.
 *
 * <p>This v1 cursor intentionally models one active control token. Parallel
 * fan-out/join is a later graph primitive; the initial engine proves typed
 * transitions, bounded cycles, deterministic revisions and restorable traversal
 * budgets for workflows such as the AI development loop.</p>
 */
public final class TypedWorkflowRunV1 {

    public static final class TraversalBudgetExceededException
            extends IllegalStateException {
        public TraversalBudgetExceededException(String message) {
            super(message);
        }
    }

    public record Snapshot(
            String currentNode,
            long revision,
            Map<String, Integer> traversalCounts) {
        public Snapshot {
            currentNode = requireText(currentNode, "currentNode");
            if (revision < 0) {
                throw new IllegalArgumentException(
                        "revision cannot be negative");
            }
            traversalCounts = Map.copyOf(
                    Objects.requireNonNull(
                            traversalCounts, "traversalCounts"));
        }
    }

    public record Transition(
            long revision,
            String fromNode,
            String outcome,
            String payloadType,
            String toNode,
            int traversalCount,
            Integer maxTraversals,
            boolean terminal) { }

    private final TypedWorkflowGraphV1 graph;
    private final LinkedHashMap<String, Integer> traversalCounts =
            new LinkedHashMap<>();

    private String currentNode;
    private long revision;

    public TypedWorkflowRunV1(TypedWorkflowGraphV1 graph) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.currentNode = graph.startNode();
    }

    public static TypedWorkflowRunV1 restore(
            TypedWorkflowGraphV1 graph,
            Snapshot snapshot) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!graph.nodes().containsKey(snapshot.currentNode())) {
            throw new IllegalStateException(
                    "persisted workflow cursor references missing node "
                            + snapshot.currentNode());
        }

        TypedWorkflowRunV1 run = new TypedWorkflowRunV1(graph);
        long traversals = 0L;
        for (var entry : snapshot.traversalCounts().entrySet()) {
            String key = entry.getKey();
            Integer count = entry.getValue();
            if (count == null || count < 0) {
                throw new IllegalStateException(
                        "persisted traversal count must be non-negative");
            }
            TypedWorkflowGraphV1.Edge edge =
                    run.edgeByKey(key);
            if (edge == null) {
                throw new IllegalStateException(
                        "persisted traversal references unknown edge "
                                + key);
            }
            if (edge.maxTraversals() != null
                    && count > edge.maxTraversals()) {
                throw new IllegalStateException(
                        "persisted traversal exceeds edge budget "
                                + key);
            }
            if (count > 0) {
                run.traversalCounts.put(key, count);
            }
            traversals = Math.addExact(traversals, count.longValue());
        }
        if (snapshot.revision() != traversals) {
            throw new IllegalStateException(
                    "persisted workflow revision does not equal "
                            + "total edge traversals");
        }

        validateSingleTokenFlow(graph, snapshot);

        run.currentNode = snapshot.currentNode();
        run.revision = snapshot.revision();
        return run;
    }

    private static void validateSingleTokenFlow(
            TypedWorkflowGraphV1 graph,
            Snapshot snapshot) {
        LinkedHashMap<String, Long> balance = new LinkedHashMap<>();
        for (String nodeId : graph.nodes().keySet()) {
            balance.put(nodeId, 0L);
        }

        for (var entry : snapshot.traversalCounts().entrySet()) {
            int count = entry.getValue();
            if (count == 0) continue;

            TypedWorkflowGraphV1.Edge edge = graph.edges().stream()
                    .filter(candidate ->
                            edgeKey(candidate).equals(entry.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "persisted traversal references unknown edge "
                                    + entry.getKey()));

            balance.put(
                    edge.fromNode(),
                    Math.subtractExact(
                            balance.get(edge.fromNode()),
                            count));
            balance.put(
                    edge.toNode(),
                    Math.addExact(
                            balance.get(edge.toNode()),
                            count));
        }

        for (String nodeId : graph.nodes().keySet()) {
            long expected = 0L;
            if (nodeId.equals(graph.startNode())) {
                expected--;
            }
            if (nodeId.equals(snapshot.currentNode())) {
                expected++;
            }
            if (balance.get(nodeId) != expected) {
                throw new IllegalStateException(
                        "persisted traversal counts cannot produce "
                                + "current node "
                                + snapshot.currentNode()
                                + ": flow mismatch at "
                                + nodeId);
            }
        }
    }

    public synchronized Transition advance(
            String outcome,
            String payloadType) {
        final String normalizedOutcome =
                requireText(outcome, "outcome");
        final String normalizedPayloadType =
                requireText(payloadType, "payloadType");

        TypedWorkflowGraphV1.Node from =
                graph.nodes().get(currentNode);
        if (from.kind() == TypedWorkflowGraphV1.NodeKind.TERMINAL) {
            throw new IllegalStateException(
                    "terminal workflow node cannot advance: "
                            + currentNode);
        }

        String declaredType = from.outcomeTypes().get(normalizedOutcome);
        if (declaredType == null) {
            throw new IllegalArgumentException(
                    "node " + currentNode
                            + " has no outcome " + normalizedOutcome);
        }
        if (!declaredType.equals(normalizedPayloadType)) {
            throw new IllegalArgumentException(
                    "outcome payload type mismatch: expected "
                            + declaredType
                            + " but received "
                            + normalizedPayloadType);
        }

        TypedWorkflowGraphV1.Edge edge = graph.outgoing(currentNode)
                .stream()
                .filter(candidate ->
                        candidate.outcome().equals(normalizedOutcome))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "validated graph lost transition for "
                                + currentNode + "." + normalizedOutcome));

        String edgeKey = edgeKey(edge);
        int previous =
                traversalCounts.getOrDefault(edgeKey, 0);
        int next = Math.addExact(previous, 1);
        if (edge.maxTraversals() != null
                && next > edge.maxTraversals()) {
            throw new TraversalBudgetExceededException(
                    "workflow edge traversal budget exhausted for "
                            + edgeKey
                            + ": "
                            + previous
                            + "/"
                            + edge.maxTraversals());
        }

        long nextRevision = Math.addExact(revision, 1L);
        traversalCounts.put(edgeKey, next);
        currentNode = edge.toNode();
        revision = nextRevision;

        boolean terminal =
                graph.nodes().get(currentNode).kind()
                        == TypedWorkflowGraphV1.NodeKind.TERMINAL;
        return new Transition(
                revision,
                edge.fromNode(),
                edge.outcome(),
                normalizedPayloadType,
                edge.toNode(),
                next,
                edge.maxTraversals(),
                terminal);
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                currentNode,
                revision,
                new LinkedHashMap<>(traversalCounts));
    }

    public synchronized boolean terminal() {
        return graph.nodes().get(currentNode).kind()
                == TypedWorkflowGraphV1.NodeKind.TERMINAL;
    }

    public synchronized TypedWorkflowGraphV1.Node currentNode() {
        return graph.nodes().get(currentNode);
    }

    private TypedWorkflowGraphV1.Edge edgeByKey(String key) {
        for (TypedWorkflowGraphV1.Edge edge : graph.edges()) {
            if (edgeKey(edge).equals(key)) {
                return edge;
            }
        }
        return null;
    }

    private static String edgeKey(TypedWorkflowGraphV1.Edge edge) {
        return edge.fromNode()
                + "::"
                + edge.outcome()
                + "::"
                + edge.toNode();
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(
                    name + " must not be blank");
        }
        return normalized;
    }
}
