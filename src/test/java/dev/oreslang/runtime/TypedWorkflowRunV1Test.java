package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class TypedWorkflowRunV1Test {

    @Test
    void canonicalDevLoopTraversesSuccessPathToTerminal() {
        var run = new TypedWorkflowRunV1(
                DevLoopWorkflowDefinitionV1.create(3));

        run.advance("accepted", "dev.goal.v1");
        run.advance("planned", "dev.coding-context.v1");
        run.advance("changed", "dev.patch.v1");
        run.advance("pushed", "dev.push-receipt.v1");
        run.advance("matched", "dev.ci-report.v1");
        run.advance("passed", "dev.approval-request.v1");
        var terminal = run.advance(
                "approved", "dev.terminal.v1");

        assertTrue(terminal.terminal());
        assertTrue(run.terminal());
        assertEquals("completed", run.currentNode().id());
        assertEquals(7L, run.snapshot().revision());
    }

    @Test
    void boundedFailureLoopStopsBeforeFourthRetry() {
        var run = new TypedWorkflowRunV1(
                DevLoopWorkflowDefinitionV1.create(3));

        run.advance("accepted", "dev.goal.v1");
        run.advance("planned", "dev.coding-context.v1");

        for (int i = 0; i < 3; i++) {
            run.advance("changed", "dev.patch.v1");
            run.advance("pushed", "dev.push-receipt.v1");
            run.advance("matched", "dev.ci-report.v1");
            run.advance("failed", "dev.failure-context.v1");
            run.advance("reported", "dev.coding-context.v1");
        }

        run.advance("changed", "dev.patch.v1");
        run.advance("pushed", "dev.push-receipt.v1");
        run.advance("matched", "dev.ci-report.v1");
        run.advance("failed", "dev.failure-context.v1");

        var before = run.snapshot();
        assertThrows(
                TypedWorkflowRunV1.TraversalBudgetExceededException.class,
                () -> run.advance(
                        "reported", "dev.coding-context.v1"));
        assertEquals(before, run.snapshot(),
                "budget failure must be state-atomic");
    }

    @Test
    void wrongOutcomePayloadTypeDoesNotAdvanceRevision() {
        var run = new TypedWorkflowRunV1(
                DevLoopWorkflowDefinitionV1.create(2));

        var before = run.snapshot();
        assertThrows(
                IllegalArgumentException.class,
                () -> run.advance(
                        "accepted", "wrong.type"));
        assertEquals(before, run.snapshot());
    }

    @Test
    void snapshotRestorePreservesTraversalBudget() {
        var graph = DevLoopWorkflowDefinitionV1.create(2);
        var run = new TypedWorkflowRunV1(graph);

        run.advance("accepted", "dev.goal.v1");
        run.advance("planned", "dev.coding-context.v1");
        run.advance("changed", "dev.patch.v1");
        run.advance("pushed", "dev.push-receipt.v1");
        run.advance("matched", "dev.ci-report.v1");
        run.advance("failed", "dev.failure-context.v1");
        run.advance("reported", "dev.coding-context.v1");

        var restored = TypedWorkflowRunV1.restore(
                graph, run.snapshot());
        assertEquals(run.snapshot(), restored.snapshot());

        restored.advance("changed", "dev.patch.v1");
        restored.advance("pushed", "dev.push-receipt.v1");
        restored.advance("matched", "dev.ci-report.v1");
        restored.advance("failed", "dev.failure-context.v1");
        restored.advance("reported", "dev.coding-context.v1");

        restored.advance("changed", "dev.patch.v1");
        restored.advance("pushed", "dev.push-receipt.v1");
        restored.advance("matched", "dev.ci-report.v1");
        restored.advance("failed", "dev.failure-context.v1");
        assertThrows(
                TypedWorkflowRunV1.TraversalBudgetExceededException.class,
                () -> restored.advance(
                        "reported", "dev.coding-context.v1"));
    }

    @Test
    void restoreRejectsCurrentNodeImpossibleForTraversalCounts() {
        var graph = DevLoopWorkflowDefinitionV1.create(2);
        var run = new TypedWorkflowRunV1(graph);
        run.advance("accepted", "dev.goal.v1");

        var snapshot = run.snapshot();
        assertThrows(
                IllegalStateException.class,
                () -> TypedWorkflowRunV1.restore(
                        graph,
                        new TypedWorkflowRunV1.Snapshot(
                                "completed",
                                snapshot.revision(),
                                snapshot.traversalCounts())));
    }

    @Test
    void tamperedSnapshotRevisionFailsClosed() {
        var graph = DevLoopWorkflowDefinitionV1.create(2);
        var run = new TypedWorkflowRunV1(graph);
        run.advance("accepted", "dev.goal.v1");

        var snapshot = run.snapshot();
        assertThrows(
                IllegalStateException.class,
                () -> TypedWorkflowRunV1.restore(
                        graph,
                        new TypedWorkflowRunV1.Snapshot(
                                snapshot.currentNode(),
                                snapshot.revision() + 1,
                                snapshot.traversalCounts())));
    }

    @Test
    void tamperedUnknownEdgeFailsClosed() {
        var graph = DevLoopWorkflowDefinitionV1.create(2);
        assertThrows(
                IllegalStateException.class,
                () -> TypedWorkflowRunV1.restore(
                        graph,
                        new TypedWorkflowRunV1.Snapshot(
                                "goal",
                                1L,
                                Map.of("unknown::edge::key", 1))));
    }
}
