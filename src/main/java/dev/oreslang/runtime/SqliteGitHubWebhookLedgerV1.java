package dev.oreslang.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SQLite authority for shared GitHub webhook routing state.
 *
 * <p>Registered CI expectations survive ingress-actor restarts. Delivery IDs
 * are bound permanently to the authenticated raw-payload digest: an exact
 * redelivery is safe to route again (the downstream workflow journal dedupes
 * the same delivery id), while reuse of an id for different signed bytes fails
 * closed.</p>
 */
public final class SqliteGitHubWebhookLedgerV1 implements AutoCloseable {

    public enum DeliveryDisposition {
        FIRST_SEEN,
        EXACT_REDELIVERY
    }

    private static final int SCHEMA_VERSION = 2;

    private final Path databasePath;
    private Connection connection;
    private boolean closed;

    public static SqliteGitHubWebhookLedgerV1 open(Path databasePath) {
        return new SqliteGitHubWebhookLedgerV1(databasePath);
    }

    private SqliteGitHubWebhookLedgerV1(Path databasePath) {
        this.databasePath = Objects.requireNonNull(
                databasePath, "databasePath")
                .toAbsolutePath()
                .normalize();
        try {
            Path parent = this.databasePath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            connection = DriverManager.getConnection(
                    "jdbc:sqlite:" + this.databasePath);
            configure();
            initializeSchema();
        } catch (Exception failure) {
            closeAfterFailedOpen();
            throw new IllegalStateException(
                    "failed to open GitHub webhook ledger "
                            + this.databasePath,
                    failure);
        }
    }

    public synchronized void register(
            GitHubCiCorrelationV1.Expectation expectation) {
        requireOpen();
        Objects.requireNonNull(expectation, "expectation");
        try {
            execute("BEGIN IMMEDIATE");
            GitHubCiCorrelationV1.Expectation existing =
                    findExpectation(expectation.runId());
            if (existing == null) {
                try (PreparedStatement statement =
                             connection.prepareStatement("""
                        INSERT INTO github_webhook_expectations (
                            run_id,
                            repository_full_name,
                            commit_sha,
                            tree_sha,
                            workflow_name
                        ) VALUES (?, ?, ?, ?, ?)
                        """)) {
                    statement.setString(1, expectation.runId());
                    statement.setString(
                            2, expectation.repositoryFullName());
                    statement.setString(3, expectation.commitSha());
                    statement.setString(4, expectation.treeSha());
                    statement.setString(
                            5, expectation.workflowName());
                    statement.executeUpdate();
                }
            } else if (!existing.equals(expectation)) {
                throw new IllegalStateException(
                        "run correlation already registered differently: "
                                + expectation.runId());
            }
            execute("COMMIT");
        } catch (RuntimeException failure) {
            rollbackOrInvalidate(failure);
            throw failure;
        } catch (SQLException failure) {
            IllegalStateException wrapped = new IllegalStateException(
                    "failed to register GitHub correlation", failure);
            rollbackOrInvalidate(wrapped);
            throw wrapped;
        }
    }

    public synchronized void unregister(String runId) {
        requireOpen();
        runId = requireText(runId, "runId");
        try (PreparedStatement statement =
                     connection.prepareStatement("""
                DELETE FROM github_webhook_expectations
                WHERE run_id = ?
                """)) {
            statement.setString(1, runId);
            statement.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "failed to unregister GitHub correlation", failure);
        }
    }

    public synchronized List<GitHubCiCorrelationV1.Expectation>
            expectations() {
        requireOpen();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("""
                     SELECT
                         run_id,
                         repository_full_name,
                         commit_sha,
                         tree_sha,
                         workflow_name
                     FROM github_webhook_expectations
                     ORDER BY run_id
                     """)) {
            List<GitHubCiCorrelationV1.Expectation> out =
                    new ArrayList<>();
            while (rows.next()) {
                out.add(new GitHubCiCorrelationV1.Expectation(
                        rows.getString("run_id"),
                        rows.getString("repository_full_name"),
                        rows.getString("commit_sha"),
                        rows.getString("tree_sha"),
                        rows.getString("workflow_name")));
            }
            return List.copyOf(out);
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "failed to read GitHub correlations", failure);
        }
    }

    public synchronized DeliveryDisposition recordDelivery(
            String deliveryId,
            String payloadSha256,
            String eventName) {
        return recordDelivery(
                deliveryId,
                payloadSha256,
                eventName,
                null,
                null,
                null);
    }

    public synchronized DeliveryDisposition recordDelivery(
            String deliveryId,
            String payloadSha256,
            String eventName,
            String proofCommitSha,
            String proofTreeSha,
            String proofReceiptRef) {
        requireOpen();
        deliveryId = requireText(deliveryId, "deliveryId");
        payloadSha256 = normalizeSha256(
                payloadSha256, "payloadSha256");
        eventName = requireText(eventName, "eventName");
        if ((proofCommitSha == null)
                != (proofTreeSha == null)
                || (proofCommitSha == null)
                != (proofReceiptRef == null)) {
            throw new IllegalArgumentException(
                    "tree proof identity must be entirely present or absent");
        }
        if (proofCommitSha != null) {
            proofCommitSha = normalizeGitObjectId(
                    proofCommitSha, "proofCommitSha");
            proofTreeSha = normalizeGitObjectId(
                    proofTreeSha, "proofTreeSha");
            proofReceiptRef = requireText(
                    proofReceiptRef, "proofReceiptRef");
        }
        try {
            execute("BEGIN IMMEDIATE");
            try (PreparedStatement statement =
                         connection.prepareStatement("""
                    SELECT
                        payload_sha256,
                        event_name,
                        proof_commit_sha,
                        proof_tree_sha,
                        proof_receipt_ref
                    FROM github_webhook_deliveries
                    WHERE delivery_id = ?
                    """)) {
                statement.setString(1, deliveryId);
                try (ResultSet row = statement.executeQuery()) {
                    if (row.next()) {
                        String priorDigest =
                                row.getString("payload_sha256");
                        String priorEvent =
                                row.getString("event_name");
                        String priorProofCommit =
                                row.getString("proof_commit_sha");
                        String priorProofTree =
                                row.getString("proof_tree_sha");
                        String priorProofReceipt =
                                row.getString("proof_receipt_ref");
                        if (!payloadSha256.equals(priorDigest)
                                || !eventName.equals(priorEvent)
                                || !Objects.equals(
                                        proofCommitSha,
                                        priorProofCommit)
                                || !Objects.equals(
                                        proofTreeSha,
                                        priorProofTree)
                                || !Objects.equals(
                                        proofReceiptRef,
                                        priorProofReceipt)) {
                            throw new SecurityException(
                                    "GitHub delivery id was reused with "
                                            + "different authenticated/proof "
                                            + "content");
                        }
                        execute("COMMIT");
                        return DeliveryDisposition.EXACT_REDELIVERY;
                    }
                }
            }

            try (PreparedStatement statement =
                         connection.prepareStatement("""
                    INSERT INTO github_webhook_deliveries (
                        delivery_id,
                        payload_sha256,
                        event_name,
                        proof_commit_sha,
                        proof_tree_sha,
                        proof_receipt_ref
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                statement.setString(1, deliveryId);
                statement.setString(2, payloadSha256);
                statement.setString(3, eventName);
                statement.setString(4, proofCommitSha);
                statement.setString(5, proofTreeSha);
                statement.setString(6, proofReceiptRef);
                statement.executeUpdate();
            }
            execute("COMMIT");
            return DeliveryDisposition.FIRST_SEEN;
        } catch (RuntimeException failure) {
            rollbackOrInvalidate(failure);
            throw failure;
        } catch (SQLException failure) {
            IllegalStateException wrapped = new IllegalStateException(
                    "failed to record GitHub delivery", failure);
            rollbackOrInvalidate(wrapped);
            throw wrapped;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        try {
            connection.close();
        } catch (SQLException failure) {
            throw new IllegalStateException(
                    "failed to close GitHub webhook ledger", failure);
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
                CREATE TABLE IF NOT EXISTS github_webhook_meta (
                    singleton_id INTEGER PRIMARY KEY
                        CHECK(singleton_id = 1),
                    schema_version INTEGER NOT NULL
                )
                """);
        try (PreparedStatement statement =
                     connection.prepareStatement("""
                INSERT OR IGNORE INTO github_webhook_meta (
                    singleton_id, schema_version
                ) VALUES (1, ?)
                """)) {
            statement.setInt(1, SCHEMA_VERSION);
            statement.executeUpdate();
        }
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                     SELECT schema_version
                     FROM github_webhook_meta
                     WHERE singleton_id = 1
                     """)) {
            if (!row.next()
                    || row.getInt("schema_version")
                            != SCHEMA_VERSION) {
                throw new IllegalStateException(
                        "unsupported GitHub webhook ledger schema");
            }
        }

        execute("""
                CREATE TABLE IF NOT EXISTS github_webhook_expectations (
                    run_id TEXT PRIMARY KEY,
                    repository_full_name TEXT NOT NULL,
                    commit_sha TEXT NOT NULL,
                    tree_sha TEXT,
                    workflow_name TEXT
                )
                """);
        execute("""
                CREATE INDEX IF NOT EXISTS
                    github_webhook_expectations_identity
                ON github_webhook_expectations (
                    repository_full_name,
                    commit_sha,
                    tree_sha,
                    workflow_name
                )
                """);
        execute("""
                CREATE TABLE IF NOT EXISTS github_webhook_deliveries (
                    delivery_id TEXT PRIMARY KEY,
                    payload_sha256 TEXT NOT NULL,
                    event_name TEXT NOT NULL,
                    proof_commit_sha TEXT,
                    proof_tree_sha TEXT,
                    proof_receipt_ref TEXT,
                    CHECK (
                        (proof_commit_sha IS NULL
                            AND proof_tree_sha IS NULL
                            AND proof_receipt_ref IS NULL)
                        OR
                        (proof_commit_sha IS NOT NULL
                            AND proof_tree_sha IS NOT NULL
                            AND proof_receipt_ref IS NOT NULL)
                    )
                )
                """);
    }

    private GitHubCiCorrelationV1.Expectation findExpectation(
            String runId) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("""
                SELECT
                    repository_full_name,
                    commit_sha,
                    tree_sha,
                    workflow_name
                FROM github_webhook_expectations
                WHERE run_id = ?
                """)) {
            statement.setString(1, runId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) return null;
                return new GitHubCiCorrelationV1.Expectation(
                        runId,
                        row.getString("repository_full_name"),
                        row.getString("commit_sha"),
                        row.getString("tree_sha"),
                        row.getString("workflow_name"));
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

    private void closeAfterFailedOpen() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Preserve the constructor failure.
        } finally {
            connection = null;
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "GitHub webhook ledger is closed");
        }
    }

    private static String normalizeGitObjectId(
            String value,
            String name) {
        String normalized = requireText(value, name)
                .toLowerCase(java.util.Locale.ROOT);
        if ((normalized.length() != 40
                && normalized.length() != 64)
                || !normalized.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException(
                    name + " must be an exact 40- or 64-hex Git object id");
        }
        return normalized;
    }

    private static String normalizeSha256(
            String value,
            String name) {
        String normalized = requireText(value, name)
                .toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    name + " must be a 64-hex SHA-256 digest");
        }
        return normalized;
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
