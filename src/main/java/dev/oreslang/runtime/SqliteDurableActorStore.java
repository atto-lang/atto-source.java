package dev.oreslang.runtime;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * SQLite adapter for {@link DurableActorStore}. One instance serializes access to
 * its JDBC connection; BEGIN IMMEDIATE + stored activation epochs fence writers
 * across independent connections/processes. This is a storage port, not actor
 * scheduling or authorization: trusted supervision must bind each Address to
 * the actor and verify schema/code versions before executing guest handlers.
 *
 * SQLite JDBC is supplied by the host, not by core. In particular, this class
 * does not make arbitrary filesystem access available to guest actors.
 */
public final class SqliteDurableActorStore implements DurableActorStore, AutoCloseable {
    private static final int MAX_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private final Connection db;
    private final boolean ownsConnection;

    /** Accept an already-open SQLite connection; the caller retains ownership. */
    public SqliteDurableActorStore(Connection db) {
        this(db, false);
    }

    private SqliteDurableActorStore(Connection db, boolean ownsConnection) {
        this.db = Objects.requireNonNull(db, "db");
        this.ownsConnection = ownsConnection;
        try {
            if (!db.getAutoCommit()) {
                throw new IllegalArgumentException("SQLite connection must use auto-commit");
            }
            try (Statement s = db.createStatement()) {
                s.execute("PRAGMA busy_timeout=5000");
                s.execute("PRAGMA foreign_keys=ON");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS durable_actors (
                      tenant TEXT NOT NULL, workflow TEXT NOT NULL,
                      run TEXT NOT NULL, node TEXT NOT NULL,
                      epoch INTEGER NOT NULL DEFAULT 0 CHECK(epoch >= 0),
                      active INTEGER NOT NULL DEFAULT 0 CHECK(active IN (0,1)),
                      format_version INTEGER NOT NULL,
                      schema_version INTEGER NOT NULL,
                      code_generation TEXT NOT NULL,
                      state_version INTEGER NOT NULL CHECK(state_version >= 0),
                      state BLOB NOT NULL, state_sha256 TEXT NOT NULL,
                      PRIMARY KEY(tenant, workflow, run, node)
                    )""");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS durable_inbox (
                      sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                      tenant TEXT NOT NULL, workflow TEXT NOT NULL,
                      run TEXT NOT NULL, node TEXT NOT NULL,
                      delivery_id TEXT NOT NULL, payload BLOB NOT NULL,
                      consumed INTEGER NOT NULL DEFAULT 0 CHECK(consumed IN (0,1)),
                      UNIQUE(tenant,workflow,run,node,delivery_id),
                      FOREIGN KEY(tenant,workflow,run,node)
                        REFERENCES durable_actors(tenant,workflow,run,node)
                    )""");
                s.execute("""
                    CREATE TABLE IF NOT EXISTS durable_outbox (
                      sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                      tenant TEXT NOT NULL, workflow TEXT NOT NULL,
                      run TEXT NOT NULL, node TEXT NOT NULL,
                      intent_id TEXT NOT NULL, idempotency_key TEXT NOT NULL,
                      payload BLOB NOT NULL, receipt_id TEXT,
                      UNIQUE(tenant,workflow,run,node,intent_id),
                      UNIQUE(tenant,workflow,run,node,idempotency_key),
                      FOREIGN KEY(tenant,workflow,run,node)
                        REFERENCES durable_actors(tenant,workflow,run,node)
                    )""");
                // Old store files gain this additive table without losing
                // inbox/state/outbox data. Receipt writes share commitTurn.
                s.execute("""
                    CREATE TABLE IF NOT EXISTS durable_command_receipts (
                      tenant TEXT NOT NULL, workflow TEXT NOT NULL,
                      run TEXT NOT NULL, node TEXT NOT NULL,
                      delivery_id TEXT NOT NULL, original_sha256 TEXT NOT NULL,
                      format_version INTEGER NOT NULL, schema_version INTEGER NOT NULL,
                      code_generation TEXT NOT NULL,
                      state_version INTEGER NOT NULL CHECK(state_version > 0),
                      result BLOB NOT NULL, result_sha256 TEXT NOT NULL,
                      PRIMARY KEY(tenant,workflow,run,node,delivery_id),
                      FOREIGN KEY(tenant,workflow,run,node,delivery_id)
                        REFERENCES durable_inbox(tenant,workflow,run,node,delivery_id)
                    )""");
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot initialize SQLite durable actor schema", failure);
        }
    }

    /** Host-only convenience; requires a SQLite JDBC driver on the classpath. */
    public static SqliteDurableActorStore open(Path file) throws SQLException {
        Objects.requireNonNull(file, "file");
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
        try {
            try (Statement s = c.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA synchronous=FULL");
            }
            return new SqliteDurableActorStore(c, true);
        } catch (RuntimeException | SQLException failure) {
            try { c.close(); } catch (SQLException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    /**
     * Trusted supervisor provisions the initial encoded state before activation.
     * Never overwrites previously committed state, including after a crash.
     */
    public synchronized boolean bootstrap(Address a, Snapshot initial) {
        Objects.requireNonNull(a, "address");
        Objects.requireNonNull(initial, "initial");
        if (initial.stateVersion() != 0) {
            throw new IllegalArgumentException("bootstrap state version must be zero");
        }
        checkBytes(initial.encodedState());
        return transaction(() -> {
            try (PreparedStatement q = db.prepareStatement("""
                    INSERT OR IGNORE INTO durable_actors
                    (tenant,workflow,run,node,format_version,schema_version,
                     code_generation,state_version,state,state_sha256)
                    VALUES (?,?,?,?,?,?,?,?,?,?)""")) {
                bind(q, a);
                q.setInt(5, initial.formatVersion());
                q.setInt(6, initial.stateSchemaVersion());
                q.setString(7, initial.codeGeneration());
                q.setLong(8, 0);
                q.setBytes(9, initial.encodedState());
                q.setString(10, digest(initial.encodedState()));
                return q.executeUpdate() == 1;
            }
        });
    }

    @Override
    public synchronized Activation activate(Address a) {
        Objects.requireNonNull(a, "address");
        return transaction(() -> {
            try (PreparedStatement u = db.prepareStatement("""
                    UPDATE durable_actors SET epoch=epoch+1, active=1
                    WHERE tenant=? AND workflow=? AND run=? AND node=?
                    AND epoch < 9223372036854775807""")) {
                bind(u, a);
                if (u.executeUpdate() != 1) {
                    throw new IllegalStateException("actor not provisioned or epoch exhausted");
                }
            }
            long epoch;
            Snapshot snapshot;
            try (PreparedStatement q = db.prepareStatement("""
                    SELECT epoch,format_version,schema_version,code_generation,
                           state_version,state,state_sha256
                    FROM durable_actors WHERE tenant=? AND workflow=? AND run=? AND node=?""")) {
                bind(q, a);
                try (ResultSet r = q.executeQuery()) {
                    if (!r.next()) throw new IllegalStateException("missing actor after activation");
                    byte[] bytes = r.getBytes(6);
                    if (!digest(bytes).equals(r.getString(7))) {
                        throw new IllegalStateException("persisted state checksum mismatch");
                    }
                    epoch = r.getLong(1);
                    snapshot = new Snapshot(r.getInt(2), r.getInt(3),
                            r.getString(4), r.getLong(5), bytes);
                }
            }
            List<Inbound> inbox = new ArrayList<>();
            try (PreparedStatement q = db.prepareStatement("""
                    SELECT delivery_id,payload FROM durable_inbox
                    WHERE tenant=? AND workflow=? AND run=? AND node=? AND consumed=0
                    ORDER BY sequence""")) {
                bind(q, a);
                try (ResultSet r = q.executeQuery()) {
                    while (r.next()) inbox.add(new Inbound(r.getString(1), r.getBytes(2)));
                }
            }
            List<Outbound> outbox = new ArrayList<>();
            try (PreparedStatement q = db.prepareStatement("""
                    SELECT intent_id,idempotency_key,payload FROM durable_outbox
                    WHERE tenant=? AND workflow=? AND run=? AND node=?
                      AND receipt_id IS NULL ORDER BY sequence""")) {
                bind(q, a);
                try (ResultSet r = q.executeQuery()) {
                    while (r.next()) outbox.add(new Outbound(
                            r.getString(1), r.getString(2), r.getBytes(3)));
                }
            }
            return new Activation(epoch, snapshot, inbox, outbox);
        });
    }

    @Override
    public synchronized boolean enqueue(Address a, Inbound message) {
        Objects.requireNonNull(a, "address");
        Objects.requireNonNull(message, "message");
        checkBytes(message.payload());
        return transaction(() -> insertInbound(a, message));
    }

    /**
     * Fenced actor-origin admission. Old epochs MUST fail before the
     * idempotency lookup, even for an identical already-consumed delivery.
     */
    @Override
    public synchronized boolean enqueueFenced(Address a, long epoch, Inbound message) {
        Objects.requireNonNull(a, "address");
        Objects.requireNonNull(message, "message");
        if (epoch <= 0) throw new IllegalArgumentException("invalid activation epoch");
        checkBytes(message.payload());
        return transaction(() -> {
            requireActiveRow(a, epoch, null);
            return insertInbound(a, message);
        });
    }

    /**
     * The same delivery ID MUST bind the same original bytes for the entire
     * retention period, regardless of whether its turn was already consumed.
     * Conflict resolution runs inside the caller's BEGIN IMMEDIATE transaction.
     */
    private boolean insertInbound(Address a, Inbound message) throws SQLException {
        byte[] incoming = message.payload();
        try (PreparedStatement q = db.prepareStatement("""
                INSERT INTO durable_inbox
                (tenant,workflow,run,node,delivery_id,payload)
                VALUES (?,?,?,?,?,?)
                ON CONFLICT(tenant,workflow,run,node,delivery_id) DO NOTHING""")) {
            bind(q, a);
            q.setString(5, message.deliveryId());
            q.setBytes(6, incoming);
            int changed = q.executeUpdate();
            if (changed == 1) return true;
            if (changed != 0) throw new IllegalStateException("invalid inbox insertion count");
        }
        try (PreparedStatement q = db.prepareStatement("""
                SELECT payload FROM durable_inbox
                WHERE tenant=? AND workflow=? AND run=? AND node=? AND delivery_id=?""")) {
            bind(q, a);
            q.setString(5, message.deliveryId());
            try (ResultSet existing = q.executeQuery()) {
                if (!existing.next())
                    throw new IllegalStateException("missing duplicate inbox record");
                if (!MessageDigest.isEqual(incoming, existing.getBytes(1)))
                    throw new IllegalStateException("DURABLE_DELIVERY_ID_PAYLOAD_CONFLICT");
            }
        }
        return false;
    }

    /**
     * A current-epoch read can linearize at the SQLite SELECT. The caller
     * must keep its own actor turn / local state lease until it copies the
     * checked value; this method does not expose mutable state.
     */
    @Override
    public synchronized void requireActive(Address a, long epoch, long version) {
        Objects.requireNonNull(a, "address");
        if (epoch <= 0 || version < 0)
            throw new IllegalArgumentException("invalid activation read fence");
        try {
            requireActiveRow(a, epoch, version);
        } catch (SQLException failure) {
            throw new IllegalStateException("durable activation read fence failed", failure);
        }
    }

    /** Caller owns the SQLite connection monitor; a transaction is optional. */
    private void requireActiveRow(Address a, long epoch, Long expectedVersion)
            throws SQLException {
        try (PreparedStatement q = db.prepareStatement("""
                SELECT epoch,active,state_version FROM durable_actors
                WHERE tenant=? AND workflow=? AND run=? AND node=?""")) {
            bind(q, a);
            try (ResultSet r = q.executeQuery()) {
                if (!r.next() || r.getLong(1) != epoch || r.getInt(2) != 1
                        || (expectedVersion != null
                            && r.getLong(3) != expectedVersion)) {
                    throw new IllegalStateException(
                            "stale or inactive durable actor activation");
                }
            }
        }
    }

    @Override
    public synchronized long commitTurn(Turn turn) {
        Objects.requireNonNull(turn, "turn");
        Snapshot next = turn.nextSnapshot();
        checkBytes(next.encodedState());
        for (Outbound outbound : turn.outgoing()) checkBytes(outbound.payload());
        return transaction(() -> {
            Address a = turn.address();
            try (PreparedStatement q = db.prepareStatement("""
                    SELECT epoch,active,format_version,schema_version,
                           code_generation,state_version FROM durable_actors
                    WHERE tenant=? AND workflow=? AND run=? AND node=?""")) {
                bind(q, a);
                try (ResultSet r = q.executeQuery()) {
                    if (!r.next() || r.getLong(1) != turn.epoch() || r.getInt(2) != 1
                            || r.getLong(6) != turn.expectedStateVersion()) {
                        throw new IllegalStateException("stale activation or state version");
                    }
                    if (r.getInt(3) != next.formatVersion()
                            || r.getInt(4) != next.stateSchemaVersion()
                            || !r.getString(5).equals(next.codeGeneration())) {
                        throw new IllegalStateException("snapshot migration required");
                    }
                }
            }
            // The earliest unconsumed delivery defines per-actor admission
            // order. A stale or racing sender cannot leapfrog an older message
            // simply by supplying its own known delivery ID.
            try (PreparedStatement oldest = db.prepareStatement("""
                    SELECT delivery_id FROM durable_inbox
                    WHERE tenant=? AND workflow=? AND run=? AND node=?
                      AND consumed=0 ORDER BY sequence LIMIT 1""")) {
                bind(oldest, a);
                try (ResultSet pending = oldest.executeQuery()) {
                    if (!pending.next()
                            || !turn.consumedDeliveryId().equals(pending.getString(1)))
                        throw new IllegalStateException("out-of-order or missing durable delivery");
                }
            }
            try (PreparedStatement u = db.prepareStatement("""
                    UPDATE durable_inbox SET consumed=1
                    WHERE tenant=? AND workflow=? AND run=? AND node=?
                      AND delivery_id=? AND consumed=0""")) {
                bind(u, a);
                u.setString(5, turn.consumedDeliveryId());
                if (u.executeUpdate() != 1) {
                    throw new IllegalStateException("delivery not pending");
                }
            }
            try (PreparedStatement u = db.prepareStatement("""
                    UPDATE durable_actors SET state_version=?,state=?,state_sha256=?
                    WHERE tenant=? AND workflow=? AND run=? AND node=?
                      AND epoch=? AND active=1 AND state_version=?""")) {
                u.setLong(1, next.stateVersion());
                u.setBytes(2, next.encodedState());
                u.setString(3, digest(next.encodedState()));
                bind(u, 4, a);
                u.setLong(8, turn.epoch());
                u.setLong(9, turn.expectedStateVersion());
                if (u.executeUpdate() != 1) {
                    throw new IllegalStateException("concurrent actor writer");
                }
            }
            for (Outbound outbound : turn.outgoing()) {
                try (PreparedStatement u = db.prepareStatement("""
                        INSERT INTO durable_outbox
                        (tenant,workflow,run,node,intent_id,idempotency_key,payload)
                        VALUES (?,?,?,?,?,?,?)""")) {
                    bind(u, a);
                    u.setString(5, outbound.intentId());
                    u.setString(6, outbound.idempotencyKey());
                    u.setBytes(7, outbound.payload());
                    u.executeUpdate(); // unique IDs/keys fail the whole turn
                }
            }

            // Commit the original command result with inbox consumption, state
            // CAS and outbox insertion. Never synthesize it after the commit.
            byte[] originalPayload;
            try (PreparedStatement q = db.prepareStatement("""
                    SELECT payload FROM durable_inbox
                    WHERE tenant=? AND workflow=? AND run=? AND node=?
                      AND delivery_id=? AND consumed=1""")) {
                bind(q, a);
                q.setString(5, turn.consumedDeliveryId());
                try (ResultSet r = q.executeQuery()) {
                    if (!r.next()) throw new IllegalStateException("missing committed input");
                    originalPayload = r.getBytes(1);
                }
            }
            try (PreparedStatement u = db.prepareStatement("""
                    INSERT INTO durable_command_receipts
                    (tenant,workflow,run,node,delivery_id,original_sha256,
                     format_version,schema_version,code_generation,
                     state_version,result,result_sha256)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""")) {
                bind(u, a);
                u.setString(5, turn.consumedDeliveryId());
                u.setString(6, digest(originalPayload));
                u.setInt(7, next.formatVersion());
                u.setInt(8, next.stateSchemaVersion());
                u.setString(9, next.codeGeneration());
                u.setLong(10, next.stateVersion());
                u.setBytes(11, next.encodedState());
                u.setString(12, digest(next.encodedState()));
                u.executeUpdate();
            }
            return next.stateVersion();
        });
    }

    /**
     * A duplicate result is read only under a fresh current-writer epoch
     * fence; a stale caller cannot obtain even an old cached receipt.
     */
    @Override
    public synchronized Optional<Snapshot> committedDelivery(
            Address a, long epoch, Inbound original) {
        Objects.requireNonNull(a, "address");
        Objects.requireNonNull(original, "original");
        if (epoch <= 0) throw new IllegalArgumentException("invalid activation epoch");
        byte[] incoming = original.payload();
        checkBytes(incoming);
        return transaction(() -> {
            requireActiveRow(a, epoch, null);
            try (PreparedStatement q = db.prepareStatement("""
                    SELECT payload FROM durable_inbox
                    WHERE tenant=? AND workflow=? AND run=? AND node=?
                      AND delivery_id=?""")) {
                bind(q, a);
                q.setString(5, original.deliveryId());
                try (ResultSet r = q.executeQuery()) {
                    if (!r.next()) return Optional.empty();
                    if (!MessageDigest.isEqual(incoming, r.getBytes(1)))
                        throw new IllegalStateException("DURABLE_DELIVERY_ID_PAYLOAD_CONFLICT");
                }
            }
            try (PreparedStatement q = db.prepareStatement("""
                    SELECT original_sha256,format_version,schema_version,
                           code_generation,state_version,result,result_sha256
                    FROM durable_command_receipts
                    WHERE tenant=? AND workflow=? AND run=? AND node=?
                      AND delivery_id=?""")) {
                bind(q, a);
                q.setString(5, original.deliveryId());
                try (ResultSet r = q.executeQuery()) {
                    if (!r.next()) return Optional.empty(); // admitted, not committed
                    byte[] result = r.getBytes(6);
                    if (!digest(incoming).equals(r.getString(1))
                            || !digest(result).equals(r.getString(7)))
                        throw new IllegalStateException("durable command receipt checksum mismatch");
                    return Optional.of(new Snapshot(r.getInt(2), r.getInt(3),
                            r.getString(4), r.getLong(5), result));
                }
            }
        });
    }

    @Override
    public synchronized void hibernate(Address a, long epoch) {
        Objects.requireNonNull(a, "address");
        transaction(() -> {
            try (PreparedStatement q = db.prepareStatement("""
                    UPDATE durable_actors SET active=0
                    WHERE tenant=? AND workflow=? AND run=? AND node=? AND epoch=?""")) {
                bind(q, a);
                q.setLong(5, epoch);
                if (q.executeUpdate() != 1) {
                    throw new IllegalStateException("stale hibernate epoch");
                }
            }
            return null;
        });
    }

    @Override
    public synchronized boolean acknowledge(Address a, long epoch, String intentId, String receiptId) {
        Objects.requireNonNull(a, "address");
        requireText(intentId, "intentId");
        requireText(receiptId, "receiptId");
        return transaction(() -> {
            try (PreparedStatement q = db.prepareStatement("""
                    SELECT active,epoch FROM durable_actors
                    WHERE tenant=? AND workflow=? AND run=? AND node=?""")) {
                bind(q, a);
                try (ResultSet r = q.executeQuery()) {
                    if (!r.next() || r.getInt(1) != 1 || r.getLong(2) != epoch) return false;
                }
            }
            try (PreparedStatement q = db.prepareStatement("""
                    SELECT receipt_id FROM durable_outbox
                    WHERE tenant=? AND workflow=? AND run=? AND node=? AND intent_id=?""")) {
                bind(q, a);
                q.setString(5, intentId);
                try (ResultSet r = q.executeQuery()) {
                    if (!r.next()) return false;
                    if (r.getString(1) != null) return receiptId.equals(r.getString(1));
                }
            }
            try (PreparedStatement q = db.prepareStatement("""
                    UPDATE durable_outbox SET receipt_id=?
                    WHERE tenant=? AND workflow=? AND run=? AND node=?
                      AND intent_id=? AND receipt_id IS NULL""")) {
                q.setString(1, receiptId);
                bind(q, 2, a);
                q.setString(6, intentId);
                return q.executeUpdate() == 1;
            }
        });
    }

    private interface Operation<T> { T run() throws SQLException; }

    private <T> T transaction(Operation<T> operation) {
        try (Statement begin = db.createStatement()) {
            begin.execute("BEGIN IMMEDIATE");
            try {
                T value = operation.run();
                try (Statement commit = db.createStatement()) { commit.execute("COMMIT"); }
                return value;
            } catch (SQLException | RuntimeException error) {
                try (Statement rollback = db.createStatement()) {
                    rollback.execute("ROLLBACK");
                } catch (SQLException rollbackFailure) {
                    error.addSuppressed(rollbackFailure);
                }
                if (error instanceof RuntimeException runtime) throw runtime;
                throw new IllegalStateException("SQLite actor transaction failed", error);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("cannot acquire SQLite actor writer", failure);
        }
    }

    private static void bind(PreparedStatement q, Address a) throws SQLException { bind(q, 1, a); }

    private static void bind(PreparedStatement q, int offset, Address a) throws SQLException {
        q.setString(offset, a.tenantId());
        q.setString(offset + 1, a.workflowId());
        q.setString(offset + 2, a.runId());
        q.setString(offset + 3, a.nodeId());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is blank");
    }

    private static void checkBytes(byte[] bytes) {
        if (bytes.length > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("durable actor payload exceeds 16 MiB");
        }
    }

    private static String digest(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    @Override
    public synchronized void close() throws SQLException {
        if (ownsConnection) db.close();
    }
}
