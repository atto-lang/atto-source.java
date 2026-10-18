package dev.oreslang.runtime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * PostgreSQL v0 adapter for {@link DurableActorStore}; optional, not connected to
 * the actor scheduler. Install docs/runtime/sql/durable_actor_v0.sql first.
 *
 * Every actor turn is a database transaction. External HTTP/Git/AI actions MUST
 * be dispatched from the persisted outbox with reconciliation on retry.
 */
public final class PostgresDurableActorStore implements DurableActorStore {
    private static final String KEY = "tenant_id=? AND workflow_id=? AND run_id=? AND node_id=?";
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;
    private static final long MAX_PENDING_BYTES = 32L * 1024L * 1024L;
    private static final int DEFAULT_PENDING_LIMIT = 256;
    private final DataSource source;
    private final String leaseOwner = UUID.randomUUID().toString();
    private static final int LEASE_SECONDS = 60;
    private final int maxPendingInbox;
    private final int maxPendingOutbox;

    public PostgresDurableActorStore(DataSource source) {
        this(source, DEFAULT_PENDING_LIMIT, DEFAULT_PENDING_LIMIT);
    }

    /** Package-private for deterministic quota tests; no bypass through negative quotas. */
    PostgresDurableActorStore(DataSource source, int maxPendingInbox, int maxPendingOutbox) {
        this.source = Objects.requireNonNull(source, "source");
        if (maxPendingInbox < 1 || maxPendingInbox > DEFAULT_PENDING_LIMIT ||
            maxPendingOutbox < 1 || maxPendingOutbox > DEFAULT_PENDING_LIMIT) {
            throw new IllegalArgumentException("pending quotas must be within 1..256");
        }
        this.maxPendingInbox = maxPendingInbox;
        this.maxPendingOutbox = maxPendingOutbox;
    }

    /** Check activation ownership inside the caller's open transaction. */
    void requireOwnedActivation(Connection connection, Address address, long epoch)
            throws SQLException {
        Objects.requireNonNull(connection);
        Objects.requireNonNull(address);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM ores_durable_actor WHERE " + KEY +
                " AND active=true AND activation_epoch=? AND lease_owner=?" +
                " AND lease_expires_at>clock_timestamp() FOR UPDATE")) {
            bind(statement, address);
            statement.setLong(5, epoch);
            statement.setString(6, leaseOwner);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new StaleActivationException("activation not owned");
            }
        }
    }

    public static final class StaleActivationException extends IllegalStateException {
        public StaleActivationException(String message) { super(message); }
    }

    @FunctionalInterface
    private interface Transaction<T> {
        T execute(Connection connection) throws SQLException;
    }

    private <T> T transact(Transaction<T> body) {
        try (Connection c = source.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result = body.execute(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException error) {
                try { c.rollback(); } catch (SQLException suppressed) { error.addSuppressed(suppressed); }
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("durable actor transaction failed", error);
        }
    }

    private static void bind(PreparedStatement p, Address key) throws SQLException {
        p.setString(1, key.tenantId());
        p.setString(2, key.workflowId());
        p.setString(3, key.runId());
        p.setString(4, key.nodeId());
    }

    private static void bindPayload(PreparedStatement p, int position, byte[] bytes)
            throws SQLException {
        if (bytes.length > MAX_PAYLOAD) throw new IllegalArgumentException("payload too large");
        p.setBytes(position, bytes);
    }

    /** Initialize one logical actor explicitly. No guest or network callbacks. */
    public boolean initialize(Address key, Snapshot initial) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(initial);
        if (initial.stateVersion() != 0) throw new IllegalArgumentException("initial version must be zero");
        return transact(c -> {
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO ores_durable_actor (tenant_id,workflow_id,run_id,node_id," +
                    "format_version,state_schema_version,code_generation,state_version,encoded_state)" +
                    " VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
                bind(p, key);
                p.setInt(5, initial.formatVersion());
                p.setInt(6, initial.stateSchemaVersion());
                p.setString(7, initial.codeGeneration());
                p.setLong(8, initial.stateVersion());
                bindPayload(p, 9, initial.encodedState());
                return p.executeUpdate() == 1;
            }
        });
    }

    @Override
    public Activation activate(Address key) {
        Objects.requireNonNull(key);
        return transact(c -> {
            Snapshot snap;
            long current;
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT activation_epoch,state_version,format_version,state_schema_version," +
                    "code_generation,encoded_state,active," +
                    "(lease_expires_at IS NOT NULL AND lease_expires_at < clock_timestamp())" +
                    " FROM ores_durable_actor WHERE " + KEY + " FOR UPDATE")) {
                bind(p, key);
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) throw new IllegalStateException("unknown actor");
                    current = rs.getLong(1);
                    if (current == Long.MAX_VALUE) throw new IllegalStateException("activation epoch exhausted");
                    if (rs.getBoolean(7) && !rs.getBoolean(8))
                        throw new StaleActivationException("live or unverified activation lease");
                    snap = new Snapshot(rs.getInt(3), rs.getInt(4), rs.getString(5),
                            rs.getLong(2), rs.getBytes(6));
                }
            }
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE ores_durable_actor SET activation_epoch=?,active=true," +
                    "lease_owner=?,lease_expires_at=clock_timestamp()+make_interval(secs => ?)" +
                    " WHERE " + KEY)) {
                p.setLong(1, current + 1);
                p.setString(2, leaseOwner);
                p.setInt(3, LEASE_SECONDS);
                bindShifted(p, 4, key);
                if (p.executeUpdate() != 1) throw new IllegalStateException("actor vanished");
            }
            Pending pendingInbox = pendingCount(c, "ores_durable_inbox", key);
            Pending pendingOutbox = pendingCount(c, "ores_durable_outbox", key);
            if (pendingInbox.count() > maxPendingInbox ||
                pendingOutbox.count() > maxPendingOutbox) {
                throw new IllegalStateException("pending queue exceeds actor quota");
            }
            List<Inbound> inbox = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT delivery_id,payload FROM ores_durable_inbox WHERE " + KEY +
                    " AND NOT consumed ORDER BY sequence_number")) {
                bind(p, key);
                try (ResultSet rs = p.executeQuery()) {
                    while (rs.next()) inbox.add(new Inbound(rs.getString(1), rs.getBytes(2)));
                }
            }
            List<Outbound> outbox = new ArrayList<>();
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT intent_id,idempotency_key,payload FROM ores_durable_outbox WHERE " + KEY +
                    " AND receipt_id IS NULL ORDER BY intent_id")) {
                bind(p, key);
                try (ResultSet rs = p.executeQuery()) {
                    while (rs.next()) outbox.add(new Outbound(rs.getString(1), rs.getString(2), rs.getBytes(3)));
                }
            }
            return new Activation(current + 1, snap, inbox, outbox);
        });
    }

    private record Pending(long count, long bytes) { }

    /** Requires a previously locked actor row so concurrent admissions cannot overrun quotas. */
    private static Pending pendingCount(Connection c, String table, Address key)
            throws SQLException {
        // Internal fixed table names ONLY; no user-provided SQL identifiers.
        if (!"ores_durable_inbox".equals(table) && !"ores_durable_outbox".equals(table)) {
            throw new IllegalArgumentException("unrecognized durable queue");
        }
        String filter = "ores_durable_inbox".equals(table) ? "NOT consumed" : "receipt_id IS NULL";
        try (PreparedStatement p = c.prepareStatement(
                "SELECT COUNT(*),COALESCE(SUM(OCTET_LENGTH(payload)),0) FROM " + table +
                " WHERE " + KEY + " AND " + filter)) {
            bind(p, key);
            try (ResultSet rs = p.executeQuery()) {
                rs.next();
                long count = rs.getLong(1);
                long bytes = rs.getLong(2);
                if (count < 0 || bytes < 0 || bytes > MAX_PENDING_BYTES) {
                    throw new IllegalStateException("persisted queue exceeds admission budget");
                }
                return new Pending(count, bytes);
            }
        }
    }

    private static void bindShifted(PreparedStatement p, int at, Address key) throws SQLException {
        p.setString(at, key.tenantId()); p.setString(at + 1, key.workflowId());
        p.setString(at + 2, key.runId()); p.setString(at + 3, key.nodeId());
    }

    @Override
    public boolean enqueue(Address key, Inbound message) {
        Objects.requireNonNull(key); Objects.requireNonNull(message);
        return transact(c -> {
            // Serialize quota admission with activate, commit and hibernate.
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT 1 FROM ores_durable_actor WHERE " + KEY + " FOR UPDATE")) {
                bind(p, key);
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) throw new IllegalStateException("unknown actor");
                }
            }
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT 1 FROM ores_durable_inbox WHERE " + KEY + " AND delivery_id=?")) {
                bind(p, key);
                p.setString(5, message.deliveryId());
                try (ResultSet rs = p.executeQuery()) {
                    if (rs.next()) return false; // idempotent even when full
                }
            }
            Pending pending = pendingCount(c, "ores_durable_inbox", key);
            if (pending.count() >= maxPendingInbox ||
                pending.bytes() + message.payload().length > MAX_PENDING_BYTES) {
                throw new IllegalStateException("durable inbox admission quota exhausted");
            }
            try (PreparedStatement p = c.prepareStatement(
                    "INSERT INTO ores_durable_inbox (tenant_id,workflow_id,run_id,node_id," +
                    "delivery_id,payload) VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
                bind(p, key);
                p.setString(5, message.deliveryId());
                bindPayload(p, 6, message.payload());
                return p.executeUpdate() == 1;
            }
        });
    }

    @Override
    public long commitTurn(Turn turn) {
        Objects.requireNonNull(turn);
        return transact(c -> {
            Address key = turn.address();
            // Lock the actor before checking its epoch. Activate and hibernate use the same lock.
            Snapshot prior;
            long activeEpoch;
            boolean active;
            String currentOwner;
            boolean unexpired;
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT activation_epoch,active,state_version,format_version,state_schema_version," +
                    "code_generation,encoded_state,lease_owner," +
                    "(lease_expires_at IS NOT NULL AND lease_expires_at > clock_timestamp())" +
                    " FROM ores_durable_actor WHERE " + KEY + " FOR UPDATE")) {
                bind(p, key);
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) throw new StaleActivationException("unknown actor");
                    activeEpoch = rs.getLong(1);
                    active = rs.getBoolean(2);
                    currentOwner = rs.getString(8);
                    unexpired = rs.getBoolean(9);
                    prior = new Snapshot(rs.getInt(4), rs.getInt(5), rs.getString(6),
                            rs.getLong(3), rs.getBytes(7));
                }
            }
            if (!active || activeEpoch != turn.epoch() ||
                    prior.stateVersion() != turn.expectedStateVersion() ||
                    !leaseOwner.equals(currentOwner) || !unexpired) {
                throw new StaleActivationException("stale epoch or state version");
            }
            Snapshot next = turn.nextSnapshot();
            if (next.formatVersion() != prior.formatVersion() ||
                    next.stateSchemaVersion() != prior.stateSchemaVersion() ||
                    !next.codeGeneration().equals(prior.codeGeneration())) {
                throw new IllegalArgumentException("unreviewed schema/code migration");
            }
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE ores_durable_inbox SET consumed=true WHERE " + KEY +
                    " AND delivery_id=? AND NOT consumed")) {
                bind(p, key);
                p.setString(5, turn.consumedDeliveryId());
                if (p.executeUpdate() != 1) throw new IllegalStateException("unknown/consumed delivery");
            }
            Pending pending = pendingCount(c, "ores_durable_outbox", key);
            long newBytes = 0;
            for (Outbound outgoing : turn.outgoing()) {
                newBytes += outgoing.payload().length;
                if (newBytes > MAX_PENDING_BYTES) {
                    throw new IllegalStateException("outbox payload admission quota exhausted");
                }
            }
            if (pending.count() + turn.outgoing().size() > maxPendingOutbox ||
                pending.bytes() + newBytes > MAX_PENDING_BYTES) {
                throw new IllegalStateException("durable outbox admission quota exhausted");
            }
            for (Outbound msg : turn.outgoing()) {
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO ores_durable_outbox (tenant_id,workflow_id,run_id,node_id," +
                        "intent_id,idempotency_key,payload) VALUES (?,?,?,?,?,?,?)")) {
                    bind(p, key);
                    p.setString(5, msg.intentId());
                    p.setString(6, msg.idempotencyKey());
                    bindPayload(p, 7, msg.payload());
                    p.executeUpdate(); // duplicate intent fails and rolls back consumption/state
                }
            }
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE ores_durable_actor SET state_version=?,encoded_state=? WHERE " + KEY +
                    " AND activation_epoch=? AND active=true AND state_version=?" +
                    " AND lease_owner=? AND lease_expires_at>clock_timestamp()")) {
                p.setLong(1, next.stateVersion());
                bindPayload(p, 2, next.encodedState());
                bindShifted(p, 3, key);
                p.setLong(7, turn.epoch());
                p.setLong(8, turn.expectedStateVersion());
                p.setString(9, leaseOwner);
                if (p.executeUpdate() != 1) throw new StaleActivationException("fenced actor update");
            }
            return next.stateVersion();
        });
    }

    @Override
    public void hibernate(Address key, long epoch) {
        Objects.requireNonNull(key);
        transact(c -> {
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE ores_durable_actor SET active=false,lease_owner=NULL,lease_expires_at=NULL" +
                    " WHERE " + KEY + " AND active=true AND activation_epoch=?" +
                    " AND lease_owner=? AND lease_expires_at>clock_timestamp()")) {
                bind(p, key); p.setLong(5, epoch);
                p.setString(6, leaseOwner);
                if (p.executeUpdate() != 1) throw new StaleActivationException("hibernate fenced");
            }
            return null;
        });
    }


    /** Extend an active owned lease; expired or superseded epochs can never renew. */
    public boolean renew(Address key, long epoch) {
        Objects.requireNonNull(key);
        return transact(c -> {
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE ores_durable_actor SET lease_expires_at=" +
                    "clock_timestamp()+make_interval(secs => ?) WHERE " + KEY +
                    " AND active=true AND activation_epoch=? AND lease_owner=?" +
                    " AND lease_expires_at>clock_timestamp()")) {
                p.setInt(1, LEASE_SECONDS);
                bindShifted(p, 2, key);
                p.setLong(6, epoch);
                p.setString(7, leaseOwner);
                return p.executeUpdate() == 1;
            }
        });
    }

    @Override
    public boolean acknowledge(Address key, long epoch, String intentId, String receiptId) {
        Objects.requireNonNull(key);
        if (intentId == null || intentId.isBlank() || receiptId == null || receiptId.isBlank())
            throw new IllegalArgumentException("blank receipt identity");
        return transact(c -> {
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT activation_epoch,active,lease_owner," +
                    "(lease_expires_at IS NOT NULL AND lease_expires_at>clock_timestamp())" +
                    " FROM ores_durable_actor WHERE " + KEY + " FOR UPDATE")) {
                bind(p, key);
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next() || rs.getLong(1) != epoch || !rs.getBoolean(2) ||
                        !leaseOwner.equals(rs.getString(3)) || !rs.getBoolean(4))
                        throw new StaleActivationException("receipt fenced");
                }
            }
            String old;
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT receipt_id FROM ores_durable_outbox WHERE " + KEY +
                    " AND intent_id=? FOR UPDATE")) {
                bind(p, key); p.setString(5, intentId);
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) throw new IllegalArgumentException("unknown operation");
                    old = rs.getString(1);
                }
            }
            if (old != null) {
                if (!old.equals(receiptId)) throw new IllegalStateException("receipt conflict");
                return false;
            }
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE ores_durable_outbox SET receipt_id=? WHERE " + KEY + " AND intent_id=?")) {
                p.setString(1, receiptId);
                bindShifted(p, 2, key);
                p.setString(6, intentId);
                if (p.executeUpdate() != 1) throw new IllegalStateException("receipt vanished");
            }
            return true;
        });
    }
}
