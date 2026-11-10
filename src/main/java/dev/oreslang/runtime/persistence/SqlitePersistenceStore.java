package dev.oreslang.runtime.persistence;

import java.nio.file.Path;
import java.text.Normalizer;
import java.sql.*;
import java.util.*;
import dev.oreslang.runtime.persistence.BatchPersistenceSidecar.*;

/**
 * SQLite WAL/FULL durable Store. JDBC driver is supplied by the opt-in host profile.
 * Trusted host only; never accepts a database path or activation grant from guest actors.
 * Blocks the calling worker and MUST NOT be invoked on an actor execution carrier.
 */
public final class SqlitePersistenceStore implements Store, AutoCloseable {
    public record Event(long version, int schemaVersion, byte[] payload) {
        public Event { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }
    public record Recovery(long version, long snapshotVersion, int snapshotSchema,
                           byte[] snapshot, List<Event> events) {
        public Recovery { snapshot = snapshot.clone(); events = List.copyOf(events); }
        @Override public byte[] snapshot() { return snapshot.clone(); }
    }
    private final Connection db;
    private boolean closed;
    public SqlitePersistenceStore(Path trustedDatabasePath) throws SQLException {
        Objects.requireNonNull(trustedDatabasePath);
        if (!trustedDatabasePath.isAbsolute() || trustedDatabasePath.toString().contains("\0"))
            throw new IllegalArgumentException("expected trusted absolute database path");
        db = DriverManager.getConnection("jdbc:sqlite:" + trustedDatabasePath);
        try {
            execute("PRAGMA busy_timeout=5000");
            try (Statement mode=db.createStatement(); ResultSet r=mode.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!r.next() || !"wal".equalsIgnoreCase(r.getString(1))) throw new SQLException("WAL unavailable");
            }
            execute("PRAGMA synchronous=FULL");
            execute("CREATE TABLE IF NOT EXISTS fences(tenant TEXT NOT NULL, actor TEXT NOT NULL, epoch INTEGER NOT NULL, PRIMARY KEY(tenant,actor))");
            execute("CREATE TABLE IF NOT EXISTS state(tenant TEXT NOT NULL, actor TEXT NOT NULL, slot TEXT NOT NULL, version INTEGER NOT NULL, snapshot_version INTEGER NOT NULL DEFAULT 0, snapshot_schema INTEGER NOT NULL DEFAULT 0, snapshot BLOB, PRIMARY KEY(tenant,actor,slot))");
            execute("CREATE TABLE IF NOT EXISTS ops(tenant TEXT NOT NULL, actor TEXT NOT NULL, slot TEXT NOT NULL, op TEXT NOT NULL, epoch INTEGER NOT NULL, expected INTEGER NOT NULL, kind INTEGER NOT NULL, schema INTEGER NOT NULL, payload BLOB NOT NULL, version INTEGER NOT NULL, PRIMARY KEY(tenant,actor,slot,op))");
            execute("CREATE TABLE IF NOT EXISTS events(tenant TEXT NOT NULL, actor TEXT NOT NULL, slot TEXT NOT NULL, version INTEGER NOT NULL, schema INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(tenant,actor,slot,version))");
            // Transactional command inbox is additive. Do not change the
            // legacy single-event ops table or its receipt semantics.
            execute("CREATE TABLE IF NOT EXISTS command_inbox(tenant TEXT NOT NULL, actor TEXT NOT NULL, slot TEXT NOT NULL, op TEXT NOT NULL, epoch INTEGER NOT NULL, expected INTEGER NOT NULL, request BLOB NOT NULL, first_version INTEGER NOT NULL, last_version INTEGER NOT NULL, event_batch BLOB NOT NULL, reply BLOB NOT NULL, PRIMARY KEY(tenant,actor,slot,op))");
        } catch (SQLException e) { db.close(); throw e; }
    }
    private void execute(String sql) throws SQLException { try (Statement q=db.createStatement()) { q.execute(sql); } }
    private void begin() throws SQLException { execute("BEGIN IMMEDIATE"); }
    private void commitTransaction() throws SQLException { execute("COMMIT"); }
    private void rollback() { try { execute("ROLLBACK"); } catch (SQLException ignored) { /* outcome unknown */ } }
    private void checkOpen() { if (closed) throw new IllegalStateException("store closed"); }
    private static void checkId(String s) {
        if (s == null || s.isBlank()
                || s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 128
                || !Normalizer.isNormalized(s, Normalizer.Form.NFC)
                || s.codePoints().anyMatch(cp -> Character.isISOControl(cp)
                        || (cp >= 0xD800 && cp <= 0xDFFF)))
            throw new IllegalArgumentException("invalid id");
    }
    private static void bindKey(PreparedStatement q, Identity id) throws SQLException {
        q.setString(1,id.tenant());q.setString(2,id.actor());q.setString(3,id.state());
    }
    private long epoch(Identity id) throws SQLException {
        try (PreparedStatement q=db.prepareStatement("SELECT epoch FROM fences WHERE tenant=? AND actor=?")) {
            q.setString(1,id.tenant());q.setString(2,id.actor());
            try(ResultSet r=q.executeQuery()) {return r.next()?r.getLong(1):0;}
        }
    }
    private long version(Identity id) throws SQLException {
        try(PreparedStatement q=db.prepareStatement("SELECT version FROM state WHERE tenant=? AND actor=? AND slot=?")) {
            bindKey(q,id); try(ResultSet r=q.executeQuery()){return r.next()?r.getLong(1):0;}
        }
    }
    @Override public synchronized void grantEpoch(String tenant,String actor,long newEpoch) {
        checkOpen();checkId(tenant);checkId(actor);
        if(newEpoch<=0)throw new IllegalArgumentException("invalid epoch");
        try {
            begin();
            long current=epoch(new Identity(tenant,actor,"main"));
            if(newEpoch<current)throw new IllegalArgumentException("STALE_EPOCH");
            try(PreparedStatement q=db.prepareStatement("INSERT INTO fences VALUES(?,?,?) ON CONFLICT(tenant,actor) DO UPDATE SET epoch=excluded.epoch")) {
                q.setString(1,tenant);q.setString(2,actor);q.setLong(3,newEpoch);q.executeUpdate();
            }
            commitTransaction();
        } catch (Exception error) {
            rollback();
            if(error instanceof RuntimeException r)throw r;
            throw new IllegalStateException("epoch grant outcome unknown",error);
        }
    }
    @Override public synchronized List<Result> commit(List<Write> batch) throws SQLException {
        checkOpen();Objects.requireNonNull(batch);
        List<Result> out=new ArrayList<>(batch.size());
        begin();
        try {
            for(Write w:batch) {
                Objects.requireNonNull(w); Identity id=w.id();
                checkId(id.tenant());checkId(id.actor());checkId(id.state());checkId(w.operationId());
                if(w.expectedVersion()==Long.MAX_VALUE || w.ownedPayload().length>1048576)
                    throw new IllegalArgumentException("invalid persistence version/payload");
                if(epoch(id)!=w.epoch()) {
                    out.add(new Result(w.operationId(),Status.STALE_EPOCH,-1,false));continue;
                }
                try(PreparedStatement q=db.prepareStatement("SELECT epoch,expected,kind,schema,payload,version FROM ops WHERE tenant=? AND actor=? AND slot=? AND op=?")) {
                    bindKey(q,id);q.setString(4,w.operationId());
                    try(ResultSet r=q.executeQuery()){
                        if(r.next()){
                            int kind=w.kind()==Kind.SNAPSHOT?1:2;
                            boolean same=r.getLong(1)==w.epoch()&&r.getLong(2)==w.expectedVersion()&&
                                r.getInt(3)==kind&&r.getInt(4)==w.schemaVersion()&&
                                Arrays.equals(r.getBytes(5),w.ownedPayload());
                            out.add(new Result(w.operationId(),same?Status.DUPLICATE:Status.OP_ID_CONFLICT,
                                same?r.getLong(6):-1,false));continue;
                        }
                    }
                }
                // One operation ID must never represent BOTH a legacy event
                // write and a durable command/reply transaction.
                try (PreparedStatement inbox=db.prepareStatement(
                        "SELECT 1 FROM command_inbox WHERE tenant=? AND actor=? AND slot=? AND op=?")) {
                    bindKey(inbox,id);inbox.setString(4,w.operationId());
                    try (ResultSet existing=inbox.executeQuery()) {
                        if (existing.next()) {
                            out.add(new Result(w.operationId(),Status.OP_ID_CONFLICT,-1,false));
                            continue;
                        }
                    }
                }
                long before=version(id);
                if(before!=w.expectedVersion()){
                    out.add(new Result(w.operationId(),Status.VERSION_CONFLICT,before,false));continue;
                }
                long next=before+1;
                try(PreparedStatement q=db.prepareStatement("INSERT INTO state(tenant,actor,slot,version,snapshot_version,snapshot_schema,snapshot) VALUES(?,?,?,?,?,?,?) ON CONFLICT(tenant,actor,slot) DO UPDATE SET version=excluded.version,snapshot_version=CASE WHEN ?=1 THEN excluded.snapshot_version ELSE state.snapshot_version END,snapshot_schema=CASE WHEN ?=1 THEN excluded.snapshot_schema ELSE state.snapshot_schema END,snapshot=CASE WHEN ?=1 THEN excluded.snapshot ELSE state.snapshot END")) {
                    bindKey(q,id);q.setLong(4,next);q.setLong(5,w.kind()==Kind.SNAPSHOT?next:0);q.setInt(6,w.kind()==Kind.SNAPSHOT?w.schemaVersion():0);
                    if(w.kind()==Kind.SNAPSHOT)q.setBytes(7,w.ownedPayload());else q.setNull(7,java.sql.Types.BLOB);
                    int isSnapshot=w.kind()==Kind.SNAPSHOT?1:0;
                    q.setInt(8,isSnapshot);q.setInt(9,isSnapshot);q.setInt(10,isSnapshot);q.executeUpdate();
                }
                if(w.kind()==Kind.EVENT) {
                    try(PreparedStatement q=db.prepareStatement("INSERT INTO events VALUES(?,?,?,?,?,?)")) {
                        bindKey(q,id);q.setLong(4,next);q.setInt(5,w.schemaVersion());q.setBytes(6,w.ownedPayload());q.executeUpdate();
                    }
                }
                try(PreparedStatement q=db.prepareStatement("INSERT INTO ops VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    bindKey(q,id);q.setString(4,w.operationId());q.setLong(5,w.epoch());q.setLong(6,w.expectedVersion());
                    q.setInt(7,w.kind()==Kind.SNAPSHOT?1:2);q.setInt(8,w.schemaVersion());
                    q.setBytes(9,w.ownedPayload());q.setLong(10,next);q.executeUpdate();
                }
                out.add(new Result(w.operationId(),Status.COMMITTED,next,false));
            }
            commitTransaction();
            return out.stream().map(r -> new Result(r.operationId(),r.status(),r.version(),
                r.status()==Status.COMMITTED || r.status()==Status.DUPLICATE)).toList();
        } catch(Exception error) {
            rollback();
            if(error instanceof SQLException sql)throw sql;
            if(error instanceof RuntimeException runtime)throw runtime;
            throw new SQLException("unexpected storage failure",error);
        }
    }
    /**
     * Called only by the trusted batch writer after commit(). The receipt
     * alone never authorizes state publication. Both the current epoch fence
     * and the exact durable operation record are read in one SQLite snapshot.
     */
    @Override public synchronized boolean verifyDurableCommit(Write write, Result receipt)
            throws SQLException {
        checkOpen();
        Objects.requireNonNull(write);
        Objects.requireNonNull(receipt);
        Identity id = write.id();
        checkId(id.tenant());
        checkId(id.actor());
        checkId(id.state());
        checkId(write.operationId());
        if (!receipt.durable()
                || (receipt.status() != Status.COMMITTED && receipt.status() != Status.DUPLICATE)
                || !receipt.operationId().equals(write.operationId())
                || write.expectedVersion() == Long.MAX_VALUE
                || receipt.version() != write.expectedVersion() + 1L) return false;
        execute("BEGIN");
        try {
            boolean valid = false;
            if (epoch(id) == write.epoch()) {
                try (PreparedStatement q=db.prepareStatement(
                        "SELECT epoch,expected,kind,schema,payload,version FROM ops "
                        + "WHERE tenant=? AND actor=? AND slot=? AND op=?")) {
                    bindKey(q,id);
                    q.setString(4,write.operationId());
                    try (ResultSet row=q.executeQuery()) {
                        if (row.next()) {
                            int kind = write.kind() == Kind.SNAPSHOT ? 1 : 2;
                            valid = row.getLong(1) == write.epoch()
                                    && row.getLong(2) == write.expectedVersion()
                                    && row.getInt(3) == kind
                                    && row.getInt(4) == write.schemaVersion()
                                    && Arrays.equals(row.getBytes(5),write.ownedPayload())
                                    && row.getLong(6) == receipt.version();
                        }
                    }
                }
            }
            commitTransaction();
            return valid;
        } catch (Exception error) {
            rollback();
            if (error instanceof SQLException sql) throw sql;
            if (error instanceof RuntimeException runtime) throw runtime;
            throw new SQLException("durable operation proof unavailable",error);
        }
    }
    /**
     * A trusted host may atomically append 1..256 immutable domain events
     * and their original COMMAND request/reply identity in one SQLite commit.
     * These records are NOT guest-authenticating capabilities. The compiler
     * must still prohibit mutable state publication until durable success.
     */
    public record CommandEvent(int schemaVersion, byte[] payload) {
        public CommandEvent {
            if (schemaVersion <= 0) throw new IllegalArgumentException("bad event schema");
            payload = Objects.requireNonNull(payload, "event payload").clone();
            if (payload.length > 65536) throw new IllegalArgumentException("event too large");
        }
        @Override public byte[] payload() { return payload.clone(); }
    }
    public record CommandReceipt(long firstVersion, long lastVersion,
                                 byte[] reply, boolean duplicate) {
        public CommandReceipt {
            if (firstVersion < 1 || lastVersion < firstVersion)
                throw new IllegalArgumentException("bad command version range");
            reply = Objects.requireNonNull(reply, "reply").clone();
        }
        @Override public byte[] reply() { return reply.clone(); }
    }

    private static byte[] encodeCommandEvents(List<CommandEvent> events) {
        Objects.requireNonNull(events,"events");
        if (events.isEmpty() || events.size() > 256)
            throw new IllegalArgumentException("command must emit 1..256 events");
        long size = 4;
        for (CommandEvent event : events) {
            Objects.requireNonNull(event,"event");
            size += 8L + event.payload().length;
            if (size > 1048576) throw new IllegalArgumentException("event batch exceeds 1MiB");
        }
        java.nio.ByteBuffer encoded=java.nio.ByteBuffer.allocate((int)size);
        encoded.putInt(events.size());
        for (CommandEvent event : events) {
            byte[] payload=event.payload();
            encoded.putInt(event.schemaVersion()).putInt(payload.length).put(payload);
        }
        return encoded.array();
    }

    private static List<CommandEvent> decodeCommandEvents(byte[] bytes) {
        if (bytes == null || bytes.length < 12 || bytes.length > 1048576)
            throw new SecurityException("INVALID_COMMAND_EVENT_BATCH");
        java.nio.ByteBuffer input=java.nio.ByteBuffer.wrap(bytes);
        int count=input.getInt();
        if (count < 1 || count > 256) throw new SecurityException("INVALID_COMMAND_EVENT_BATCH");
        List<CommandEvent> result=new ArrayList<>(count);
        for (int i=0;i<count;i++) {
            if (input.remaining() < 8) throw new SecurityException("INVALID_COMMAND_EVENT_BATCH");
            int schema=input.getInt(),length=input.getInt();
            if (schema <= 0 || length < 0 || length > 65536 || length > input.remaining())
                throw new SecurityException("INVALID_COMMAND_EVENT_BATCH");
            byte[] event=new byte[length];input.get(event);
            result.add(new CommandEvent(schema,event));
        }
        if (input.hasRemaining()) throw new SecurityException("INVALID_COMMAND_EVENT_BATCH");
        return result;
    }

    /** Must be invoked inside the current SQLite transaction only. */
    private Optional<CommandReceipt> resolveCommandInsideTransaction(
            Identity id,long currentEpoch,String operationId,byte[] exactCommand) throws SQLException {
        try (PreparedStatement q=db.prepareStatement(
                "SELECT epoch,expected,length(request),substr(request,1,65537),"
                + "first_version,last_version,length(event_batch),substr(event_batch,1,1048577),"
                + "length(reply),substr(reply,1,65537) FROM command_inbox "
                + "WHERE tenant=? AND actor=? AND slot=? AND op=?")) {
            bindKey(q,id);q.setString(4,operationId);
            try (ResultSet r=q.executeQuery()) {
                if (!r.next()) return Optional.empty();
                long originalEpoch=r.getLong(1),expected=r.getLong(2),first=r.getLong(5),last=r.getLong(6);
                byte[] request=r.getBytes(4),batch=r.getBytes(8),reply=r.getBytes(10);
                if (originalEpoch <= 0 || originalEpoch > currentEpoch
                        || expected < 0 || expected >= Long.MAX_VALUE
                        || first != expected+1 || last < first || last > version(id)
                        || last-first >= 256
                        || r.getLong(3) != exactCommand.length
                        || request == null || !Arrays.equals(request,exactCommand)
                        || r.getLong(7) < 12 || r.getLong(7) > 1048576
                        || r.getLong(9) > 65536 || reply == null)
                    throw new SecurityException("OP_ID_CONFLICT");
                List<CommandEvent> events=decodeCommandEvents(batch);
                if (events.size() != last-first+1)
                    throw new SecurityException("INVALID_COMMAND_EVENT_BATCH");
                try (PreparedStatement journal=db.prepareStatement(
                        "SELECT version,schema,length(payload),substr(payload,1,65537) "
                        + "FROM events WHERE tenant=? AND actor=? AND slot=? "
                        + "AND version>=? AND version<=? ORDER BY version ASC")) {
                    bindKey(journal,id);journal.setLong(4,first);journal.setLong(5,last);
                    try (ResultSet row=journal.executeQuery()) {
                        for (int i=0;i<events.size();i++) {
                            CommandEvent event=events.get(i);
                            if (!row.next() || row.getLong(1) != first+i
                                    || row.getInt(2) != event.schemaVersion()
                                    || row.getLong(3) != event.payload().length
                                    || !Arrays.equals(row.getBytes(4),event.payload()))
                                throw new SecurityException("INCOMPLETE_COMMAND_JOURNAL");
                        }
                        if (row.next()) throw new SecurityException("INCOMPLETE_COMMAND_JOURNAL");
                    }
                }
                return Optional.of(new CommandReceipt(first,last,reply,true));
            }
        }
    }

    /**
     * A new activation may resolve an earlier committed command by the SAME
     * immutable command bytes and op ID, without rerunning decide() or
     * reacquiring the prior writer epoch. Never expose this store method to
     * untrusted guest code or use it to bypass runtime actor turn ownership.
     */
    public synchronized Optional<CommandReceipt> resolveCommandFenced(
            Identity id,long currentEpoch,String operationId,byte[] exactCommand) throws SQLException {
        checkOpen();Objects.requireNonNull(id);Objects.requireNonNull(exactCommand);
        checkId(id.tenant());checkId(id.actor());checkId(id.state());checkId(operationId);
        if (currentEpoch<=0 || exactCommand.length>65536)
            throw new IllegalArgumentException("invalid request/epoch");
        byte[] request=exactCommand.clone();
        execute("BEGIN");
        try {
            if (epoch(id)!=currentEpoch) throw new SecurityException("STALE_EPOCH");
            Optional<CommandReceipt> receipt=resolveCommandInsideTransaction(
                    id,currentEpoch,operationId,request);
            commitTransaction();
            return receipt;
        } catch(Exception failure) {
            rollback();
            if (failure instanceof SQLException sql) throw sql;
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new SQLException("unknown command resolution outcome",failure);
        }
    }

    /**
     * Transactional inbox + contiguous multi-event append + persisted reply.
     * The caller is a trusted non-actor storage worker; it MUST copy the
     * staged immutable event batch and release actor state loans first.
     * Unknown COMMIT outcome requires resolveCommandFenced with SAME op ID.
     */
    public synchronized CommandReceipt commitCommand(
            Identity id,long activationEpoch,String operationId,long expectedVersion,
            byte[] commandBytes,List<CommandEvent> domainEvents,byte[] replyBytes)
            throws SQLException {
        checkOpen();Objects.requireNonNull(id);Objects.requireNonNull(commandBytes);
        Objects.requireNonNull(replyBytes);
        checkId(id.tenant());checkId(id.actor());checkId(id.state());checkId(operationId);
        if (activationEpoch <= 0 || expectedVersion < 0
                || commandBytes.length > 65536 || replyBytes.length > 65536)
            throw new IllegalArgumentException("invalid durable command bounds");
        List<CommandEvent> events=List.copyOf(domainEvents);
        byte[] batch=encodeCommandEvents(events),request=commandBytes.clone(),reply=replyBytes.clone();
        if (expectedVersion > Long.MAX_VALUE-events.size())
            throw new IllegalArgumentException("journal version overflow");

        begin();
        try {
            if (epoch(id) != activationEpoch) throw new SecurityException("STALE_EPOCH");
            Optional<CommandReceipt> previous=resolveCommandInsideTransaction(
                    id,activationEpoch,operationId,request);
            if (previous.isPresent()) {
                commitTransaction();
                return previous.get();
            }
            // No legacy write may use the same logical operation ID.
            try (PreparedStatement q=db.prepareStatement(
                    "SELECT 1 FROM ops WHERE tenant=? AND actor=? AND slot=? AND op=?")) {
                bindKey(q,id);q.setString(4,operationId);
                try (ResultSet existing=q.executeQuery()) {
                    if (existing.next()) throw new SecurityException("OP_ID_CONFLICT");
                }
            }
            long before=version(id);
            if (before != expectedVersion) throw new IllegalStateException("VERSION_CONFLICT");
            long first=before+1,last=before+events.size();
            try (PreparedStatement q=db.prepareStatement(
                    "INSERT INTO state(tenant,actor,slot,version,snapshot_version,snapshot_schema,snapshot) "
                    + "VALUES(?,?,?,?,0,0,NULL) ON CONFLICT(tenant,actor,slot) "
                    + "DO UPDATE SET version=excluded.version")) {
                bindKey(q,id);q.setLong(4,last);q.executeUpdate();
            }
            try (PreparedStatement q=db.prepareStatement(
                    "INSERT INTO events VALUES(?,?,?,?,?,?)")) {
                for (int i=0;i<events.size();i++) {
                    CommandEvent event=events.get(i);
                    bindKey(q,id);q.setLong(4,first+i);
                    q.setInt(5,event.schemaVersion());q.setBytes(6,event.payload());
                    q.executeUpdate();
                }
            }
            try (PreparedStatement q=db.prepareStatement(
                    "INSERT INTO command_inbox VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                bindKey(q,id);q.setString(4,operationId);
                q.setLong(5,activationEpoch);q.setLong(6,expectedVersion);
                q.setBytes(7,request);q.setLong(8,first);q.setLong(9,last);
                q.setBytes(10,batch);q.setBytes(11,reply);q.executeUpdate();
            }
            commitTransaction();
            return new CommandReceipt(first,last,reply,false);
        } catch(Exception failure) {
            rollback();
            if (failure instanceof SQLException sql) throw sql;
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new SQLException("unknown durable command outcome",failure);
        }
    }

    /**
     * Trusted, read-only reconciliation of an EVENT committed by any prior
     * activation of the SAME logical actor. The current provider epoch must
     * be authorized; this never reactivates a previous writer or returns a
     * guest-constructible durable receipt. Event bytes are compared with the
     * stored operation AND the retained journal row under one read snapshot.
     *
     * This verifies event identity, NOT original command payload identity or
     * an application's original reply (durable inbox is still required).
     */
    public record VerifiedCommittedEvent(long version, int schemaVersion, byte[] payload) {
        public VerifiedCommittedEvent {
            if (version < 1 || schemaVersion < 1)
                throw new IllegalArgumentException("invalid committed event");
            payload = Objects.requireNonNull(payload, "payload").clone();
        }
        @Override public byte[] payload() { return payload.clone(); }
    }

    public synchronized Optional<VerifiedCommittedEvent> resolveCommittedEventFenced(
            Identity id, long currentEpoch, String operationId,
            int schemaVersion, byte[] exactEventBytes) throws SQLException {
        checkOpen();
        Objects.requireNonNull(id, "identity");
        checkId(id.tenant());
        checkId(id.actor());
        checkId(id.state());
        checkId(operationId);
        Objects.requireNonNull(exactEventBytes, "event bytes");
        if (currentEpoch <= 0 || schemaVersion <= 0
                || exactEventBytes.length > 1048576)
            throw new IllegalArgumentException("invalid fenced event resolution input");
        byte[] expectedPayload = exactEventBytes.clone();

        execute("BEGIN");
        try {
            if (epoch(id) != currentEpoch) throw new SecurityException("STALE_EPOCH");
            Optional<VerifiedCommittedEvent> result = Optional.empty();
            try (PreparedStatement q = db.prepareStatement(
                    "SELECT epoch,expected,kind,schema,length(payload),"
                    + "substr(payload,1,1048577),version FROM ops "
                    + "WHERE tenant=? AND actor=? AND slot=? AND op=?")) {
                bindKey(q,id);
                q.setString(4,operationId);
                try (ResultSet op = q.executeQuery()) {
                    if (op.next()) {
                        long originalEpoch = op.getLong(1);
                        long originalExpected = op.getLong(2);
                        int kind = op.getInt(3);
                        int originalSchema = op.getInt(4);
                        long payloadLength = op.getLong(5);
                        byte[] saved = op.getBytes(6);
                        long committedVersion = op.getLong(7);
                        if (originalEpoch <= 0 || originalEpoch > currentEpoch
                                || originalExpected < 0
                                || originalExpected >= Long.MAX_VALUE
                                || originalExpected + 1L != committedVersion
                                || committedVersion < 1 || committedVersion > version(id)
                                || kind != 2 || originalSchema != schemaVersion
                                || payloadLength != expectedPayload.length
                                || saved == null || !Arrays.equals(saved,expectedPayload)) {
                            throw new SecurityException("OP_ID_CONFLICT");
                        }
                        // Fail closed if a corrupt/pruned journal cannot be
                        // checked independently against the operation ledger.
                        try (PreparedStatement eventQuery = db.prepareStatement(
                                "SELECT schema,length(payload),substr(payload,1,1048577)"
                                + " FROM events WHERE tenant=? AND actor=? AND slot=? AND version=?")) {
                            bindKey(eventQuery,id);
                            eventQuery.setLong(4,committedVersion);
                            try (ResultSet event = eventQuery.executeQuery()) {
                                if (!event.next() || event.getInt(1) != schemaVersion
                                        || event.getLong(2) != expectedPayload.length
                                        || !Arrays.equals(event.getBytes(3),expectedPayload))
                                    throw new SecurityException("OPERATION_EVENT_UNAVAILABLE");
                            }
                        }
                        result = Optional.of(new VerifiedCommittedEvent(
                                committedVersion,schemaVersion,saved));
                    }
                }
            }
            commitTransaction();
            return result;
        } catch (Exception failure) {
            rollback();
            if (failure instanceof SQLException sql) throw sql;
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new SQLException("event lookup outcome unknown", failure);
        }
    }

    /**
     * Activation-scoped restore. Fence lookup and all recovered data reads
     * share one SQLite read snapshot, so another connection cannot change the
     * epoch between the authorization check and subsequent state queries.
     * A trusted host may still call load() for migration or inspection;
     * actor reactivation must use this fenced entry point instead.
     */
    public synchronized Recovery loadFenced(Identity id, long trustedEpoch) throws SQLException {
        checkOpen();
        Objects.requireNonNull(id);
        if (trustedEpoch <= 0) throw new IllegalArgumentException("invalid restore epoch");
        execute("BEGIN");
        try {
            if (epoch(id) != trustedEpoch) throw new SecurityException("STALE_EPOCH");
            Recovery restored = readRestoreWithinCurrentTransaction(id);
            commitTransaction();
            return restored;
        } catch (Exception failure) {
            rollback();
            if (failure instanceof SQLException sql) throw sql;
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw new SQLException("restore outcome unknown", failure);
        }
    }
    /** Privileged host inspection; read state/head and events from ONE snapshot. */
    public synchronized Recovery load(Identity id) throws SQLException {
        checkOpen();Objects.requireNonNull(id);
        checkId(id.tenant());checkId(id.actor());checkId(id.state());
        execute("BEGIN");
        try {
            Recovery restored = readRestoreWithinCurrentTransaction(id);
            commitTransaction();
            return restored;
        } catch (SQLException | RuntimeException failure) {
            rollback(); throw failure;
        }
    }

    /** Called only while the owning Connection is inside an explicit read transaction. */
    private Recovery readRestoreWithinCurrentTransaction(Identity id) throws SQLException {
        long version=0, snapshotVersion=0;int snapshotSchema=0;byte[] snapshot=new byte[0];
        try(PreparedStatement q=db.prepareStatement("SELECT version,snapshot_version,snapshot_schema,length(snapshot),substr(snapshot,1,1048577) "
            + "FROM state WHERE tenant=? AND actor=? AND slot=?")) {
            bindKey(q,id);
            try(ResultSet r=q.executeQuery()){
                if(r.next()){
                    version=r.getLong(1);snapshotVersion=r.getLong(2);snapshotSchema=r.getInt(3);
                    int bytesLength=r.getInt(4);
                    if(bytesLength>1048576)throw new SQLException("INVALID_ACTOR_SNAPSHOT");
                    byte[] bytes=r.getBytes(5);
                    if(version<0 || snapshotVersion<0 || snapshotVersion>version || snapshotSchema<0 ||
                       (snapshotVersion>0 && (snapshotSchema==0 || bytes==null)) ||
                       (snapshotVersion==0 && (snapshotSchema!=0 || bytes!=null)) ||
                       (bytes!=null && bytes.length>1048576))
                        throw new SQLException("INVALID_ACTOR_SNAPSHOT");
                    if(bytes!=null)snapshot=bytes;
                }
            }
        }
        List<Event> events=new ArrayList<>();
        try(PreparedStatement q=db.prepareStatement("SELECT version,schema,length(payload),substr(payload,1,1048577) "
            + "FROM events WHERE tenant=? AND actor=? AND slot=? AND version>? ORDER BY version ASC")){
            bindKey(q,id);q.setLong(4,snapshotVersion);
            try(ResultSet r=q.executeQuery()){
                long expected=snapshotVersion;
                long replayBytes=0;
                while(r.next()){
                    long next=r.getLong(1);
                    int eventSchema=r.getInt(2);
                    if(expected==Long.MAX_VALUE || next!=expected+1 || eventSchema<=0)
                        throw new SQLException("INCOMPLETE_ACTOR_JOURNAL");
                    int eventLength=r.getInt(3);
                    if(eventLength>1048576 || eventLength>16777216L-replayBytes)
                        throw new SQLException("ACTOR_EVENT_REPLAY_BYTES_LIMIT");
                    byte[] payload=r.getBytes(4);
                    if(payload==null)throw new SQLException("NULL_ACTOR_EVENT");
                    if(payload.length>1048576 || payload.length>16777216L-replayBytes)
                        throw new SQLException("ACTOR_EVENT_REPLAY_BYTES_LIMIT");
                    replayBytes+=payload.length;
                    events.add(new Event(next,eventSchema,payload));
                    if(events.size()>10000)throw new SQLException("ACTOR_JOURNAL_REPLAY_LIMIT");
                    expected=next;
                }
            }
        }
        if(version<0 || snapshotVersion<0 || snapshotVersion>version ||
           (snapshotVersion>0 && snapshotSchema<=0) ||
           version - snapshotVersion != events.size())
            throw new SQLException("INCOMPLETE_ACTOR_JOURNAL");
        return new Recovery(version,snapshotVersion,snapshotSchema,snapshot,events);
    }
    @Override public synchronized void close() throws SQLException { if(!closed){closed=true;db.close();} }
}
