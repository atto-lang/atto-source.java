package dev.oreslang.runtime.persistence;

import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.Identity;
import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.Status;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Trusted JDBC command-transaction provider sharing the fences/state/events
 * SQLite tables with SqlitePersistenceStore. WAL/FULL and BEGIN IMMEDIATE
 * make every multi-event command, head, and stable logical receipt atomic.
 * This is BLOCKING HOST I/O and must run on a dedicated sidecar worker.
 * Not a guest API and not a distributed fencing authority.
 */
public final class SqliteCommandTransactionStore implements AutoCloseable {
    public record Command(Identity id, String opId, long epoch, long expectedVersion,
                          int schemaVersion, byte[] commandBytes, List<byte[]> events,
                          byte[] resultBytes) {
        public Command {
            Objects.requireNonNull(id);
            checkId(opId);
            if (epoch < 1 || expectedVersion < 0 || schemaVersion < 1)
                throw new IllegalArgumentException("INVALID_COMMAND_METADATA");
            commandBytes = Objects.requireNonNull(commandBytes).clone();
            resultBytes = Objects.requireNonNull(resultBytes).clone();
            Objects.requireNonNull(events);
            if (events.isEmpty() || events.size() > 256)
                throw new IllegalArgumentException("INVALID_COMMAND_EVENTS");
            List<byte[]> copies = new ArrayList<>(events.size());
            long bytes = 0;
            for (byte[] event : events) {
                byte[] copy = Objects.requireNonNull(event).clone();
                if (copy.length > 1_048_576) throw new IllegalArgumentException("EVENT_TOO_LARGE");
                bytes += copy.length;
                if (bytes > 1_048_576) throw new IllegalArgumentException("COMMAND_TOO_LARGE");
                copies.add(copy);
            }
            if (commandBytes.length > 1_048_576 || resultBytes.length > 1_048_576)
                throw new IllegalArgumentException("COMMAND_TOO_LARGE");
            events = List.copyOf(copies);
        }
        @Override public byte[] commandBytes() { return commandBytes.clone(); }
        @Override public byte[] resultBytes() { return resultBytes.clone(); }
        @Override public List<byte[]> events() {
            List<byte[]> copies = new ArrayList<>(events.size());
            for (byte[] item : events) copies.add(item.clone());
            return List.copyOf(copies);
        }
        long charge() {
            long bytes = 256L + commandBytes.length + resultBytes.length;
            for (byte[] event : events) bytes += event.length + 64L;
            return bytes;
        }
    }
    public record Receipt(String opId, Status status, long firstVersion, long version,
                          int eventCount, boolean durable, byte[] resultBytes) {
        public Receipt {
            resultBytes = resultBytes == null ? new byte[0] : resultBytes.clone();
            if (durable && status != Status.COMMITTED && status != Status.DUPLICATE)
                throw new IllegalArgumentException("INVALID_DURABLE_STATUS");
        }
        @Override public byte[] resultBytes() { return resultBytes.clone(); }
    }

    private final Connection db;
    private boolean closed;

    public SqliteCommandTransactionStore(Path trustedDatabasePath) throws SQLException {
        Objects.requireNonNull(trustedDatabasePath);
        if (!trustedDatabasePath.isAbsolute() || trustedDatabasePath.toString().contains("\0"))
            throw new IllegalArgumentException("INVALID_TRUSTED_DATABASE_PATH");
        db = DriverManager.getConnection("jdbc:sqlite:" + trustedDatabasePath);
        try {
            execute("PRAGMA busy_timeout=5000");
            try (Statement s=db.createStatement();
                 ResultSet r=s.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!r.next() || !"wal".equalsIgnoreCase(r.getString(1)))
                    throw new SQLException("WAL_UNAVAILABLE");
            }
            execute("PRAGMA synchronous=FULL");
            // Common source-store tables must exist before command transactions.
            for (String table : List.of("fences", "state", "events", "ops")) {
                try (PreparedStatement s=db.prepareStatement(
                        "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?")) {
                    s.setString(1,table);
                    try (ResultSet r=s.executeQuery()) {
                        if (!r.next()) throw new SQLException("MISSING_SHARED_STORE_SCHEMA: " + table);
                    }
                }
            }
            execute("CREATE TABLE IF NOT EXISTS command_commits(" +
                "tenant TEXT NOT NULL,actor TEXT NOT NULL,slot TEXT NOT NULL," +
                "op TEXT NOT NULL,command_hash BLOB NOT NULL," +
                "first_version INTEGER NOT NULL,last_version INTEGER NOT NULL," +
                "event_count INTEGER NOT NULL,schema INTEGER NOT NULL," +
                "events_hash BLOB NOT NULL,result_hash BLOB NOT NULL," +
                "result_bytes BLOB NOT NULL,PRIMARY KEY(tenant,actor,slot,op))");
        } catch (SQLException failure) {
            db.close();
            throw failure;
        }
    }

    // Borrow an existing BEGIN IMMEDIATE transaction only for verification.
    // Do NOT open a new connection, begin a nested transaction or close the
    // owner connection. This permits trustworthy legacy snapshot admission
    // after earlier uncommitted legacy/command writes in the same batch.
    private SqliteCommandTransactionStore(Connection borrowed) {
        db=Objects.requireNonNull(borrowed);
    }

    private static void checkId(String value) {
        if (value == null || value.isBlank() ||
            value.getBytes(StandardCharsets.UTF_8).length > 128 ||
            !java.text.Normalizer.isNormalized(value, java.text.Normalizer.Form.NFC) ||
            value.codePoints().anyMatch(cp -> Character.isISOControl(cp) || cp >= 0xD800 && cp <= 0xDFFF))
            throw new IllegalArgumentException("INVALID_OPERATION_ID");
    }
    private static byte[] hash(byte[] input) {
        try { return MessageDigest.getInstance("SHA-256").digest(input); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static byte[] eventHash(int schema, List<byte[]> events) {
        // Cross-backend v1 canonical digest, shared with the Node conformance
        // oracle: sha256(UTF8(JSON([schema,[base64(event), ...]]))).
        // Base64 alphabet cannot introduce JSON escaping ambiguities.
        StringBuilder bytes=new StringBuilder().append('[').append(schema).append(",[");
        for (int i=0;i<events.size();i++) {
            if(i>0)bytes.append(',');
            bytes.append('"').append(java.util.Base64.getEncoder()
                    .encodeToString(events.get(i))).append('"');
        }
        bytes.append("]]");
        return hash(bytes.toString().getBytes(StandardCharsets.UTF_8));
    }
    private void execute(String sql) throws SQLException {
        try (Statement s=db.createStatement()) { s.execute(sql); }
    }
    private void begin() throws SQLException { execute("BEGIN IMMEDIATE"); }
    private void rollback() { try { execute("ROLLBACK"); } catch (SQLException ignored) {} }
    private void checkOpen() { if (closed) throw new IllegalStateException("STORE_CLOSED"); }
    private static void bindId(PreparedStatement s, Identity id) throws SQLException {
        s.setString(1,id.tenant());s.setString(2,id.actor());s.setString(3,id.state());
    }
    private long currentEpoch(Identity id) throws SQLException {
        try (PreparedStatement s=db.prepareStatement(
                "SELECT epoch FROM fences WHERE tenant=? AND actor=?")) {
            s.setString(1,id.tenant());s.setString(2,id.actor());
            try (ResultSet r=s.executeQuery()) { return r.next()?r.getLong(1):0L; }
        }
    }
    private long head(Identity id) throws SQLException {
        try (PreparedStatement s=db.prepareStatement(
                "SELECT version FROM state WHERE tenant=? AND actor=? AND slot=?")) {
            bindId(s,id);
            try (ResultSet r=s.executeQuery()) { return r.next()?r.getLong(1):0L; }
        }
    }

    /**
     * Enforce replay's hard 10,000-event/16 MiB bounds BEFORE admitting new
     * command events. Both backends' SQLite writers use BEGIN IMMEDIATE, so
     * there is no read/insert race across separate JDBC connections.
     */
    private boolean replayCapacityAvailable(Identity id,int newEvents,long newBytes)
            throws SQLException {
        if(newEvents<1 || newEvents>10000 || newBytes<0 || newBytes>16_777_216L)
            return false;
        long checkpoint=0;
        int snapshotSchema=0;
        byte[] snapshot=null;
        try(PreparedStatement s=db.prepareStatement(
                "SELECT snapshot_version,snapshot_schema,length(snapshot)," +
                "substr(snapshot,1,1048577) FROM state " +
                "WHERE tenant=? AND actor=? AND slot=?")) {
            bindId(s,id);
            try(ResultSet row=s.executeQuery()) {
                if(row.next()) {
                    checkpoint=row.getLong(1);
                    snapshotSchema=row.getInt(2);
                    long size=row.getLong(3);
                    boolean absent=row.wasNull();
                    snapshot=row.getBytes(4);
                    if(checkpoint>0 &&
                       (absent || size<0 || size>1_048_576 ||
                        snapshot==null || snapshot.length!=size))
                        throw new SQLException("INVALID_ACTOR_SNAPSHOT");
                    if(checkpoint==0 && (snapshotSchema!=0 || !absent))
                        throw new SQLException("INVALID_ACTOR_SNAPSHOT");
                }
            }
        }
        if(checkpoint<0 || checkpoint>head(id))
            throw new SQLException("INVALID_ACTOR_SNAPSHOT");
        if(checkpoint>0)
            verifySnapshotBarrier(id,checkpoint,snapshotSchema,snapshot);
        try(PreparedStatement s=db.prepareStatement(
                "SELECT COUNT(*),COALESCE(SUM(length(payload)),0)," +
                "MIN(version),MAX(version) FROM events " +
                "WHERE tenant=? AND actor=? AND slot=? AND version>?")) {
            bindId(s,id);s.setLong(4,checkpoint);
            try(ResultSet row=s.executeQuery()) {
                if(!row.next())throw new SQLException("ACTOR_JOURNAL_COUNT_UNAVAILABLE");
                long count=row.getLong(1), bytes=row.getLong(2);
                long first=row.getLong(3),last=row.getLong(4);
                long streamHead=head(id);
                if(count<0 || bytes<0)throw new SQLException("INVALID_ACTOR_JOURNAL");
                if(count!=streamHead-checkpoint ||
                   (count>0 && (first!=checkpoint+1 || last!=streamHead)))
                    throw new SQLException("INCOMPLETE_ACTOR_JOURNAL");
                return count<=10000L-newEvents &&
                       bytes<=16_777_216L-newBytes;
            }
        }
    }

    /**
     * Cross-epoch logical-command receipts may outlive compacted event BLOBs,
     * but only behind a *verified* durable snapshot barrier. Never trust the
     * state row's snapshot bytes merely because its version is present.
     * Invoke only inside a pinned read/write SQLite transaction.
     */
    private void verifySnapshotBarrier(Identity id, long version, int schema,
                                       byte[] snapshot) throws SQLException {
        if(version<1 || schema<1 || snapshot==null ||
           snapshot.length>1_048_576)
            throw new SQLException("INVALID_ACTOR_SNAPSHOT");
        try(PreparedStatement q=db.prepareStatement(
                "SELECT schema,length(payload),substr(payload,1,1048577)," +
                "epoch,expected FROM ops WHERE tenant=? AND actor=? AND slot=? " +
                "AND kind=1 AND version=?")) {
            bindId(q,id);q.setLong(4,version);
            try(ResultSet r=q.executeQuery()) {
                if(!r.next())throw new SQLException("UNVERIFIED_SNAPSHOT");
                int originalSchema=r.getInt(1);
                long size=r.getLong(2);
                byte[] original=r.getBytes(3);
                if(originalSchema!=schema || size!=snapshot.length ||
                   original==null || original.length!=size ||
                   r.getLong(4)<1 || r.getLong(5)!=version-1 ||
                   !Arrays.equals(snapshot,original) || r.next())
                    throw new SQLException("CORRUPT_SNAPSHOT_PROOF");
            }
        }
    }

    // Must be called within the same read/write transaction as epoch validation.
    private Receipt proof(Command command) throws SQLException {
        Identity id=command.id();
        try (PreparedStatement s=db.prepareStatement(
                "SELECT command_hash,first_version,last_version,event_count,schema," +
                "events_hash,result_hash,result_bytes FROM command_commits " +
                "WHERE tenant=? AND actor=? AND slot=? AND op=?")) {
            bindId(s,id);s.setString(4,command.opId());
            try (ResultSet r=s.executeQuery()) {
                if (!r.next()) return null;
                if (!MessageDigest.isEqual(r.getBytes(1),hash(command.commandBytes)))
                    throw new SecurityException("OP_ID_CONFLICT");
                long first=r.getLong(2),last=r.getLong(3);
                int count=r.getInt(4),schema=r.getInt(5);
                byte[] recordedEvents=r.getBytes(6),recordedResultHash=r.getBytes(7),result=r.getBytes(8);
                if (first<1 || count<1 || count>10000 || schema<1 ||
                    last==Long.MAX_VALUE || last < first ||
                    last-first+1 != count || last>head(id) ||
                    result==null || recordedResultHash==null ||
                    !MessageDigest.isEqual(hash(result),recordedResultHash))
                    throw new SQLException("CORRUPT_COMMAND_RECORD");
                long snapshotVersion=0;
                try (PreparedStatement snap=db.prepareStatement(
                        "SELECT snapshot_version,snapshot_schema,length(snapshot)," +
                        "substr(snapshot,1,1048577) FROM state " +
                        "WHERE tenant=? AND actor=? AND slot=?")) {
                    bindId(snap,id);
                    try (ResultSet q=snap.executeQuery()) {
                        if (q.next()) {
                            snapshotVersion=q.getLong(1);
                            int snapSchema=q.getInt(2);
                            long size=q.getLong(3);
                            boolean nullSnapshot=q.wasNull();
                            byte[] bytes=q.getBytes(4);
                            if(snapshotVersion<0 || snapshotVersion>head(id))
                                throw new SQLException("INVALID_ACTOR_SNAPSHOT");
                            if(snapshotVersion>0) {
                                if(nullSnapshot || size<0 || size>1_048_576 ||
                                   bytes==null || bytes.length!=size)
                                    throw new SQLException("INVALID_ACTOR_SNAPSHOT");
                                verifySnapshotBarrier(id,snapshotVersion,snapSchema,bytes);
                            } else if(snapSchema!=0 || !nullSnapshot)
                                throw new SQLException("INVALID_ACTOR_SNAPSHOT");
                        }
                    }
                }
                if (first<=snapshotVersion && last>snapshotVersion)
                    throw new SQLException("COMMAND_CROSSES_SNAPSHOT");
                if (last>snapshotVersion) {
                    List<byte[]> observed=new ArrayList<>();
                    try (PreparedStatement ev=db.prepareStatement(
                            "SELECT version,schema,length(payload),substr(payload,1,1048577) " +
                            "FROM events WHERE tenant=? AND actor=? AND slot=? AND version BETWEEN ? AND ? ORDER BY version")) {
                        bindId(ev,id);ev.setLong(4,first);ev.setLong(5,last);
                        try (ResultSet row=ev.executeQuery()) {
                            long expected=first, bytes=0;
                            while (row.next()) {
                                long version=row.getLong(1);
                                int eventSchema=row.getInt(2),size=row.getInt(3);
                                if (version!=expected++ || eventSchema!=schema || size<0 || size>1_048_576 ||
                                    size>16_777_216L-bytes)
                                    throw new SQLException("INCOMPLETE_COMMAND_JOURNAL");
                                byte[] event=row.getBytes(4);
                                if (event==null || event.length!=size)
                                    throw new SQLException("INCOMPLETE_COMMAND_JOURNAL");
                                bytes+=size;observed.add(event);
                                if (observed.size()>10000) throw new SQLException("INCOMPLETE_COMMAND_JOURNAL");
                            }
                            if (expected!=last+1 || observed.size()!=count)
                                throw new SQLException("INCOMPLETE_COMMAND_JOURNAL");
                        }
                    }
                    if (recordedEvents==null ||
                        !MessageDigest.isEqual(recordedEvents,eventHash(schema,observed)))
                        throw new SQLException("CORRUPT_COMMAND_EVENTS");
                }
                return new Receipt(command.opId(),Status.DUPLICATE,first,last,count,true,result);
            }
        }
    }

    /**
     * Atomic commit. A stable logical command id survives newer activation
     * epochs; the epoch fence is evaluated BEFORE duplicate lookup.
     */
    public synchronized Receipt commit(Command command) throws SQLException {
        Objects.requireNonNull(command);checkOpen();
        Identity id=command.id();
        begin();
        try {
            Receipt answer;
            if (currentEpoch(id)!=command.epoch()) {
                answer=new Receipt(command.opId(),Status.STALE_EPOCH,-1,-1,0,false,null);
            } else {
                Receipt prior=proof(command);
                if (prior!=null) answer=prior;
                else {
                    boolean legacy=false;
                    try (PreparedStatement q=db.prepareStatement(
                            "SELECT 1 FROM ops WHERE tenant=? AND actor=? AND slot=? AND op=?")) {
                        bindId(q,id);q.setString(4,command.opId());
                        try (ResultSet r=q.executeQuery()) { legacy=r.next(); }
                    }
                    long version=head(id);
                    if (legacy) {
                        answer=new Receipt(command.opId(),Status.OP_ID_CONFLICT,-1,-1,0,false,null);
                    } else if (version!=command.expectedVersion()) {
                        answer=new Receipt(command.opId(),Status.VERSION_CONFLICT,-1,version,0,false,null);
                    } else if (command.events.size()>Long.MAX_VALUE-version) {
                        answer=new Receipt(command.opId(),Status.VERSION_CONFLICT,-1,version,0,false,null);
                    } else if (!replayCapacityAvailable(id,command.events.size(),
                                   command.events.stream().mapToLong(e->e.length).sum())) {
                        answer=new Receipt(command.opId(),Status.REPLAY_LIMIT_EXCEEDED,
                                           -1,version,0,false,null);
                    } else {
                        long first=version+1,last=version+command.events.size();
                        try (PreparedStatement insert=db.prepareStatement(
                                "INSERT INTO events(tenant,actor,slot,version,schema,payload) VALUES(?,?,?,?,?,?)")) {
                            for (int i=0;i<command.events.size();i++) {
                                bindId(insert,id);insert.setLong(4,first+i);
                                insert.setInt(5,command.schemaVersion());
                                insert.setBytes(6,command.events.get(i));insert.executeUpdate();
                            }
                        }
                        try (PreparedStatement update=db.prepareStatement(
                                "INSERT INTO state(tenant,actor,slot,version,snapshot_version,snapshot_schema,snapshot)" +
                                " VALUES(?,?,?,?,0,0,NULL) ON CONFLICT(tenant,actor,slot) DO UPDATE SET version=excluded.version")) {
                            bindId(update,id);update.setLong(4,last);update.executeUpdate();
                        }
                        try (PreparedStatement insert=db.prepareStatement(
                                "INSERT INTO command_commits VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
                            bindId(insert,id);insert.setString(4,command.opId());
                            insert.setBytes(5,hash(command.commandBytes));
                            insert.setLong(6,first);insert.setLong(7,last);
                            insert.setInt(8,command.events.size());insert.setInt(9,command.schemaVersion());
                            insert.setBytes(10,eventHash(command.schemaVersion(),command.events));
                            insert.setBytes(11,hash(command.resultBytes));insert.setBytes(12,command.resultBytes);
                            insert.executeUpdate();
                        }
                        answer=new Receipt(command.opId(),Status.COMMITTED,first,last,
                                command.events.size(),true,command.resultBytes);
                    }
                }
            }
            execute("COMMIT");
            return answer;
        } catch (Exception failure) {
            rollback();
            // After an attempted write, exception propagation must never
            // imply a durable rejection: the COMMIT result could be unknown.
            if (failure instanceof SecurityException security) throw security;
            throw new SQLException("OUTCOME_UNKNOWN: " + failure.getMessage(),failure);
        }
    }

    /** Fenced read of the durable logical command record and original content. */
    public synchronized Receipt verify(Command command) throws SQLException {
        Objects.requireNonNull(command);checkOpen();
        Identity id=command.id();
        execute("BEGIN");
        try {
            if (currentEpoch(id)!=command.epoch()) throw new SecurityException("STALE_EPOCH");
            Receipt r=proof(command);
            execute("COMMIT");
            return r;
        } catch (Exception failure) {
            rollback();
            if (failure instanceof SQLException sql) throw sql;
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new SQLException("OUTCOME_UNKNOWN",failure);
        }
    }
    /**
     * Trusted actor reactivation: reads epoch, snapshot, head and post-snapshot
     * journal in ONE SQLite snapshot and proves every replay event against its
     * immutable legacy op or logical multi-event command record.
     *
     * No partially replayed state escapes on journal corruption. The caller
     * must reconstruct into a fresh private arena and revalidate its
     * activation authority before transitioning from RECOVERING to READY.
     */
    /**
     * Verify the complete currently committed/uncommitted actor journal
     * inside a transaction owned by another trusted SQLite provider. Exact
     * per-event proof and command digests are required before snapshotting.
     */
    static SqlitePersistenceStore.Recovery verifyWithinCurrentTransaction(
            Connection ownedByCaller, Identity id) throws SQLException {
        Objects.requireNonNull(ownedByCaller);
        Objects.requireNonNull(id);
        return new SqliteCommandTransactionStore(ownedByCaller)
                .readVerifiedWithinCurrentTransaction(id);
    }

    private SqlitePersistenceStore.Recovery readVerifiedWithinCurrentTransaction(
            Identity id) throws SQLException {
            long headVersion=0,snapshotVersion=0;
            int snapshotSchema=0;
            byte[] snapshot=new byte[0];
            try(PreparedStatement s=db.prepareStatement(
                    "SELECT version,snapshot_version,snapshot_schema,length(snapshot)," +
                    "substr(snapshot,1,1048577) FROM state " +
                    "WHERE tenant=? AND actor=? AND slot=?")) {
                bindId(s,id);
                try(ResultSet r=s.executeQuery()){
                    if(r.next()){
                        headVersion=r.getLong(1);snapshotVersion=r.getLong(2);
                        snapshotSchema=r.getInt(3);
                        long size=r.getLong(4);
                        boolean noSnapshot=r.wasNull();
                        byte[] bytes=r.getBytes(5);
                        if(headVersion<0 || snapshotVersion<0 ||
                           snapshotVersion>headVersion ||
                           (snapshotVersion==0 && (snapshotSchema!=0 || !noSnapshot)) ||
                           (snapshotVersion>0 && (snapshotSchema<1 || noSnapshot)) ||
                           size<0 || size>1_048_576 || (!noSnapshot && (bytes==null ||
                           bytes.length!=size)))
                            throw new SQLException("INVALID_ACTOR_SNAPSHOT");
                        if(bytes!=null)snapshot=bytes;
                    }
                }
            }
            if(snapshotVersion>0)
                verifySnapshotBarrier(id,snapshotVersion,snapshotSchema,snapshot);
            if(headVersion==Long.MAX_VALUE ||
               headVersion-snapshotVersion>10000)
                throw new SQLException("ACTOR_JOURNAL_REPLAY_LIMIT");
            // Index only the post-snapshot legacy events (snapshots themselves
            // are checked as barriers, not replayed) and command ranges.
            record Legacy(int schema, byte[] payload) {}
            java.util.Map<Long,Legacy> legacy=new java.util.HashMap<>();
            try(PreparedStatement s=db.prepareStatement(
                    "SELECT version,schema,length(payload),substr(payload,1,1048577)," +
                    "epoch,expected FROM ops WHERE tenant=? AND actor=? AND slot=? AND kind=2 " +
                    "AND version>?")) {
                bindId(s,id);s.setLong(4,snapshotVersion);
                try(ResultSet r=s.executeQuery()){
                    while(r.next()){
                        long version=r.getLong(1),size=r.getLong(3);
                        byte[] payload=r.getBytes(4);
                        if(version<1 || version>headVersion || r.getInt(2)<1 ||
                           size<0 || size>1_048_576 ||
                           payload==null || payload.length!=size ||
                           r.getLong(5)<1 || r.getLong(6)!=version-1 ||
                           legacy.put(version,new Legacy(r.getInt(2),payload))!=null)
                            throw new SQLException("CORRUPT_OPERATION_LEDGER");
                        if(legacy.size()>10000)throw new SQLException("ACTOR_JOURNAL_REPLAY_LIMIT");
                    }
                }
            }
            record CommandRange(long first,long last,int schema,int count,
                                byte[] hash,byte[] resultHash,byte[] resultBytes) {}
            java.util.Map<Long,CommandRange> expectedCommands=new java.util.HashMap<>();
            java.util.Map<Long,List<byte[]>> captured=new java.util.HashMap<>();
            try(PreparedStatement s=db.prepareStatement(
                    "SELECT first_version,last_version,event_count,schema,events_hash," +
                    "result_hash,result_bytes FROM command_commits " +
                    "WHERE tenant=? AND actor=? AND slot=? AND last_version>?")) {
                bindId(s,id);s.setLong(4,snapshotVersion);
                try(ResultSet r=s.executeQuery()){
                    int commands=0;
                    while(r.next()){
                        long first=r.getLong(1),last=r.getLong(2);
                        int count=r.getInt(3),schema=r.getInt(4);
                        byte[] digest=r.getBytes(5),resultHash=r.getBytes(6),
                               resultBytes=r.getBytes(7);
                        if(first<=snapshotVersion || first<1 ||
                           last==Long.MAX_VALUE || last>headVersion ||
                           count<1 || count>10000 || last-first+1!=count ||
                           schema<1 || digest==null || resultHash==null ||
                           resultBytes==null || resultBytes.length>1_048_576 ||
                           !MessageDigest.isEqual(hash(resultBytes),resultHash))
                            throw new SQLException("CORRUPT_COMMAND_RECORD");
                        CommandRange span=new CommandRange(first,last,schema,count,
                                                           digest,resultHash,resultBytes);
                        for(long version=first;version<=last;version++){
                            if(expectedCommands.put(version,span)!=null ||
                               legacy.containsKey(version))
                                throw new SQLException("OVERLAPPING_OPERATION_LEDGER");
                        }
                        captured.put(first,new ArrayList<>());
                        if(++commands>10000)throw new SQLException("ACTOR_JOURNAL_REPLAY_LIMIT");
                    }
                }
            }
            List<SqlitePersistenceStore.Event> events=new ArrayList<>();
            long expected=snapshotVersion+1,bytes=0;
            try(PreparedStatement s=db.prepareStatement(
                    "SELECT version,schema,length(payload),substr(payload,1,1048577) " +
                    "FROM events WHERE tenant=? AND actor=? AND slot=? AND version>? " +
                    "ORDER BY version ASC")) {
                bindId(s,id);s.setLong(4,snapshotVersion);
                try(ResultSet r=s.executeQuery()){
                    while(r.next()){
                        long version=r.getLong(1),size=r.getLong(3);
                        int schema=r.getInt(2);
                        byte[] payload=r.getBytes(4);
                        if(version!=expected++ || schema<1 || size<0 ||
                           size>1_048_576 || size>16_777_216L-bytes ||
                           payload==null || payload.length!=size)
                            throw new SQLException("INCOMPLETE_ACTOR_JOURNAL");
                        bytes+=size;
                        Legacy original=legacy.get(version);
                        CommandRange command=expectedCommands.get(version);
                        if(original==null && command==null)
                            throw new SQLException("MISSING_OPERATION_PROOF");
                        if(original!=null &&
                           (original.schema()!=schema ||
                            !Arrays.equals(original.payload(),payload)))
                            throw new SQLException("CORRUPT_LEGACY_EVENT");
                        if(command!=null) {
                            if(command.schema()!=schema)
                                throw new SQLException("CORRUPT_COMMAND_SCHEMA");
                            captured.get(command.first()).add(payload);
                        }
                        events.add(new SqlitePersistenceStore.Event(version,schema,payload));
                        if(events.size()>10000)
                            throw new SQLException("ACTOR_JOURNAL_REPLAY_LIMIT");
                    }
                }
            }
            if(expected!=headVersion+1 ||
               expectedCommands.size()+legacy.size()!=events.size())
                throw new SQLException("INCOMPLETE_ACTOR_JOURNAL");
            for(CommandRange command : new java.util.HashSet<>(expectedCommands.values())) {
                List<byte[]> values=captured.get(command.first());
                if(values==null || values.size()!=command.count() ||
                   !MessageDigest.isEqual(eventHash(command.schema(),values),command.hash()))
                    throw new SQLException("CORRUPT_COMMAND_EVENTS");
            }
            SqlitePersistenceStore.Recovery restored=
                new SqlitePersistenceStore.Recovery(headVersion,snapshotVersion,
                                                     snapshotSchema,snapshot,events);
        return restored;
    }

    public synchronized SqlitePersistenceStore.Recovery loadVerified(Identity id,
                                                                      long trustedEpoch)
            throws SQLException {
        Objects.requireNonNull(id);checkOpen();
        if (trustedEpoch<1) throw new IllegalArgumentException("INVALID_RESTORE_EPOCH");
        execute("BEGIN");
        try {
            if (currentEpoch(id)!=trustedEpoch)throw new SecurityException("STALE_EPOCH");
            SqlitePersistenceStore.Recovery restored=
                readVerifiedWithinCurrentTransaction(id);
            execute("COMMIT");
            return restored;
        } catch(Exception failure) {
            rollback();
            if(failure instanceof SQLException sql)throw sql;
            if(failure instanceof RuntimeException runtime)throw runtime;
            throw new SQLException("UNVERIFIABLE_RESTORE",failure);
        }
    }

    @Override public synchronized void close() throws SQLException {
        if(!closed) {closed=true;db.close();}
    }
}
