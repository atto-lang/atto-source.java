package dev.oreslang.runtime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.time.Duration;
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

    /**
     * A host-only, store-issued activation capability. Unlike a public record,
     * callers cannot manufacture an owner credential or detach one activation
     * from the tenant-scoped address at which its lease was acquired.
     * Never expose this object to untrusted guest code.
     */
    public static final class LeaseActivation {
        private final Address address;
        private final Activation activation;
        private final UUID owner;

        private LeaseActivation(Address address, Activation activation, UUID owner) {
            this.address = Objects.requireNonNull(address, "address");
            this.activation = Objects.requireNonNull(activation, "activation");
            this.owner = Objects.requireNonNull(owner, "owner");
        }

        public Address address() { return address; }
        public Activation activation() { return activation; }
        public UUID owner() { return owner; }
    }

    private static final Duration LEASE_LENGTH = Duration.ofSeconds(30);

    /** Legacy adapter entrypoint deliberately enforces the same healthy-owner admission. */
    @Override
    public Activation activate(Address key) {
        throw new StaleActivationException("activation requires explicit owner UUID: use acquireLease");
    }

    /** No healthy owner can be displaced. Expiration is decided by PostgreSQL's clock. */
    public LeaseActivation acquireLease(Address key, UUID owner) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(owner);
        return transact(c -> {
            Snapshot snap;

            long current;
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT activation_epoch,state_version,format_version,state_schema_version," +
                    "code_generation,encoded_state,active,lease_until," +
                    "clock_timestamp() FROM ores_durable_actor WHERE " + KEY + " FOR UPDATE")) {
                bind(p, key);
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) throw new IllegalStateException("unknown actor");
                    current = rs.getLong(1);
                    if (rs.getBoolean(7)) {
                        var until = rs.getTimestamp(8);
                        var now = rs.getTimestamp(9);
                        // A null legacy lease is ambiguous; fail closed rather
                        // than silently stealing a healthy pre-migration actor.
                        if (until == null || until.after(now)) {
                            throw new StaleActivationException("healthy activation lease held");
                        }
                    }
                    if (current == Long.MAX_VALUE) throw new IllegalStateException("activation epoch exhausted");
                    snap = new Snapshot(rs.getInt(3), rs.getInt(4), rs.getString(5),
                            rs.getLong(2), rs.getBytes(6));
                }
            }
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE ores_durable_actor SET activation_epoch=?,active=true," +
                    "lease_owner=?,lease_until=clock_timestamp() + (? * interval '1 second') WHERE " + KEY)) {
                p.setLong(1, current + 1);
                p.setObject(2, owner);
                p.setLong(3, LEASE_LENGTH.toSeconds());
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
            return new LeaseActivation(key, new Activation(current + 1, snap, inbox, outbox), owner);
        });
    }


    /** Heartbeats cannot resurrect expired or superseded leases. */
    public boolean renewLease(Address key, long epoch, UUID owner) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(owner);
        return transact(c -> {
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE ores_durable_actor SET lease_until=clock_timestamp() +" +
                    " (? * interval '1 second') WHERE " + KEY +
                    " AND activation_epoch=? AND active AND lease_owner=?" +
                    " AND lease_until>clock_timestamp()")) {
                p.setLong(1, LEASE_LENGTH.toSeconds());
                bindShifted(p, 2, key);
                p.setLong(6, epoch);
                p.setObject(7, owner);
                return p.executeUpdate()==1;
            }
        });
    }

    public void hibernateLease(Address key, long epoch, UUID owner) {
        Objects.requireNonNull(key);
        Objects.requireNonNull(owner);
        transact(c -> {
            hibernateLeaseInTransaction(c, key, epoch, owner);
            return null;
        });
    }

    private static void hibernateLeaseInTransaction(
            Connection c, Address key, long epoch, UUID owner) throws SQLException {
        try (PreparedStatement p = c.prepareStatement(
                "UPDATE ores_durable_actor SET active=false,lease_owner=NULL," +
                "lease_until=NULL WHERE " + KEY +
                " AND activation_epoch=? AND active AND lease_owner=? AND lease_until>clock_timestamp()")) {
            bind(p, key);
            p.setLong(5, epoch);
            p.setObject(6, owner);
            if (p.executeUpdate() != 1) {
                throw new StaleActivationException("lease release fenced");
            }
        }
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
        throw new StaleActivationException("turn commit requires lease owner: use commitOwnedTurn");
    }

    public long commitOwnedTurn(Turn turn, UUID owner) {
        Objects.requireNonNull(turn);
        Objects.requireNonNull(owner);
        return transact(c -> commitOwnedTurnInTransaction(c, turn, owner));
    }

    /**
     * Persist one consumed inbox message, next snapshot, and outbox intents;
     * release the same owner/epoch lease in the SAME PostgreSQL transaction.
     * If the release cannot be fenced, the state mutation rolls back too.
     * A supervisor must hold a real ActorRuntime quiescence proof across this
     * call, not just the advisory coordinator predicate.
     */
    public long commitAndHibernateOwnedTurn(Turn turn, UUID owner) {
        Objects.requireNonNull(turn);
        Objects.requireNonNull(owner);
        return transact(c -> {
            long version = commitOwnedTurnInTransaction(c, turn, owner);
            hibernateLeaseInTransaction(c, turn.address(), turn.epoch(), owner);
            return version;
        });
    }

    private long commitOwnedTurnInTransaction(Connection c, Turn turn, UUID owner)
            throws SQLException {
            Address key = turn.address();
            // Lock the actor before checking its epoch. Activate and hibernate use the same lock.
            Snapshot prior;
            long activeEpoch;
            boolean active;
            try (PreparedStatement p = c.prepareStatement(
                    "SELECT activation_epoch,active,state_version,format_version,state_schema_version," +
                    "code_generation,encoded_state,(lease_until>clock_timestamp()) AS lease_valid," +
                    "lease_owner" +
                    " FROM ores_durable_actor WHERE " + KEY + " FOR UPDATE")) {
                bind(p, key);
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) throw new StaleActivationException("unknown actor");
                    activeEpoch = rs.getLong(1);
                    active = rs.getBoolean(2) && rs.getBoolean(8) &&
                            owner.equals(rs.getObject(9, UUID.class));
                    prior = new Snapshot(rs.getInt(4), rs.getInt(5), rs.getString(6),
                            rs.getLong(3), rs.getBytes(7));
                }
            }
            if (!active || activeEpoch != turn.epoch() ||
                    prior.stateVersion() != turn.expectedStateVersion()) {
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
                    " AND activation_epoch=? AND active=true AND lease_until>clock_timestamp()" +
                    " AND state_version=? AND lease_owner=?")) {
                p.setLong(1, next.stateVersion());
                bindPayload(p, 2, next.encodedState());
                bindShifted(p, 3, key);
                p.setLong(7, turn.epoch());
                p.setLong(8, turn.expectedStateVersion());
                p.setObject(9, owner);
                if (p.executeUpdate() != 1) throw new StaleActivationException("fenced actor update");
            }
            return next.stateVersion();
    }

    @Override
    public void hibernate(Address key, long epoch) {
        throw new StaleActivationException("hibernate requires owner: use hibernateLease");
    }

    @Override
    public boolean acknowledge(Address key, long epoch, String intentId, String receiptId) {
        throw new StaleActivationException(
                "outbox receipt requires owner and claim token: use PostgresDurableWorkflowGate.recordReceipt");
    }
}
