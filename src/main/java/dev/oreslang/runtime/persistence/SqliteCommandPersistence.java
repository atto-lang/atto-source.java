package dev.oreslang.runtime.persistence;

import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Host-only v2 logical-command SQLite provider. Not a guest API or a compiled .ores actor.
 * All JDBC operations run on the trusted storage worker through the async methods.
 * This deliberately does NOT change the legacy snapshot operation-ID contract.
 */
public final class SqliteCommandPersistence implements AutoCloseable {
    private static final int MAX_EVENT_COUNT = 256;
    private static final int MAX_COMMAND_BYTES = 1 << 20;
    private static final int MAX_REPLAY_EVENTS = 65536;
    private static final long MAX_REPLAY_BYTES = 64L * 1024 * 1024;
    private final long replayByteLimit;
    private final Connection db;
    private final String tenant;
    private final ExecutorService worker;
    // Bounded nonblocking admission, including queued and in-flight operations.
    private final Semaphore admission = new Semaphore(256);
    private boolean closed;

    public static final class CommandError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final String code;
        public CommandError(String code) { super(code); this.code = code; }
    }
    private static void fail(String code) { throw new CommandError(code); }
    private static void ident(String s) {
        if (s == null || s.isBlank() || s.getBytes(StandardCharsets.UTF_8).length > 128 ||
                !Normalizer.isNormalized(s, Normalizer.Form.NFC) ||
                s.codePoints().anyMatch(cp -> Character.isISOControl(cp) || (cp >= 0xD800 && cp <= 0xDFFF)))
            fail("INVALID_IDENTITY");
    }
    public record Identity(String tenant, String actor, String state) {
        public Identity { ident(tenant); ident(actor); ident(state); }
    }
    public record Command(Identity identity, String operationId, long epoch, long expectedVersion,
                          int schemaVersion, byte[] commandBytes, List<byte[]> events, byte[] resultBytes) {
        public Command {
            Objects.requireNonNull(identity);
            ident(operationId);
            if (epoch <= 0 || expectedVersion < 0 || schemaVersion <= 0 ||
                events == null || events.isEmpty() || events.size() > MAX_EVENT_COUNT)
                fail("INVALID_COMMAND");
            commandBytes = Objects.requireNonNull(commandBytes).clone();
            resultBytes = Objects.requireNonNull(resultBytes).clone();
            ArrayList<byte[]> owned = new ArrayList<>(events.size());
            long bytes = (long) commandBytes.length + resultBytes.length;
            for (byte[] event : events) {
                byte[] copy = Objects.requireNonNull(event).clone();
                bytes += copy.length;
                owned.add(copy);
            }
            if (bytes > MAX_COMMAND_BYTES) fail("PAYLOAD_TOO_LARGE");
            events = List.copyOf(owned);
        }
        @Override public byte[] commandBytes() { return commandBytes.clone(); }
        @Override public byte[] resultBytes() { return resultBytes.clone(); }
        @Override public List<byte[]> events() {
            return events.stream().map(byte[]::clone).toList();
        }
    }
    public record Receipt(String operationId, long firstVersion, long lastVersion,
                          int eventCount, byte[] resultBytes, boolean duplicate) {
        public Receipt {
            ident(operationId);
            if (firstVersion < 1 || lastVersion < firstVersion || eventCount < 1 ||
                    lastVersion - firstVersion + 1 != eventCount) fail("CORRUPT_RECEIPT");
            resultBytes = Objects.requireNonNull(resultBytes).clone();
        }
        @Override public byte[] resultBytes() { return resultBytes.clone(); }
    }
    public record Event(long version, int schemaVersion, String operationId, byte[] payload) {
        public Event { payload = Objects.requireNonNull(payload).clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }
    public record Recovery(long version, List<Event> events) {
        public Recovery { events = List.copyOf(events); }
    }

    public SqliteCommandPersistence(Path trustedAbsoluteDatabase, String tenant) throws SQLException {
        this(trustedAbsoluteDatabase, tenant, MAX_REPLAY_BYTES);
    }
    /** Bounded replay byte budget (1..64 MiB); checked before materializing BLOBs. */
    public SqliteCommandPersistence(Path trustedAbsoluteDatabase, String tenant, long replayByteLimit)
            throws SQLException {
        if (replayByteLimit < 1 || replayByteLimit > MAX_REPLAY_BYTES)
            fail("INVALID_CONFIG");
        this.replayByteLimit = replayByteLimit;
        Objects.requireNonNull(trustedAbsoluteDatabase);
        if (!trustedAbsoluteDatabase.isAbsolute() || trustedAbsoluteDatabase.toString().indexOf('\0') >= 0)
            throw new IllegalArgumentException("trusted absolute SQLite path required");
        ident(tenant);
        this.tenant = tenant;
        this.db = DriverManager.getConnection("jdbc:sqlite:" + trustedAbsoluteDatabase);
        try {
            execute("PRAGMA busy_timeout=5000");
            try (Statement stmt = db.createStatement(); ResultSet rows = stmt.executeQuery("PRAGMA journal_mode=WAL")) {
                if (!rows.next() || !"wal".equalsIgnoreCase(rows.getString(1)))
                    throw new SQLException("SQLite WAL required");
            }
            execute("PRAGMA synchronous=FULL");
            // Share the trusted epoch authority with the legacy JDBC store.
            execute("CREATE TABLE IF NOT EXISTS fences(tenant TEXT NOT NULL,actor TEXT NOT NULL,epoch INTEGER NOT NULL,PRIMARY KEY(tenant,actor))");
            execute("CREATE TABLE IF NOT EXISTS command_v2_heads(tenant TEXT NOT NULL,actor TEXT NOT NULL,slot TEXT NOT NULL,version INTEGER NOT NULL,PRIMARY KEY(tenant,actor,slot))");
            execute("CREATE TABLE IF NOT EXISTS command_v2_events(tenant TEXT NOT NULL,actor TEXT NOT NULL,slot TEXT NOT NULL,version INTEGER NOT NULL,op_id TEXT NOT NULL,schema INTEGER NOT NULL,payload BLOB NOT NULL,PRIMARY KEY(tenant,actor,slot,version))");
            execute("CREATE TABLE IF NOT EXISTS command_v2_receipts(tenant TEXT NOT NULL,actor TEXT NOT NULL,slot TEXT NOT NULL,op_id TEXT NOT NULL,command_hash TEXT NOT NULL,first_version INTEGER NOT NULL,last_version INTEGER NOT NULL,event_count INTEGER NOT NULL,result_bytes BLOB NOT NULL,PRIMARY KEY(tenant,actor,slot,op_id))");
            // Versioned event checksums are separate to fail closed on older
            // draft databases; never manufacture hashes for untrusted history.
            execute("CREATE TABLE IF NOT EXISTS command_v2_event_digests(tenant TEXT NOT NULL,actor TEXT NOT NULL,slot TEXT NOT NULL,version INTEGER NOT NULL,digest TEXT NOT NULL,PRIMARY KEY(tenant,actor,slot,version))");
            execute("CREATE TRIGGER IF NOT EXISTS command_v2_event_digest_no_delete BEFORE DELETE ON command_v2_event_digests BEGIN SELECT RAISE(ABORT,'IMMUTABLE_EVENT_DIGEST'); END");
            execute("CREATE TRIGGER IF NOT EXISTS command_v2_event_digest_no_update BEFORE UPDATE ON command_v2_event_digests BEGIN SELECT RAISE(ABORT,'IMMUTABLE_EVENT_DIGEST'); END");
            execute("CREATE TRIGGER IF NOT EXISTS command_v2_immutable_delete BEFORE DELETE ON command_v2_receipts BEGIN SELECT RAISE(ABORT,'IMMUTABLE_COMMAND_RECEIPT'); END");
            execute("CREATE TRIGGER IF NOT EXISTS command_v2_immutable_update BEFORE UPDATE ON command_v2_receipts BEGIN SELECT RAISE(ABORT,'IMMUTABLE_COMMAND_RECEIPT'); END");
        } catch (SQLException error) {
            db.close();
            throw error;
        }
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ores-command-v2-sqlite-worker");
            t.setDaemon(true);
            return t;
        });
    }
    private void execute(String sql) throws SQLException {
        try (Statement stmt = db.createStatement()) { stmt.execute(sql); }
    }
    private void begin() throws SQLException { execute("BEGIN IMMEDIATE"); }
    private void commit() throws SQLException { execute("COMMIT"); }
    private void rollback() { try { execute("ROLLBACK"); } catch (SQLException ignored) {} }
    private void open() { if (closed) fail("CLOSED"); }
    private static String digest(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            return java.util.HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    // Length-prefix every field: the checksum commits identity, event order,
    // schema and bytes, rather than only the raw payload. It is not a MAC
    // against a privileged database controller able to rewrite both tables.
    private static void hashField(MessageDigest digest, byte[] bytes) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
    private static String eventDigest(Identity id,long version,String op,int schema,byte[] bytes) {
        try {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            for(String field:List.of("atto-command-v2-event-v1",id.tenant(),id.actor(),id.state(),op))
                hashField(digest,field.getBytes(StandardCharsets.UTF_8));
            digest.update(ByteBuffer.allocate(Long.BYTES+Integer.BYTES)
                .putLong(version).putInt(schema).array());
            hashField(digest,bytes);
            return java.util.HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    private void checkTenant(Identity id) { if (!tenant.equals(id.tenant())) fail("WRONG_TENANT"); }
    private static void bind(PreparedStatement q, Identity id) throws SQLException {
        q.setString(1, id.tenant()); q.setString(2, id.actor()); q.setString(3, id.state());
    }
    private long epoch(Identity id) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("SELECT epoch FROM fences WHERE tenant=? AND actor=?")) {
            q.setString(1,id.tenant()); q.setString(2,id.actor());
            try (ResultSet rows=q.executeQuery()) { return rows.next() ? rows.getLong(1) : 0; }
        }
    }
    private long head(Identity id) throws SQLException {
        try (PreparedStatement q = db.prepareStatement("SELECT version FROM command_v2_heads WHERE tenant=? AND actor=? AND slot=?")) {
            bind(q,id); try (ResultSet rows=q.executeQuery()) { return rows.next()?rows.getLong(1):0; }
        }
    }
    private Receipt lookup(Identity id, String operationId, byte[] commandBytes) throws SQLException {
        try (PreparedStatement q=db.prepareStatement("SELECT command_hash,first_version,last_version,event_count,result_bytes FROM command_v2_receipts WHERE tenant=? AND actor=? AND slot=? AND op_id=?")) {
            bind(q,id); q.setString(4,operationId);
            try (ResultSet rows=q.executeQuery()) {
                if (!rows.next()) return null;
                if (!digest(commandBytes).equals(rows.getString(1))) fail("OP_ID_CONFLICT");
                long first=rows.getLong(2),last=rows.getLong(3);
                int count=rows.getInt(4);
                if (first<1 || last<first || count<1 || last-first+1!=count || last>head(id))
                    fail("CORRUPT_COMMAND_RECORD");
                return new Receipt(operationId,first,last,count,rows.getBytes(5),true);
            }
        }
    }
    /** Trusted host-only epoch grant. No guest can mint or advance an epoch. */
    public synchronized void grantEpoch(String actor, long epoch) throws SQLException {
        open(); ident(actor); if (epoch<1) fail("INVALID_EPOCH");
        Identity id=new Identity(tenant,actor,"main");
        begin();
        try {
            if (epoch<this.epoch(id)) fail("STALE_EPOCH");
            try (PreparedStatement q=db.prepareStatement("INSERT INTO fences VALUES(?,?,?) ON CONFLICT(tenant,actor) DO UPDATE SET epoch=excluded.epoch")) {
                q.setString(1,tenant);q.setString(2,actor);q.setLong(3,epoch);q.executeUpdate();
            }
            commit();
        } catch (SQLException | RuntimeException error) { rollback();throw error; }
    }
    /** Durable append + result + dedup identity share one SQLite transaction. */
    public synchronized Receipt append(Command input) throws SQLException {
        open(); Objects.requireNonNull(input); checkTenant(input.identity());
        begin();
        try {
            Identity id=input.identity();
            if (epoch(id)!=input.epoch()) fail("STALE_EPOCH");
            Receipt prior=lookup(id,input.operationId(),input.commandBytes);
            if (prior!=null) { commit();return prior; }
            long version=head(id);
            if (version!=input.expectedVersion()) fail("VERSION_CONFLICT");
            if (input.events.size()>Long.MAX_VALUE-version) fail("VERSION_EXHAUSTED");
            int i=0;
            for (byte[] payload : input.events) {
                try (PreparedStatement q=db.prepareStatement("INSERT INTO command_v2_events VALUES(?,?,?,?,?,?,?)")) {
                    bind(q,id);q.setLong(4,version+ ++i);q.setString(5,input.operationId());
                    q.setInt(6,input.schemaVersion());q.setBytes(7,payload);q.executeUpdate();
                }
                try (PreparedStatement q=db.prepareStatement(
                        "INSERT INTO command_v2_event_digests VALUES(?,?,?,?,?)")) {
                    bind(q,id);q.setLong(4,version+i);
                    q.setString(5,eventDigest(id,version+i,input.operationId(),
                        input.schemaVersion(),payload));
                    q.executeUpdate();
                }
            }
            long newVersion=version+i;
            try (PreparedStatement q=db.prepareStatement("INSERT INTO command_v2_heads VALUES(?,?,?,?) ON CONFLICT(tenant,actor,slot) DO UPDATE SET version=excluded.version")) {
                bind(q,id);q.setLong(4,newVersion);q.executeUpdate();
            }
            try (PreparedStatement q=db.prepareStatement("INSERT INTO command_v2_receipts VALUES(?,?,?,?,?,?,?,?,?)")) {
                bind(q,id);q.setString(4,input.operationId());q.setString(5,digest(input.commandBytes));
                q.setLong(6,version+1);q.setLong(7,newVersion);q.setInt(8,i);
                q.setBytes(9,input.resultBytes);q.executeUpdate();
            }
            commit();
            return new Receipt(input.operationId(),version+1,newVersion,i,input.resultBytes,false);
        } catch (SQLException | RuntimeException error) { rollback();throw error; }
    }
    /** Query durable proof under an epoch fence, including after actor reactivation. */
    public synchronized Receipt verify(Identity id, String operationId, long epoch, byte[] commandBytes)
            throws SQLException {
        open(); Objects.requireNonNull(id);checkTenant(id);ident(operationId);
        Objects.requireNonNull(commandBytes);if(epoch<1)fail("INVALID_EPOCH");
        begin();
        try {
            if (epoch(id)!=epoch) fail("STALE_EPOCH");
            Receipt receipt=lookup(id,operationId,commandBytes);
            commit();return receipt;
        } catch (SQLException | RuntimeException error) { rollback();throw error; }
    }
    /** Coherent, bounded replay; no snapshot/compaction support in this v2 Java slice yet. */
    public synchronized Recovery loadFenced(Identity id,long epoch) throws SQLException {
        open();Objects.requireNonNull(id);checkTenant(id);if(epoch<1)fail("INVALID_EPOCH");
        begin();
        try {
            if (epoch(id)!=epoch) fail("STALE_EPOCH");
            long version=head(id),cursor=0,replayedBytes=0;
            ArrayList<Event> events=new ArrayList<>();
            try (PreparedStatement q=db.prepareStatement(
                    "SELECT e.version,e.op_id,e.schema,e.payload,d.digest,length(e.payload) "
                    + "FROM command_v2_events e LEFT JOIN command_v2_event_digests d "
                    + "ON d.tenant=e.tenant AND d.actor=e.actor AND d.slot=e.slot "
                    + "AND d.version=e.version "
                    + "WHERE e.tenant=? AND e.actor=? AND e.slot=? "
                    + "ORDER BY e.version ASC LIMIT ?")) {
                bind(q,id);q.setInt(4,MAX_REPLAY_EVENTS+1);
                try (ResultSet rows=q.executeQuery()) {
                    while(rows.next()) {
                        if (events.size()>=MAX_REPLAY_EVENTS) fail("REPLAY_LIMIT_EXCEEDED");
                        long next=rows.getLong(1);
                        if (next!=cursor+1 || rows.getInt(3)<=0) fail("JOURNAL_GAP");
                        // Every replayed event must have one durable command receipt.
                        String op=rows.getString(2);
                        if (!receiptCovers(id,op,next)) fail("UNINDEXED_JOURNAL");
                        long length=rows.getLong(6);
                        if (rows.wasNull() || length < 0 ||
                                length > replayByteLimit-replayedBytes)
                            fail("REPLAY_BYTES_LIMIT_EXCEEDED");
                        // Check SQLite's length() before materializing the
                        // untrusted BLOB into a new Java byte[].
                        byte[] payload=rows.getBytes(4);
                        String recorded=rows.getString(5);
                        if (payload==null || payload.length!=length || recorded==null ||
                                !recorded.equals(eventDigest(id,next,op,rows.getInt(3),payload)))
                            fail("CORRUPT_JOURNAL_PAYLOAD");
                        replayedBytes+=length;
                        events.add(new Event(next,rows.getInt(3),op,payload));
                        cursor=next;
                    }
                }
            }
            if (cursor!=version) fail("JOURNAL_GAP");
            commit();return new Recovery(version,events);
        } catch (SQLException | RuntimeException error) { rollback();throw error; }
    }
    private boolean receiptCovers(Identity id,String op,long version) throws SQLException {
        try(PreparedStatement q=db.prepareStatement("SELECT first_version,last_version,event_count FROM command_v2_receipts WHERE tenant=? AND actor=? AND slot=? AND op_id=?")) {
            bind(q,id);q.setString(4,op);
            try(ResultSet r=q.executeQuery()) {
                if(!r.next())return false;
                long first=r.getLong(1),last=r.getLong(2),count=r.getLong(3);
                return first>=1 && version>=first && version<=last && count>=1 && last-first+1==count;
            }
        }
    }
    private <T> CompletionStage<T> asynchronously(java.util.concurrent.Callable<T> task) {
        if (!admission.tryAcquire())
            return CompletableFuture.failedFuture(new CommandError("BACKPRESSURE"));
        try {
            return CompletableFuture.supplyAsync(() -> {
                try { return task.call(); }
                catch (Exception exception) { throw new CompletionException(exception); }
                finally { admission.release(); }
            }, worker);
        } catch (RejectedExecutionException rejected) {
            admission.release();
            return CompletableFuture.failedFuture(new CommandError("CLOSED"));
        }
    }
    /** Calling this method never performs JDBC work on the actor carrier. */
    public CompletionStage<Receipt> appendAsync(Command input) {
        Objects.requireNonNull(input);return asynchronously(()->append(input));
    }
    public CompletionStage<Receipt> verifyAsync(Identity id,String op,long epoch,byte[] commandBytes) {
        byte[] owned=Objects.requireNonNull(commandBytes).clone();
        return asynchronously(()->verify(id,op,epoch,owned));
    }
    public CompletionStage<Recovery> loadFencedAsync(Identity id,long epoch) {
        return asynchronously(()->loadFenced(id,epoch));
    }
    @Override public void close() throws SQLException {
        worker.shutdown();
        try {if(!worker.awaitTermination(15,TimeUnit.SECONDS))worker.shutdownNow();}
        catch(InterruptedException e) {worker.shutdownNow();Thread.currentThread().interrupt();}
        synchronized(this) {if(!closed){closed=true;db.close();}}
    }
}
