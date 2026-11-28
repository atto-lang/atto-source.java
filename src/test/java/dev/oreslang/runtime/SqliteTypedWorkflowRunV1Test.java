package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class SqliteTypedWorkflowRunV1Test {

    @TempDir
    Path tempDir;

    @Test
    void genericRunSurvivesRestartWithTraversalBudgets() {
        Path db = tempDir.resolve("typed.db");
        var graph = DevLoopWorkflowDefinitionV1.create(2);

        try (var run = SqliteTypedWorkflowRunV1.open(
                db, "generic-1", graph)) {
            run.advance("accepted", "dev.goal.v1");
            run.advance("planned", "dev.coding-context.v1");
            run.advance("changed", "dev.patch.v1");
            run.advance("pushed", "dev.push-receipt.v1");
            run.advance("matched", "dev.ci-report.v1");
            run.advance("failed", "dev.failure-context.v1");
            run.advance("reported", "dev.coding-context.v1");
            assertEquals(7L, run.snapshot().revision());
        }

        try (var reopened = SqliteTypedWorkflowRunV1.open(
                db, "generic-1", graph)) {
            assertEquals("code", reopened.snapshot().currentNode());

            reopened.advance("changed", "dev.patch.v1");
            reopened.advance("pushed", "dev.push-receipt.v1");
            reopened.advance("matched", "dev.ci-report.v1");
            reopened.advance("failed", "dev.failure-context.v1");
            reopened.advance("reported", "dev.coding-context.v1");

            reopened.advance("changed", "dev.patch.v1");
            reopened.advance("pushed", "dev.push-receipt.v1");
            reopened.advance("matched", "dev.ci-report.v1");
            reopened.advance("failed", "dev.failure-context.v1");

            var before = reopened.snapshot();
            assertThrows(
                    TypedWorkflowRunV1.TraversalBudgetExceededException.class,
                    () -> reopened.advance(
                            "reported", "dev.coding-context.v1"));
            assertEquals(before, reopened.snapshot());
        }
    }

    @Test
    void graphDefinitionDriftIsRejectedForExistingRun() {
        Path db = tempDir.resolve("drift.db");
        var original = DevLoopWorkflowDefinitionV1.create(2);

        try (var ignored = SqliteTypedWorkflowRunV1.open(
                db, "generic-2", original)) {
            // create row
        }

        var changed = DevLoopWorkflowDefinitionV1.create(3);
        assertNotEquals(
                original.fingerprint(),
                changed.fingerprint());

        assertThrows(
                IllegalStateException.class,
                () -> SqliteTypedWorkflowRunV1.open(
                        db, "generic-2", changed));
    }

    @Test
    void graphFingerprintIgnoresCallerMapInsertionOrder() {
        var first = new TypedWorkflowGraphV1(
                "start",
                List.of(
                        new TypedWorkflowGraphV1.Node(
                                "start",
                                TypedWorkflowGraphV1.NodeKind.TRIGGER,
                                "in",
                                new java.util.LinkedHashMap<>(
                                        Map.of(
                                                "b", "t",
                                                "a", "t"))),
                        new TypedWorkflowGraphV1.Node(
                                "done-a",
                                TypedWorkflowGraphV1.NodeKind.TERMINAL,
                                "t",
                                Map.of()),
                        new TypedWorkflowGraphV1.Node(
                                "done-b",
                                TypedWorkflowGraphV1.NodeKind.TERMINAL,
                                "t",
                                Map.of())),
                List.of(
                        new TypedWorkflowGraphV1.Edge(
                                "start", "a", "done-a", null),
                        new TypedWorkflowGraphV1.Edge(
                                "start", "b", "done-b", null)));

        var outcomes = new java.util.LinkedHashMap<String, String>();
        outcomes.put("a", "t");
        outcomes.put("b", "t");
        var second = new TypedWorkflowGraphV1(
                "start",
                List.of(
                        new TypedWorkflowGraphV1.Node(
                                "done-b",
                                TypedWorkflowGraphV1.NodeKind.TERMINAL,
                                "t",
                                Map.of()),
                        new TypedWorkflowGraphV1.Node(
                                "start",
                                TypedWorkflowGraphV1.NodeKind.TRIGGER,
                                "in",
                                outcomes),
                        new TypedWorkflowGraphV1.Node(
                                "done-a",
                                TypedWorkflowGraphV1.NodeKind.TERMINAL,
                                "t",
                                Map.of())),
                List.of(
                        new TypedWorkflowGraphV1.Edge(
                                "start", "b", "done-b", null),
                        new TypedWorkflowGraphV1.Edge(
                                "start", "a", "done-a", null)));

        assertEquals(
                first.fingerprint(),
                second.fingerprint());
    }

    @Test
    void validButImpossiblePersistedCursorFailsHistoryReplay()
            throws Exception {
        Path db = tempDir.resolve("cursor-tamper.db");
        var graph = DevLoopWorkflowDefinitionV1.create(2);

        try (var run = SqliteTypedWorkflowRunV1.open(
                db, "generic-cursor-tamper", graph)) {
            run.advance("accepted", "dev.goal.v1");
        }

        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + db.toAbsolutePath());
             var statement = connection.createStatement()) {
            assertEquals(
                    1,
                    statement.executeUpdate("""
                            UPDATE typed_workflow_runs
                            SET current_node = 'completed'
                            WHERE run_id = 'generic-cursor-tamper'
                            """));
        }

        assertThrows(
                IllegalStateException.class,
                () -> SqliteTypedWorkflowRunV1.open(
                        db, "generic-cursor-tamper", graph));
    }

    @Test
    void tamperedOrderedHistoryFailsClosedEvenWithSameGraphFingerprint()
            throws Exception {
        Path db = tempDir.resolve("history-tamper.db");
        var graph = DevLoopWorkflowDefinitionV1.create(2);

        try (var run = SqliteTypedWorkflowRunV1.open(
                db, "generic-history-tamper", graph)) {
            run.advance("accepted", "dev.goal.v1");
            run.advance("planned", "dev.coding-context.v1");
        }

        try (var connection = DriverManager.getConnection(
                "jdbc:sqlite:" + db.toAbsolutePath());
             var statement = connection.createStatement()) {
            assertEquals(
                    1,
                    statement.executeUpdate("""
                            UPDATE typed_workflow_history
                            SET to_node = 'completed'
                            WHERE run_id = 'generic-history-tamper'
                              AND revision = 1
                            """));
        }

        assertThrows(
                IllegalStateException.class,
                () -> SqliteTypedWorkflowRunV1.open(
                        db, "generic-history-tamper", graph));
    }

    @Test
    void twoConnectionsCannotLoseAnAdvance() throws Exception {
        Path db = tempDir.resolve("concurrent.db");
        var graph = DevLoopWorkflowDefinitionV1.create(2);

        try (var first = SqliteTypedWorkflowRunV1.open(
                    db, "generic-3", graph);
             var second = SqliteTypedWorkflowRunV1.open(
                    db, "generic-3", graph)) {
            first.advance("accepted", "dev.goal.v1");

            // Second connection reloads current state inside BEGIN IMMEDIATE.
            var transition = second.advance(
                    "planned", "dev.coding-context.v1");
            assertEquals("code", transition.toNode());

            assertEquals(
                    second.snapshot(),
                    first.snapshot());
        }
    }
}
