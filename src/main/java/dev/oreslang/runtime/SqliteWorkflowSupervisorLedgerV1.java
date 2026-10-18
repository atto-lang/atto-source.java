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

    private static final int SCHEMA_VERSION = 2;

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

    /**
     * Idempotently reserve estimated model/spend budget for one durable effect.
     *
     * <p>Only ALLOW admissions create a reservation. Repeating the same
     * reservation key/parameters is a no-op; conflicting reuse fails closed.</p>
     */
    public synchronized WorkflowSupervisorPolicyV1.Admission reserve(
            String effectKey,
            WorkflowActivityProtocolV1.Kind activity,
            long nowMillis,
            long estimatedModelTokens,
            long estimatedSpendMicros,
            int currentAmbiguousEffects,
            boolean protectedWrite) {
        requireOpen();
        effectKey = requireText(effectKey, "effectKey");
        Objects.requireNonNull(activity, "activity");
        boolean committed = false;
        try {
            execute("BEGIN IMMEDIATE");
            Reservation existing = readReservation(effectKey);
            if (existing != null) {
                if (existing.activity() != activity
                        || existing.reservedModelTokens()
                                != estimatedModelTokens
                        || existing.reservedSpendMicros()
                                != estimatedSpendMicros
                        || existing.protectedWrite()
                                != protectedWrite) {
                    throw new IllegalStateException(
                            "supervisor reservation key was reused "
                                    + "with different parameters: "
                                    + effectKey);
                }
                WorkflowSupervisorPolicyV1.Usage usage = readUsage();
                WorkflowSupervisorPolicyV1.Admission replay =
                        usage.cancelled()
                                ? new WorkflowSupervisorPolicyV1.Admission(
                                        WorkflowSupervisorPolicyV1.Decision.CANCEL,
                                        usage,
                                        usage.cancellationReason())
                                : new WorkflowSupervisorPolicyV1.Admission(
                                        WorkflowSupervisorPolicyV1.Decision.ALLOW,
                                        usage,
                                        null);
                execute("COMMIT");
                committed = true;
                return replay;
            }

            WorkflowSupervisorPolicyV1.Admission admission =
                    WorkflowSupervisorPolicyV1.admit(
                            policy,
                            readUsage(),
                            activity,
                            nowMillis,
                            estimatedModelTokens,
                            estimatedSpendMicros,
                            currentAmbiguousEffects,
                            protectedWrite);
            writeUsage(admission.usage());
            if (admission.decision()
                    == WorkflowSupervisorPolicyV1.Decision.ALLOW) {
                insertReservation(
                        effectKey,
                        activity,
                        estimatedModelTokens,
                        estimatedSpendMicros,
                        protectedWrite);
            }
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
                    "workflow supervisor reservation failed",
                    failure);
            if (!committed) {
                rollbackOrInvalidate(wrapped);
            }
            throw wrapped;
        }
    }

    /**
     * Settle actual provider usage against one previously admitted reservation.
     *
     * <p>Settlement may refund conservative estimates or charge an overage.
     * An overage that crosses a policy limit is still recorded as reality and
     * atomically cancels future workflow admission. Exact repeated settlement
     * is idempotent; a different second settlement is rejected.</p>
     */
    public synchronized WorkflowSupervisorPolicyV1.Usage settle(
            String effectKey,
            long actualModelTokens,
            long actualSpendMicros) {
        requireOpen();
        effectKey = requireText(effectKey, "effectKey");
        if (actualModelTokens < 0 || actualSpendMicros < 0) {
            throw new IllegalArgumentException(
                    "actual usage cannot be negative");
        }

        boolean committed = false;
        try {
            execute("BEGIN IMMEDIATE");
            Reservation reservation = readReservation(effectKey);
            if (reservation == null) {
                throw new IllegalArgumentException(
                        "unknown supervisor reservation " + effectKey);
            }
            WorkflowSupervisorPolicyV1.Usage current = readUsage();

            if (reservation.settled()) {
                if (reservation.actualModelTokens()
                                != actualModelTokens
                        || reservation.actualSpendMicros()
                                != actualSpendMicros) {
                    throw new IllegalStateException(
                            "supervisor reservation was already settled "
                                    + "with different actual usage: "
                                    + effectKey);
                }
                execute("COMMIT");
                committed = true;
                return current;
            }

            long withoutReservedTokens = Math.subtractExact(
                    current.modelTokens(),
                    reservation.reservedModelTokens());
            long withoutReservedSpend = Math.subtractExact(
                    current.spendMicros(),
                    reservation.reservedSpendMicros());
            long settledTokens = Math.addExact(
                    withoutReservedTokens, actualModelTokens);
            long settledSpend = Math.addExact(
                    withoutReservedSpend, actualSpendMicros);
            if (settledTokens < 0 || settledSpend < 0) {
                throw new IllegalStateException(
                        "supervisor reservation accounting underflow");
            }

            WorkflowSupervisorPolicyV1.Usage adjusted =
                    new WorkflowSupervisorPolicyV1.Usage(
                            current.startedAtMillis(),
                            settledTokens,
                            current.ciRuns(),
                            settledSpend,
                            current.ambiguousEffects(),
                            current.cancelled(),
                            current.cancellationReason());

            if (!adjusted.cancelled()
                    && settledTokens > policy.maxModelTokens()) {
                adjusted = WorkflowSupervisorPolicyV1.cancel(
                        adjusted,
                        "model_token_budget_exhausted_after_settlement");
            } else if (!adjusted.cancelled()
                    && settledSpend > policy.maxSpendMicros()) {
                adjusted = WorkflowSupervisorPolicyV1.cancel(
                        adjusted,
                        "spend_budget_exhausted_after_settlement");
            }

            writeUsage(adjusted);
            markReservationSettled(
                    effectKey,
                    actualModelTokens,
                    actualSpendMicros);
            execute("COMMIT");
            committed = true;
            return adjusted;
        } catch (RuntimeException | Error failure) {
            if (!committed) {
                rollbackOrInvalidate(failure);
            }
            throw failure;
        } catch (SQLException failure) {
            IllegalStateException wrapped = new IllegalStateException(
                    "workflow supervisor settlement failed",
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
            WorkflowSupervisorPolicyV1.Usage current =
                    readUsage();
            if (current.cancelled()) {
                if (!current.cancellationReason().equals(reason)) {
                    throw new IllegalStateException(
                            "workflow cancellation reason is immutable: "
                                    + current.cancellationReason());
                }
                execute("COMMIT");
                committed = true;
                return current;
            }

            WorkflowSupervisorPolicyV1.Usage cancelled =
                    WorkflowSupervisorPolicyV1.cancel(
                            current, reason);
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
        execute("""
                CREATE TABLE IF NOT EXISTS workflow_supervisor_reservations (
                    run_id TEXT NOT NULL,
                    effect_key TEXT NOT NULL,
                    activity TEXT NOT NULL,
                    reserved_model_tokens INTEGER NOT NULL,
                    reserved_spend_micros INTEGER NOT NULL,
                    protected_write INTEGER NOT NULL,
                    settled INTEGER NOT NULL,
                    actual_model_tokens INTEGER,
                    actual_spend_micros INTEGER,
                    PRIMARY KEY (run_id, effect_key),
                    FOREIGN KEY (run_id)
                        REFERENCES workflow_supervisor(run_id)
                        ON DELETE CASCADE,
                    CHECK(settled IN (0, 1)),
                    CHECK(protected_write IN (0, 1)),
                    CHECK(
                        (settled = 0
                            AND actual_model_tokens IS NULL
                            AND actual_spend_micros IS NULL)
                        OR
                        (settled = 1
                            AND actual_model_tokens IS NOT NULL
                            AND actual_spend_micros IS NOT NULL)
                    )
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

    private Reservation readReservation(String effectKey)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT
                    activity,
                    reserved_model_tokens,
                    reserved_spend_micros,
                    protected_write,
                    settled,
                    actual_model_tokens,
                    actual_spend_micros
                FROM workflow_supervisor_reservations
                WHERE run_id = ? AND effect_key = ?
                """)) {
            statement.setString(1, runId);
            statement.setString(2, effectKey);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                boolean settled = row.getInt("settled") != 0;
                Long actualTokens = settled
                        ? row.getLong("actual_model_tokens")
                        : null;
                Long actualSpend = settled
                        ? row.getLong("actual_spend_micros")
                        : null;
                return new Reservation(
                        WorkflowActivityProtocolV1.Kind.valueOf(
                                row.getString("activity")),
                        row.getLong("reserved_model_tokens"),
                        row.getLong("reserved_spend_micros"),
                        row.getInt("protected_write") != 0,
                        settled,
                        actualTokens,
                        actualSpend);
            }
        }
    }

    private void insertReservation(
            String effectKey,
            WorkflowActivityProtocolV1.Kind activity,
            long modelTokens,
            long spendMicros,
            boolean protectedWrite) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO workflow_supervisor_reservations (
                    run_id,
                    effect_key,
                    activity,
                    reserved_model_tokens,
                    reserved_spend_micros,
                    protected_write,
                    settled,
                    actual_model_tokens,
                    actual_spend_micros
                ) VALUES (?, ?, ?, ?, ?, ?, 0, NULL, NULL)
                """)) {
            statement.setString(1, runId);
            statement.setString(2, effectKey);
            statement.setString(3, activity.name());
            statement.setLong(4, modelTokens);
            statement.setLong(5, spendMicros);
            statement.setInt(6, protectedWrite ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private void markReservationSettled(
            String effectKey,
            long actualModelTokens,
            long actualSpendMicros) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE workflow_supervisor_reservations
                SET
                    settled = 1,
                    actual_model_tokens = ?,
                    actual_spend_micros = ?
                WHERE run_id = ? AND effect_key = ?
                  AND settled = 0
                """)) {
            statement.setLong(1, actualModelTokens);
            statement.setLong(2, actualSpendMicros);
            statement.setString(3, runId);
            statement.setString(4, effectKey);
            if (statement.executeUpdate() != 1) {
                throw new SQLException(
                        "supervisor reservation settlement update failed");
            }
        }
    }

    private record Reservation(
            WorkflowActivityProtocolV1.Kind activity,
            long reservedModelTokens,
            long reservedSpendMicros,
            boolean protectedWrite,
            boolean settled,
            Long actualModelTokens,
            Long actualSpendMicros) { }

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
