package dev.oreslang.runtime;

import java.util.List;
import java.util.Map;

/** Canonical typed graph for the v1 AI development loop. */
public final class DevLoopWorkflowDefinitionV1 {

    private DevLoopWorkflowDefinitionV1() { }

    public static TypedWorkflowGraphV1 create(int maxIterations) {
        if (maxIterations <= 0 || maxIterations > 10_000) {
            throw new IllegalArgumentException(
                    "maxIterations must be in [1, 10000]");
        }

        var nodes = List.of(
                new TypedWorkflowGraphV1.Node(
                        "goal",
                        TypedWorkflowGraphV1.NodeKind.TRIGGER,
                        "dev.goal.v1",
                        Map.of("accepted", "dev.goal.v1")),
                new TypedWorkflowGraphV1.Node(
                        "plan",
                        TypedWorkflowGraphV1.NodeKind.ACTIVITY,
                        "dev.goal.v1",
                        Map.of("planned", "dev.coding-context.v1")),
                new TypedWorkflowGraphV1.Node(
                        "code",
                        TypedWorkflowGraphV1.NodeKind.ACTIVITY,
                        "dev.coding-context.v1",
                        Map.of("changed", "dev.patch.v1")),
                new TypedWorkflowGraphV1.Node(
                        "push",
                        TypedWorkflowGraphV1.NodeKind.ACTIVITY,
                        "dev.patch.v1",
                        Map.of("pushed", "dev.push-receipt.v1")),
                new TypedWorkflowGraphV1.Node(
                        "wait-ci",
                        TypedWorkflowGraphV1.NodeKind.WAIT,
                        "dev.push-receipt.v1",
                        Map.of("matched", "dev.ci-report.v1")),
                new TypedWorkflowGraphV1.Node(
                        "evaluate",
                        TypedWorkflowGraphV1.NodeKind.DECISION,
                        "dev.ci-report.v1",
                        Map.of(
                                "passed", "dev.approval-request.v1",
                                "failed", "dev.failure-context.v1")),
                new TypedWorkflowGraphV1.Node(
                        "bug-report",
                        TypedWorkflowGraphV1.NodeKind.ACTIVITY,
                        "dev.failure-context.v1",
                        Map.of("reported", "dev.coding-context.v1")),
                new TypedWorkflowGraphV1.Node(
                        "approval",
                        TypedWorkflowGraphV1.NodeKind.APPROVAL,
                        "dev.approval-request.v1",
                        Map.of(
                                "approved", "dev.terminal.v1",
                                "rejected", "dev.terminal.v1")),
                new TypedWorkflowGraphV1.Node(
                        "completed",
                        TypedWorkflowGraphV1.NodeKind.TERMINAL,
                        "dev.terminal.v1",
                        Map.of()),
                new TypedWorkflowGraphV1.Node(
                        "cancelled",
                        TypedWorkflowGraphV1.NodeKind.TERMINAL,
                        "dev.terminal.v1",
                        Map.of()));

        var edges = List.of(
                new TypedWorkflowGraphV1.Edge(
                        "goal", "accepted", "plan", null),
                new TypedWorkflowGraphV1.Edge(
                        "plan", "planned", "code", null),
                new TypedWorkflowGraphV1.Edge(
                        "code", "changed", "push", null),
                new TypedWorkflowGraphV1.Edge(
                        "push", "pushed", "wait-ci", null),
                new TypedWorkflowGraphV1.Edge(
                        "wait-ci", "matched", "evaluate", null),
                new TypedWorkflowGraphV1.Edge(
                        "evaluate", "passed", "approval", null),
                new TypedWorkflowGraphV1.Edge(
                        "evaluate", "failed", "bug-report", null),
                new TypedWorkflowGraphV1.Edge(
                        "bug-report",
                        "reported",
                        "code",
                        maxIterations),
                new TypedWorkflowGraphV1.Edge(
                        "approval", "approved", "completed", null),
                new TypedWorkflowGraphV1.Edge(
                        "approval", "rejected", "cancelled", null));

        return new TypedWorkflowGraphV1("goal", nodes, edges);
    }
}
