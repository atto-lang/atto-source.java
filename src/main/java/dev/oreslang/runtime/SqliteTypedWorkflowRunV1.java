package dev.oreslang.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * SQLite persistence for the generic typed workflow execution cursor.
 *
 * <p>Each durable run pins the canonical graph fingerprint. Definition drift is
 * rejected rather than replaying old state under new transitions.</p>
 */
public final class SqliteTypedWorkflowRunV1 implements AutoCloseable {

    private static final int SCHEMA_VERSION = 2;

    private final Path databasePath;
    private Connection connection;
    private final String runId;
    private final TypedWorkflowGraphV1 graph;
    private final String graphFingerprint;
    private boolean closed;

    public static SqliteTypedWorkflowRunV1 open(
            Path databasePath,
            String runId,
            TypedWorkflowGraphV1 graph) {
        return new SqliteTypedWorkflowRunV1(
                databasePath, runId, graph);
    }

    private SqliteTypedWorkflowRunV1(
            Path databasePath,
            String runId,
            TypedWorkflowGraphV1 graph) {
        this.databasePath = Objects.requireNonNull(
                databasePath, "databasePath")
                .toAbsolutePath()
                .normalize();
        this.runId = requireText(runId, "runId");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.graphFingerprint = graph.fingerprint();

        try {
            Path parent = this.databasePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + this.databasePath);
            configure();
            initializeSchema();
            initializeOrVerify();
        } catch (Exception failure) {
            closeAfterFailedOpen();
            throw new IllegalStateException(
                    "failed to open typed workflow run "
                            + this.databasePath,
                    failure);
        }
    }

    public synchronized TypedWorkflowRunV1.Transition advance(
            String outcome,
            String payloadType) {
        requireOpen();
        boolean committed = false;
        try {
            execute("BEGIN IMMEDIATE");
            TypedWorkflowRunV1 run =
                    loadVerifiedRun();
            TypedWorkflowRunV1.Transition transition =
                    run.advance(outcome, payloadType);
            writeSnapshot(run.snapshot());
            insertHistory(transition);
            execute("COMMIT");
            committed = true;
            return transition;
        } catch (RuntimeException | Error failure) {
            if (!committed) {
                rollbackOrInvalidate(failure);
            }
            throw failure;
        } catch (SQLException failure) {
            IllegalStateException wrapped = new IllegalStateException(
                    "typed workflow advance failed",
                    failure);
            if (!committed) {
                rollbackOrInvalidate(wrapped);
            }
            throw wrapped;
        }
    }

    public synchronized TypedWorkflowRunV1.Snapshot snapshot() {
        requireOpen();
        try {
            return loadVerifiedRun().snapshot();
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "failed to read typed workflow run",
                    failure);
        }
    }

    public String runId() {
        return runId;
    }

    public String graphFingerprint() {
        return graphFingerprint;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            connection.close();
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "failed to close typed workflow run",
                    failure);
        }
    }

    private void closeAfterFailedOpen() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Preserve the constructor failure that made this handle unusable.
        } finally {
            connection = null;
        }
    }

    private void configure() throws SQLException {
        execute("PRAGMA foreign_keys=ON");
        execute("PRAGMA journal_mode=WAL");
        execute("PRAGMA synchronous=FULL");
        execute("PRAGMA busy_timeout=5000");
    }

    private void initializeSchema() throws SQLException {
        execute("""
                CREATE TABLE IF NOT EXISTS typed_workflow_runs (
                    run_id TEXT PRIMARY KEY,
                    schema_version INTEGER NOT NULL,
                    graph_fingerprint TEXT NOT NULL,
                    current_node TEXT NOT NULL,
                    revision INTEGER NOT NULL
                )
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS typed_workflow_traversals (
                    run_id TEXT NOT NULL,
                    edge_key TEXT NOT NULL,
                    traversal_count INTEGER NOT NULL,
                    PRIMARY KEY (run_id, edge_key),
                    FOREIGN KEY (run_id)
                        REFERENCES typed_workflow_runs(run_id)
                        ON DELETE CASCADE
                )
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS typed_workflow_history (
                    run_id TEXT NOT NULL,
                    revision INTEGER NOT NULL,
                    from_node TEXT NOT NULL,
                    outcome TEXT NOT NULL,
                    payload_type TEXT NOT NULL,
                    to_node TEXT NOT NULL,
                    traversal_count INTEGER NOT NULL,
                    max_traversals INTEGER,
                    terminal INTEGER NOT NULL CHECK(terminal IN (0, 1)),
                    PRIMARY KEY (run_id, revision),
                    FOREIGN KEY (run_id)
                        REFERENCES typed_workflow_runs(run_id)
                        ON DELETE CASCADE
                )
                """);
    }

    private void initializeOrVerify() throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO typed_workflow_runs (
                    run_id,
                    schema_version,
                    graph_fingerprint,
                    current_node,
                    revision
                ) VALUES (?, ?, ?, ?, 0)
                """)) {
            statement.setString(1, runId);
            statement.setInt(2, SCHEMA_VERSION);
            statement.setString(3, graphFingerprint);
            statement.setString(4, graph.startNode());
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    schema_version,
                    graph_fingerprint
                FROM typed_workflow_runs
                WHERE run_id = ?
                """)) {
            statement.setString(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException(
                            "typed workflow run row is missing");
                }
                if (row.getInt("schema_version") != SCHEMA_VERSION) {
                    throw new IllegalStateException(
                            "unsupported typed workflow schema version");
                }
                if (!graphFingerprint.equals(
                        row.getString("graph_fingerprint"))) {
                    throw new IllegalStateException(
                            "workflow graph fingerprint changed for durable run "
                                    + runId);
                }
            }
        }

        loadVerifiedRun();
    }

    private TypedWorkflowRunV1 loadVerifiedRun()
            throws SQLException {
        TypedWorkflowRunV1 run = new TypedWorkflowRunV1(graph);

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    revision,
                    from_node,
                    outcome,
                    payload_type,
                    to_node,
                    traversal_count,
                    max_traversals,
                    terminal
                FROM typed_workflow_history
                WHERE run_id = ?
                ORDER BY revision
                """)) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                long expectedRevision = 1L;
                while (rows.next()) {
                    long revision = rows.getLong("revision");
                    if (revision != expectedRevision) {
                        throw new IllegalStateException(
                                "typed workflow history revision gap: expected "
                                        + expectedRevision
                                        + " but found "
                                        + revision);
                    }

                    TypedWorkflowRunV1.Transition replayed =
                            run.advance(
                                    rows.getString("outcome"),
                                    rows.getString("payload_type"));

                    Number max = (Number) rows.getObject(
                            "max_traversals");
                    Integer maxTraversals =
                            max == null ? null : max.intValue();
                    TypedWorkflowRunV1.Transition persisted =
                            new TypedWorkflowRunV1.Transition(
                                    revision,
                                    rows.getString("from_node"),
                                    rows.getString("outcome"),
                                    rows.getString("payload_type"),
                                    rows.getString("to_node"),
                                    rows.getInt("traversal_count"),
                                    maxTraversals,
                                    rows.getInt("terminal") != 0);
                    if (!replayed.equals(persisted)) {
                        throw new IllegalStateException(
                                "typed workflow history failed deterministic "
                                        + "replay at revision "
                                        + revision);
                    }
                    expectedRevision =
                            Math.addExact(expectedRevision, 1L);
                }
            }
        }

        TypedWorkflowRunV1.Snapshot persistedSnapshot =
                readSnapshot();
        if (!run.snapshot().equals(persistedSnapshot)) {
            throw new IllegalStateException(
                    "typed workflow snapshot disagrees with replayed history");
        }
        return run;
    }

    private void insertHistory(
            TypedWorkflowRunV1.Transition transition)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO typed_workflow_history (
                    run_id,
                    revision,
                    from_node,
                    outcome,
                    payload_type,
                    to_node,
                    traversal_count,
                    max_traversals,
                    terminal
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, runId);
            statement.setLong(2, transition.revision());
            statement.setString(3, transition.fromNode());
            statement.setString(4, transition.outcome());
            statement.setString(5, transition.payloadType());
            statement.setString(6, transition.toNode());
            statement.setInt(7, transition.traversalCount());
            if (transition.maxTraversals() == null) {
                statement.setNull(8, java.sql.Types.INTEGER);
            } else {
                statement.setInt(8, transition.maxTraversals());
            }
            statement.setInt(9, transition.terminal() ? 1 : 0);
            if (statement.executeUpdate() != 1) {
                throw new SQLException(
                        "typed workflow history insert failed");
            }
        }
    }

    private TypedWorkflowRunV1.Snapshot readSnapshot()
            throws SQLException {
        String currentNode;
        long revision;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    current_node,
                    revision,
                    graph_fingerprint
                FROM typed_workflow_runs
                WHERE run_id = ?
                """)) {
            statement.setString(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException(
                            "typed workflow run row disappeared");
                }
                if (!graphFingerprint.equals(
                        row.getString("graph_fingerprint"))) {
                    throw new IllegalStateException(
                            "typed workflow graph fingerprint mismatch");
                }
                currentNode = row.getString("current_node");
                revision = row.getLong("revision");
            }
        }

        LinkedHashMap<String, Integer> counts =
                new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT edge_key, traversal_count
                FROM typed_workflow_traversals
                WHERE run_id = ?
                ORDER BY edge_key
                """)) {
            statement.setString(1, runId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    counts.put(
                            rows.getString("edge_key"),
                            rows.getInt("traversal_count"));
                }
            }
        }
        return new TypedWorkflowRunV1.Snapshot(
                currentNode, revision, counts);
    }

    private void writeSnapshot(
            TypedWorkflowRunV1.Snapshot snapshot)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE typed_workflow_runs
                SET current_node = ?, revision = ?
                WHERE run_id = ? AND graph_fingerprint = ?
                """)) {
            statement.setString(1, snapshot.currentNode());
            statement.setLong(2, snapshot.revision());
            statement.setString(3, runId);
            statement.setString(4, graphFingerprint);
            if (statement.executeUpdate() != 1) {
                throw new SQLException(
                        "typed workflow run update failed");
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                DELETE FROM typed_workflow_traversals
                WHERE run_id = ?
                """)) {
            statement.setString(1, runId);
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO typed_workflow_traversals (
                    run_id,
                    edge_key,
                    traversal_count
                ) VALUES (?, ?, ?)
                """)) {
            for (Map.Entry<String, Integer> entry
                    : snapshot.traversalCounts().entrySet()) {
                statement.setString(1, runId);
                statement.setString(2, entry.getKey());
                statement.setInt(3, entry.getValue());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void rollbackOrInvalidate(Throwable originalFailure) {
        try {
            execute("ROLLBACK");
        } catch (SQLException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
            invalidateConnection();
        }
    }

    private void invalidateConnection() {
        closed = true;
        Connection doomed = connection;
        connection = null;
        if (doomed == null) return;
        try {
            doomed.close();
        } catch (SQLException ignored) {
            // Preserve the original transaction/rollback failure.
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "typed workflow run is closed");
        }
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
