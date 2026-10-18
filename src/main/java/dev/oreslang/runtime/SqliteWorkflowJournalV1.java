package dev.oreslang.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * SQLite-backed durable implementation of the v1 workflow journal/outbox.
 *
 * <p>The reducer remains the sole workflow authority. Every mutation begins an
 * IMMEDIATE SQLite transaction, reconstructs and verifies the reference store,
 * applies exactly one semantic operation, writes the complete new durable
 * state, and commits atomically. The in-memory delegate is replaced only after
 * the database commit succeeds.</p>
 *
 * <p>WAL plus synchronous=FULL is used intentionally for the reference durable
 * backend. This favors crash semantics and testability over write throughput.
 * Higher-throughput adapters may use incremental rows but must preserve the
 * same atomic journal/outbox boundary.</p>
 */
public final class SqliteWorkflowJournalV1
        implements WorkflowJournalStoreV1 {

    public enum FaultPoint {
        AFTER_BEGIN,
        AFTER_META_WRITE,
        AFTER_JOURNAL_WRITE,
        AFTER_EFFECT_WRITE,
        BEFORE_COMMIT,
        AFTER_COMMIT
    }

    @FunctionalInterface
    interface FaultInjector {
        FaultInjector NONE = point -> { };
        void hit(FaultPoint point);
    }

    private static final int SCHEMA_VERSION = 1;

    private final Path databasePath;
    private Connection connection;
    private final FaultInjector faultInjector;

    private WorkflowJournalV1 delegate;
    private boolean closed;

    public static SqliteWorkflowJournalV1 open(Path databasePath) {
        return new SqliteWorkflowJournalV1(
                databasePath, FaultInjector.NONE);
    }

    static SqliteWorkflowJournalV1 open(
            Path databasePath,
            FaultInjector faultInjector) {
        return new SqliteWorkflowJournalV1(
                databasePath, faultInjector);
    }

    private SqliteWorkflowJournalV1(
            Path databasePath,
            FaultInjector faultInjector) {
        this.databasePath =
                Objects.requireNonNull(databasePath, "databasePath")
                        .toAbsolutePath()
                        .normalize();
        this.faultInjector =
                Objects.requireNonNull(faultInjector, "faultInjector");

        try {
            Path parent = this.databasePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + this.databasePath);
            configureConnection();
            initializeSchema();
            this.delegate = loadVerifiedStoreSnapshot();
        } catch (SQLException | IOException failure) {
            closeAfterFailedOpen();
            throw new IllegalStateException(
                    "failed to open workflow SQLite journal "
                            + this.databasePath,
                    failure);
        } catch (RuntimeException | Error failure) {
            closeAfterFailedOpen();
            throw failure;
        }
    }

    public Path databasePath() {
        return databasePath;
    }

    @Override
    public synchronized void append(
            DurableDevLoopV1.Transition transition) {
        Objects.requireNonNull(transition, "transition");
        mutate(store -> {
            store.append(transition);
            return null;
        });
    }

    @Override
    public synchronized Optional<WorkflowJournalV1.Claim> claimNext(
            String workerId,
            long nowMillis,
            long leaseMillis) {
        return mutate(store ->
                store.claimNext(workerId, nowMillis, leaseMillis));
    }

    @Override
    public synchronized void releaseUnexecuted(
            String effectKey,
            String claimToken,
            String reasonRef,
            long nowMillis) {
        mutate(store -> {
            store.releaseUnexecuted(
                    effectKey,
                    claimToken,
                    reasonRef,
                    nowMillis);
            return null;
        });
    }

    @Override
    public synchronized void complete(
            String effectKey,
            String claimToken,
            String receiptRef,
            long nowMillis) {
        mutate(store -> {
            store.complete(
                    effectKey,
                    claimToken,
                    receiptRef,
                    nowMillis);
            return null;
        });
    }

    @Override
    public synchronized DurableDevLoopV1.Transition completeAndAppend(
            String effectKey,
            String claimToken,
            String receiptRef,
            long nowMillis,
            String eventId,
            DurableDevLoopV1.Event event) {
        Objects.requireNonNull(event, "event");
        return mutate(store -> store.completeAndAppend(
                effectKey,
                claimToken,
                receiptRef,
                nowMillis,
                eventId,
                event));
    }

    @Override
    public synchronized int expireClaims(long nowMillis) {
        return mutate(store -> store.expireClaims(nowMillis));
    }

    @Override
    public synchronized void resolveAmbiguousAsCompleted(
            String effectKey,
            String receiptRef,
            String reconciliationRef) {
        mutate(store -> {
            store.resolveAmbiguousAsCompleted(
                    effectKey,
                    receiptRef,
                    reconciliationRef);
            return null;
        });
    }

    @Override
    public synchronized void resolveAmbiguousForRetry(
            String effectKey,
            String reconciliationRef) {
        mutate(store -> {
            store.resolveAmbiguousForRetry(
                    effectKey,
                    reconciliationRef);
            return null;
        });
    }

    @Override
    public synchronized List<DurableDevLoopV1.JournalEntry> journal() {
        refresh();
        return delegate.journal();
    }

    @Override
    public synchronized List<WorkflowJournalV1.EffectSnapshot> effects() {
        refresh();
        return delegate.effects();
    }

    @Override
    public synchronized Optional<WorkflowJournalV1.EffectSnapshot> effect(
            String effectKey) {
        refresh();
        return delegate.effect(effectKey);
    }

    @Override
    public synchronized WorkflowJournalV1.StoreSnapshot snapshot() {
        refresh();
        return delegate.snapshot();
    }

    @Override
    public synchronized DurableDevLoopV1.ReplayResult replayDevLoop() {
        refresh();
        return delegate.replayDevLoop();
    }

    @Override
    public synchronized WorkflowJournalStoreV1.ReadView readView() {
        refresh();
        return delegate.readView();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            connection.close();
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "failed to close workflow SQLite journal "
                            + databasePath,
                    failure);
        }
    }

    private <T> T mutate(Function<WorkflowJournalV1, T> operation) {
        requireOpen();
        boolean transactionOpen = false;
        boolean transactionResolved = false;
        try {
            execute("BEGIN IMMEDIATE");
            transactionOpen = true;
            faultInjector.hit(FaultPoint.AFTER_BEGIN);

            WorkflowJournalV1 candidate = loadVerifiedStore();
            WorkflowJournalV1.PersistentState before =
                    candidate.persistentState();

            final T result;
            try {
                result = operation.apply(candidate);
            } catch (RuntimeException | Error semanticFailure) {
                WorkflowJournalV1.PersistentState after =
                        candidate.persistentState();
                if (!after.equals(before)) {
                    try {
                        persistState(after);
                        faultInjector.hit(FaultPoint.BEFORE_COMMIT);
                        execute("COMMIT");
                        transactionOpen = false;
                        transactionResolved = true;
                        delegate = candidate;
                        try {
                            faultInjector.hit(FaultPoint.AFTER_COMMIT);
                        } catch (RuntimeException | Error postCommitFailure) {
                            semanticFailure.addSuppressed(
                                    postCommitFailure);
                        }
                    } catch (RuntimeException | Error
                            | SQLException persistenceFailure) {
                        if (transactionOpen && !transactionResolved) {
                            rollbackAndReloadOrInvalidate(
                                    persistenceFailure);
                            transactionOpen = false;
                            transactionResolved = true;
                        }
                        semanticFailure.addSuppressed(
                                persistenceFailure);
                    }
                } else if (transactionOpen && !transactionResolved) {
                    rollbackAndReloadOrInvalidate(semanticFailure);
                    transactionOpen = false;
                    transactionResolved = true;
                }
                throw semanticFailure;
            }

            persistState(candidate.persistentState());
            faultInjector.hit(FaultPoint.BEFORE_COMMIT);
            execute("COMMIT");
            transactionOpen = false;
            transactionResolved = true;
            delegate = candidate;

            faultInjector.hit(FaultPoint.AFTER_COMMIT);
            return result;
        } catch (RuntimeException | Error failure) {
            if (transactionOpen
                    && !transactionResolved
                    && connection != null) {
                rollbackAndReloadOrInvalidate(failure);
            }
            throw failure;
        } catch (SQLException failure) {
            IllegalStateException wrapped = new IllegalStateException(
                    "workflow SQLite mutation failed", failure);
            if (transactionOpen
                    && !transactionResolved
                    && connection != null) {
                rollbackAndReloadOrInvalidate(wrapped);
            }
            throw wrapped;
        }
    }

    private void refresh() {
        requireOpen();
        try {
            delegate = loadVerifiedStoreSnapshot();
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "failed to refresh workflow SQLite journal",
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

    private void configureConnection() throws SQLException {
        execute("PRAGMA foreign_keys=ON");
        execute("PRAGMA journal_mode=WAL");
        execute("PRAGMA synchronous=FULL");
        execute("PRAGMA busy_timeout=5000");
    }

    private void initializeSchema() throws SQLException {
        execute("""
                CREATE TABLE IF NOT EXISTS workflow_meta (
                    singleton_id INTEGER PRIMARY KEY CHECK(singleton_id = 1),
                    schema_version INTEGER NOT NULL,
                    run_id TEXT,
                    max_iterations INTEGER,
                    next_sequence INTEGER NOT NULL,
                    next_claim_number INTEGER NOT NULL,
                    last_observed_millis INTEGER NOT NULL
                )
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS workflow_journal (
                    sequence INTEGER PRIMARY KEY,
                    event_id TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    event_int1 INTEGER,
                    event_text1 TEXT,
                    event_text2 TEXT,
                    event_text3 TEXT,
                    event_text4 TEXT,
                    disposition TEXT NOT NULL
                )
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS workflow_effects (
                    ordinal INTEGER PRIMARY KEY,
                    effect_key TEXT NOT NULL UNIQUE,
                    command_type TEXT NOT NULL,
                    command_int1 INTEGER,
                    command_text1 TEXT,
                    command_text2 TEXT,
                    command_text3 TEXT,
                    command_text4 TEXT,
                    state TEXT NOT NULL,
                    attempts INTEGER NOT NULL,
                    worker_id TEXT,
                    claim_token TEXT,
                    lease_until_millis INTEGER NOT NULL,
                    receipt_ref TEXT,
                    reconciliation_ref TEXT,
                    retry_allowed INTEGER NOT NULL CHECK(retry_allowed IN (0, 1))
                )
                """);

        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO workflow_meta (
                    singleton_id,
                    schema_version,
                    run_id,
                    max_iterations,
                    next_sequence,
                    next_claim_number,
                    last_observed_millis
                ) VALUES (1, ?, NULL, NULL, 1, 1, -1)
                """)) {
            statement.setInt(1, SCHEMA_VERSION);
            statement.executeUpdate();
        }

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT schema_version FROM workflow_meta "
                             + "WHERE singleton_id = 1")) {
            if (!rows.next()) {
                throw new SQLException(
                        "workflow_meta singleton row is missing");
            }
            int actual = rows.getInt(1);
            if (actual != SCHEMA_VERSION) {
                throw new IllegalStateException(
                        "unsupported workflow SQLite schema version "
                                + actual
                                + "; expected "
                                + SCHEMA_VERSION);
            }
        }
    }

    private WorkflowJournalV1 loadVerifiedStoreSnapshot()
            throws SQLException {
        boolean transactionOpen = false;
        try {
            execute("BEGIN");
            transactionOpen = true;
            WorkflowJournalV1 loaded = loadVerifiedStore();
            execute("COMMIT");
            transactionOpen = false;
            return loaded;
        } catch (SQLException | RuntimeException | Error failure) {
            if (transactionOpen) {
                try {
                    execute("ROLLBACK");
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    private WorkflowJournalV1 loadVerifiedStore() throws SQLException {
        WorkflowJournalV1.PersistentState state = readPersistentState();
        return WorkflowJournalV1.restore(state);
    }

    private WorkflowJournalV1.PersistentState readPersistentState()
            throws SQLException {
        String runId;
        Integer maxIterations;
        long nextSequence;
        long nextClaimNumber;
        long lastObservedMillis;

        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT
                         schema_version,
                         run_id,
                         max_iterations,
                         next_sequence,
                         next_claim_number,
                         last_observed_millis
                     FROM workflow_meta
                     WHERE singleton_id = 1
                     """)) {
            if (!rows.next()) {
                throw new SQLException(
                        "workflow_meta singleton row is missing");
            }
            int version = rows.getInt("schema_version");
            if (version != SCHEMA_VERSION) {
                throw new IllegalStateException(
                        "unsupported workflow SQLite schema version "
                                + version);
            }
            runId = rows.getString("run_id");
            Number max = (Number) rows.getObject("max_iterations");
            maxIterations = max == null ? null : max.intValue();
            nextSequence = rows.getLong("next_sequence");
            nextClaimNumber = rows.getLong("next_claim_number");
            lastObservedMillis = rows.getLong("last_observed_millis");
        }

        List<DurableDevLoopV1.JournalEntry> journal =
                new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT
                         sequence,
                         event_id,
                         event_type,
                         event_int1,
                         event_text1,
                         event_text2,
                         event_text3,
                         event_text4,
                         disposition
                     FROM workflow_journal
                     ORDER BY sequence
                     """)) {
            while (rows.next()) {
                WorkflowSqlCodecV1.Encoded encoded =
                        new WorkflowSqlCodecV1.Encoded(
                                rows.getString("event_type"),
                                nullableInt(rows, "event_int1"),
                                rows.getString("event_text1"),
                                rows.getString("event_text2"),
                                rows.getString("event_text3"),
                                rows.getString("event_text4"));
                journal.add(new DurableDevLoopV1.JournalEntry(
                        rows.getLong("sequence"),
                        rows.getString("event_id"),
                        WorkflowSqlCodecV1.decodeEvent(encoded),
                        DurableDevLoopV1.Disposition.valueOf(
                                rows.getString("disposition"))));
            }
        }

        List<WorkflowJournalV1.EffectSnapshot> effects =
                new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT
                         effect_key,
                         command_type,
                         command_int1,
                         command_text1,
                         command_text2,
                         command_text3,
                         command_text4,
                         state,
                         attempts,
                         worker_id,
                         claim_token,
                         lease_until_millis,
                         receipt_ref,
                         reconciliation_ref,
                         retry_allowed
                     FROM workflow_effects
                     ORDER BY ordinal
                     """)) {
            while (rows.next()) {
                WorkflowSqlCodecV1.Encoded encoded =
                        new WorkflowSqlCodecV1.Encoded(
                                rows.getString("command_type"),
                                nullableInt(rows, "command_int1"),
                                rows.getString("command_text1"),
                                rows.getString("command_text2"),
                                rows.getString("command_text3"),
                                rows.getString("command_text4"));
                String effectKey = rows.getString("effect_key");
                DurableDevLoopV1.Command command =
                        WorkflowSqlCodecV1.decodeCommand(encoded);
                if (!effectKey.equals(command.effectKey())) {
                    throw new IllegalStateException(
                            "persisted effect key disagrees with command: "
                                    + effectKey);
                }
                effects.add(new WorkflowJournalV1.EffectSnapshot(
                        effectKey,
                        command,
                        WorkflowJournalV1.EffectState.valueOf(
                                rows.getString("state")),
                        rows.getInt("attempts"),
                        rows.getString("worker_id"),
                        rows.getString("claim_token"),
                        rows.getLong("lease_until_millis"),
                        rows.getString("receipt_ref"),
                        rows.getString("reconciliation_ref"),
                        rows.getInt("retry_allowed") != 0));
            }
        }

        return new WorkflowJournalV1.PersistentState(
                runId,
                maxIterations,
                nextSequence,
                nextClaimNumber,
                lastObservedMillis,
                journal,
                effects);
    }

    private void persistState(
            WorkflowJournalV1.PersistentState state)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                UPDATE workflow_meta
                SET
                    schema_version = ?,
                    run_id = ?,
                    max_iterations = ?,
                    next_sequence = ?,
                    next_claim_number = ?,
                    last_observed_millis = ?
                WHERE singleton_id = 1
                """)) {
            statement.setInt(1, SCHEMA_VERSION);
            setNullableString(statement, 2, state.runId());
            setNullableInteger(statement, 3, state.maxIterations());
            statement.setLong(4, state.nextSequence());
            statement.setLong(5, state.nextClaimNumber());
            statement.setLong(6, state.lastObservedMillis());
            if (statement.executeUpdate() != 1) {
                throw new SQLException(
                        "workflow_meta singleton update failed");
            }
        }
        faultInjector.hit(FaultPoint.AFTER_META_WRITE);

        execute("DELETE FROM workflow_journal");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO workflow_journal (
                    sequence,
                    event_id,
                    event_type,
                    event_int1,
                    event_text1,
                    event_text2,
                    event_text3,
                    event_text4,
                    disposition
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            for (DurableDevLoopV1.JournalEntry entry : state.journal()) {
                WorkflowSqlCodecV1.Encoded encoded =
                        WorkflowSqlCodecV1.encodeEvent(entry.event());
                statement.setLong(1, entry.sequence());
                statement.setString(2, entry.eventId());
                statement.setString(3, encoded.type());
                setNullableInteger(statement, 4, encoded.int1());
                setNullableString(statement, 5, encoded.text1());
                setNullableString(statement, 6, encoded.text2());
                setNullableString(statement, 7, encoded.text3());
                setNullableString(statement, 8, encoded.text4());
                statement.setString(9, entry.disposition().name());
                statement.addBatch();
            }
            statement.executeBatch();
        }
        faultInjector.hit(FaultPoint.AFTER_JOURNAL_WRITE);

        execute("DELETE FROM workflow_effects");
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO workflow_effects (
                    ordinal,
                    effect_key,
                    command_type,
                    command_int1,
                    command_text1,
                    command_text2,
                    command_text3,
                    command_text4,
                    state,
                    attempts,
                    worker_id,
                    claim_token,
                    lease_until_millis,
                    receipt_ref,
                    reconciliation_ref,
                    retry_allowed
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            int ordinal = 0;
            for (WorkflowJournalV1.EffectSnapshot effect
                    : state.effects()) {
                WorkflowSqlCodecV1.Encoded encoded =
                        WorkflowSqlCodecV1.encodeCommand(
                                effect.command());
                statement.setInt(1, ordinal++);
                statement.setString(2, effect.effectKey());
                statement.setString(3, encoded.type());
                setNullableInteger(statement, 4, encoded.int1());
                setNullableString(statement, 5, encoded.text1());
                setNullableString(statement, 6, encoded.text2());
                setNullableString(statement, 7, encoded.text3());
                setNullableString(statement, 8, encoded.text4());
                statement.setString(9, effect.state().name());
                statement.setInt(10, effect.attempts());
                setNullableString(statement, 11, effect.workerId());
                setNullableString(statement, 12, effect.claimToken());
                statement.setLong(13, effect.leaseUntilMillis());
                setNullableString(statement, 14, effect.receiptRef());
                setNullableString(
                        statement, 15, effect.reconciliationRef());
                statement.setInt(16, effect.retryAllowed() ? 1 : 0);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        faultInjector.hit(FaultPoint.AFTER_EFFECT_WRITE);
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void rollbackAndReloadOrInvalidate(
            Throwable originalFailure) {
        try {
            execute("ROLLBACK");
        } catch (SQLException rollbackFailure) {
            originalFailure.addSuppressed(rollbackFailure);
            invalidateConnection();
            return;
        }

        try {
            delegate = loadVerifiedStoreSnapshot();
        } catch (SQLException | RuntimeException reloadFailure) {
            originalFailure.addSuppressed(reloadFailure);
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
            // The original mutation/rollback failure remains authoritative.
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "workflow SQLite journal is closed");
        }
    }

    private static Integer nullableInt(
            ResultSet rows,
            String column) throws SQLException {
        Number value = (Number) rows.getObject(column);
        return value == null ? null : value.intValue();
    }

    private static void setNullableInteger(
            PreparedStatement statement,
            int index,
            Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setNullableString(
            PreparedStatement statement,
            int index,
            String value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
