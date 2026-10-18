package dev.oreslang.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/**
 * Durable SQLite accounting ledger for workflow supervisor budgets.
 *
 * <p>The ledger may live in the same SQLite file as
 * {@link SqliteWorkflowJournalV1}. A restart cannot reset token, CI or spend
 * accounting. Admission is serialized through BEGIN IMMEDIATE and policy
 * identity is immutable for the lifetime of the run.</p>
 */
public final class SqliteWorkflowSupervisorLedgerV1
        implements AutoCloseable {

    private static final int SCHEMA_VERSION = 1;

    private final Path databasePath;
    private Connection connection;
    private final String runId;
    private final WorkflowSupervisorPolicyV1.Policy policy;
    private boolean closed;

    public static SqliteWorkflowSupervisorLedgerV1 open(
            Path databasePath,
            String runId,
            WorkflowSupervisorPolicyV1.Policy policy,
            long startedAtMillis) {
        return new SqliteWorkflowSupervisorLedgerV1(
                databasePath,
                runId,
                policy,
                startedAtMillis);
    }

    private SqliteWorkflowSupervisorLedgerV1(
            Path databasePath,
            String runId,
            WorkflowSupervisorPolicyV1.Policy policy,
            long startedAtMillis) {
        this.databasePath = Objects.requireNonNull(
                databasePath, "databasePath")
                .toAbsolutePath()
                .normalize();
        this.runId = requireText(runId, "runId");
        this.policy = Objects.requireNonNull(policy, "policy");
        if (startedAtMillis < 0) {
            throw new IllegalArgumentException(
                    "startedAtMillis cannot be negative");
        }

        try {
            Path parent = this.databasePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + this.databasePath);
            configure();
            initializeSchema();
            initializeOrVerify(startedAtMillis);
        } catch (Exception failure) {
            closeAfterFailedOpen();
            throw new IllegalStateException(
                    "failed to open workflow supervisor ledger "
                            + this.databasePath,
                    failure);
        }
    }

    public synchronized WorkflowSupervisorPolicyV1.Admission admit(
            WorkflowActivityProtocolV1.Kind activity,
            long nowMillis,
            long additionalModelTokens,
            long additionalSpendMicros,
            int currentAmbiguousEffects,
            boolean protectedWrite) {
        requireOpen();
        boolean committed = false;
        try {
            execute("BEGIN IMMEDIATE");
            WorkflowSupervisorPolicyV1.Usage current =
                    readUsage();
            WorkflowSupervisorPolicyV1.Admission admission =
                    WorkflowSupervisorPolicyV1.admit(
                            policy,
                            current,
                            activity,
                            nowMillis,
                            additionalModelTokens,
                            additionalSpendMicros,
                            currentAmbiguousEffects,
                            protectedWrite);

            // Policy returns unchanged token/spend counters for human gates,
            // but observed ambiguity state still needs durable persistence.
            writeUsage(admission.usage());
            execute("COMMIT");
            committed = true;
            return admission;
        } catch (RuntimeException | Error failure) {
            if (!committed) {
                rollbackOrInvalidate(failure);
            }
            throw failure;
        } catch (SQLException failure) {
            IllegalStateException wrapped = new IllegalStateException(
                    "workflow supervisor admission failed",
                    failure);
            if (!committed) {
                rollbackOrInvalidate(wrapped);
            }
            throw wrapped;
        }
    }

    public synchronized WorkflowSupervisorPolicyV1.Usage cancel(
            String reason) {
        requireOpen();
        boolean committed = false;
        try {
            execute("BEGIN IMMEDIATE");
            WorkflowSupervisorPolicyV1.Usage cancelled =
                    WorkflowSupervisorPolicyV1.cancel(
                            readUsage(), reason);
            writeUsage(cancelled);
            execute("COMMIT");
            committed = true;
            return cancelled;
        } catch (RuntimeException | Error failure) {
            if (!committed) {
                rollbackOrInvalidate(failure);
            }
            throw failure;
        } catch (SQLException failure) {
            IllegalStateException wrapped = new IllegalStateException(
                    "workflow supervisor cancellation failed",
                    failure);
            if (!committed) {
                rollbackOrInvalidate(wrapped);
            }
            throw wrapped;
        }
    }

    public synchronized WorkflowSupervisorPolicyV1.Usage usage() {
        requireOpen();
        try {
            return readUsage();
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "failed to read workflow supervisor usage",
                    failure);
        }
    }

    public String runId() {
        return runId;
    }

    public WorkflowSupervisorPolicyV1.Policy policy() {
        return policy;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            connection.close();
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "failed to close workflow supervisor ledger",
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
                CREATE TABLE IF NOT EXISTS workflow_supervisor (
                    run_id TEXT PRIMARY KEY,
                    schema_version INTEGER NOT NULL,
                    max_wall_clock_millis INTEGER NOT NULL,
                    max_model_tokens INTEGER NOT NULL,
                    max_ci_runs INTEGER NOT NULL,
                    max_spend_micros INTEGER NOT NULL,
                    max_ambiguous_effects INTEGER NOT NULL,
                    require_approval_before_protected_write INTEGER NOT NULL,
                    started_at_millis INTEGER NOT NULL,
                    model_tokens INTEGER NOT NULL,
                    ci_runs INTEGER NOT NULL,
                    spend_micros INTEGER NOT NULL,
                    ambiguous_effects INTEGER NOT NULL,
                    cancelled INTEGER NOT NULL,
                    cancellation_reason TEXT
                )
                """);
    }

    private void initializeOrVerify(long startedAtMillis)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO workflow_supervisor (
                    run_id,
                    schema_version,
                    max_wall_clock_millis,
                    max_model_tokens,
                    max_ci_runs,
                    max_spend_micros,
                    max_ambiguous_effects,
                    require_approval_before_protected_write,
                    started_at_millis,
                    model_tokens,
                    ci_runs,
                    spend_micros,
                    ambiguous_effects,
                    cancelled,
                    cancellation_reason
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, 0, NULL)
                """)) {
            statement.setString(1, runId);
            statement.setInt(2, SCHEMA_VERSION);
            statement.setLong(3, policy.maxWallClockMillis());
            statement.setLong(4, policy.maxModelTokens());
            statement.setInt(5, policy.maxCiRuns());
            statement.setLong(6, policy.maxSpendMicros());
            statement.setInt(7, policy.maxAmbiguousEffects());
            statement.setInt(
                    8,
                    policy.requireApprovalBeforeProtectedWrite()
                            ? 1
                            : 0);
            statement.setLong(9, startedAtMillis);
            statement.executeUpdate();
        }

        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT *
                FROM workflow_supervisor
                WHERE run_id = ?
                """)) {
            statement.setString(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException(
                            "workflow supervisor row is missing");
                }
                if (row.getInt("schema_version") != SCHEMA_VERSION
                        || row.getLong("max_wall_clock_millis")
                                != policy.maxWallClockMillis()
                        || row.getLong("max_model_tokens")
                                != policy.maxModelTokens()
                        || row.getInt("max_ci_runs")
                                != policy.maxCiRuns()
                        || row.getLong("max_spend_micros")
                                != policy.maxSpendMicros()
                        || row.getInt("max_ambiguous_effects")
                                != policy.maxAmbiguousEffects()
                        || (row.getInt(
                                "require_approval_before_protected_write")
                                != 0)
                                != policy
                                        .requireApprovalBeforeProtectedWrite()
                        || row.getLong("started_at_millis")
                                != startedAtMillis) {
                    throw new IllegalStateException(
                            "existing workflow supervisor policy/start "
                                    + "does not match requested configuration");
                }
                decodeUsage(row);
            }
        }
    }

    private WorkflowSupervisorPolicyV1.Usage readUsage()
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT *
                FROM workflow_supervisor
                WHERE run_id = ?
                """)) {
            statement.setString(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    throw new SQLException(
                            "workflow supervisor row disappeared");
                }
                return decodeUsage(row);
            }
        }
    }

    private WorkflowSupervisorPolicyV1.Usage decodeUsage(
            ResultSet row) throws SQLException {
        boolean cancelled = row.getInt("cancelled") != 0;
        String reason = row.getString("cancellation_reason");
        return new WorkflowSupervisorPolicyV1.Usage(
                row.getLong("started_at_millis"),
                row.getLong("model_tokens"),
                row.getInt("ci_runs"),
                row.getLong("spend_micros"),
                row.getInt("ambiguous_effects"),
                cancelled,
                reason);
    }

    private void writeUsage(
            WorkflowSupervisorPolicyV1.Usage usage)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE workflow_supervisor
                SET
                    model_tokens = ?,
                    ci_runs = ?,
                    spend_micros = ?,
                    ambiguous_effects = ?,
                    cancelled = ?,
                    cancellation_reason = ?
                WHERE run_id = ?
                """)) {
            statement.setLong(1, usage.modelTokens());
            statement.setInt(2, usage.ciRuns());
            statement.setLong(3, usage.spendMicros());
            statement.setInt(4, usage.ambiguousEffects());
            statement.setInt(5, usage.cancelled() ? 1 : 0);
            statement.setString(6, usage.cancellationReason());
            statement.setString(7, runId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException(
                        "workflow supervisor usage update failed");
            }
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
                    "workflow supervisor ledger is closed");
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
