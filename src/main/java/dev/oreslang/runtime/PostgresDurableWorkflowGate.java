package dev.oreslang.runtime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Host-only transaction boundaries for authenticated webhook routing and
 * at-least-once external operation dispatch. External calls happen AFTER
 * claim commit; they are never made inside a database transaction.
 *
 * The trusted caller must authorize Address against the GitHub installation.
 * The HTTP handler must authenticate GitHub's raw-body HMAC before calling this.
 */
public final class PostgresDurableWorkflowGate {
    private static final String KEY="tenant_id=? AND workflow_id=? AND run_id=? AND node_id=?";
    private static final int MAX_INBOX=256;
    private static final long MAX_INBOX_BYTES=32L*1024*1024;
    private static final int MAX_MESSAGE_BYTES=16*1024*1024;
    private final DataSource source;

    public PostgresDurableWorkflowGate(DataSource source) {
        this.source=Objects.requireNonNull(source);
    }

    public record ExpectedPush(long installationId, long repositoryId, String ref,
                               String sha, long iteration, long stateVersion) {
        public ExpectedPush {
            if(installationId<=0||repositoryId<=0||iteration<=0||stateVersion<0 ||
               ref==null||!ref.startsWith("refs/heads/")||ref.length()>256 ||
               sha==null||!sha.matches("[0-9a-f]{40}")) {
                throw new IllegalArgumentException("invalid expected GitHub push");
            }
        }
    }
    public record SignedPush(long installationId, long repositoryId, String ref,
                             String sha, String deliveryId, byte[] encodedEvent) {
        public SignedPush {
            if(installationId<=0||repositoryId<=0||ref==null||!ref.startsWith("refs/heads/")||
               sha==null||!sha.matches("[0-9a-f]{40}")||
               deliveryId==null||!deliveryId.matches("[A-Za-z0-9-]{1,128}")||
               encodedEvent==null||encodedEvent.length==0||encodedEvent.length>MAX_MESSAGE_BYTES) {
                throw new IllegalArgumentException("invalid authenticated webhook envelope");
            }
            encodedEvent=encodedEvent.clone();
        }
        @Override public byte[] encodedEvent(){return encodedEvent.clone();}
    }
    public enum Admission { ACCEPTED, DUPLICATE, STALE }

    public record Claim(DurableActorStore.Outbound intent, UUID claimToken, long activationEpoch,
                        int attempts) { }

    @FunctionalInterface private interface Tx<T>{T go(Connection c)throws SQLException;}
    private <T> T transaction(Tx<T> body){
        try(Connection c=source.getConnection()){
            c.setAutoCommit(false);
            try{
                T result=body.go(c);
                c.commit();
                return result;
            }catch(SQLException|RuntimeException e){
                try{c.rollback();}catch(SQLException rollback){e.addSuppressed(rollback);}
                throw e;
            }
        }catch(SQLException e){throw new IllegalStateException("durable workflow transaction failed",e);}
    }
    private static void key(PreparedStatement p,int first,DurableActorStore.Address a)throws SQLException{
        p.setString(first,a.tenantId());p.setString(first+1,a.workflowId());
        p.setString(first+2,a.runId());p.setString(first+3,a.nodeId());
    }
    private static void lockActor(Connection c,DurableActorStore.Address a)throws SQLException{
        try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM ores_durable_actor WHERE "+KEY+" FOR UPDATE")){
            key(p,1,a);
            try(ResultSet r=p.executeQuery()){if(!r.next())throw new IllegalStateException("unknown actor");}
        }
    }

    /** A trusted workflow coordinator pins the next expected transition. */
    public void expectPush(DurableActorStore.Address a,ExpectedPush expected){
        Objects.requireNonNull(a);Objects.requireNonNull(expected);
        transaction(c->{
            lockActor(c,a);
            try(PreparedStatement p=c.prepareStatement(
                "SELECT state_version FROM ores_durable_actor WHERE "+KEY)){
                key(p,1,a);
                try(ResultSet r=p.executeQuery()){
                    if(!r.next()||r.getLong(1)!=expected.stateVersion())
                        throw new IllegalStateException("actor version changed before expectation");
                }
            }
            try(PreparedStatement p=c.prepareStatement(
                "INSERT INTO ores_durable_webhook_wait(tenant_id,workflow_id,run_id,node_id,"+
                "installation_id,repository_id,expected_ref,expected_sha,iteration,state_version,stage)"+
                " VALUES(?,?,?,?,?,?,?,?,?,?,'waiting_webhook') "+
                "ON CONFLICT(tenant_id,workflow_id,run_id,node_id) DO UPDATE SET "+
                "installation_id=EXCLUDED.installation_id,repository_id=EXCLUDED.repository_id,"+
                "expected_ref=EXCLUDED.expected_ref,expected_sha=EXCLUDED.expected_sha,"+
                "iteration=EXCLUDED.iteration,state_version=EXCLUDED.state_version,"+
                "stage='waiting_webhook' WHERE "+
                "(ores_durable_webhook_wait.state_version<EXCLUDED.state_version AND "+
                "ores_durable_webhook_wait.iteration<EXCLUDED.iteration) OR "+
                "(ores_durable_webhook_wait.stage='waiting_webhook' AND "+
                "ores_durable_webhook_wait.state_version=EXCLUDED.state_version AND "+
                "ores_durable_webhook_wait.iteration=EXCLUDED.iteration AND "+
                "ores_durable_webhook_wait.installation_id=EXCLUDED.installation_id AND "+
                "ores_durable_webhook_wait.repository_id=EXCLUDED.repository_id AND "+
                "ores_durable_webhook_wait.expected_sha=EXCLUDED.expected_sha AND "+
                "ores_durable_webhook_wait.expected_ref=EXCLUDED.expected_ref)")){
                key(p,1,a);p.setLong(5,expected.installationId());p.setLong(6,expected.repositoryId());
                p.setString(7,expected.ref());p.setString(8,expected.sha());
                p.setLong(9,expected.iteration());p.setLong(10,expected.stateVersion());
                if(p.executeUpdate()!=1)
                    throw new IllegalStateException("cannot reopen completed/changed expected push");
            }
            return null;
        });
    }

    /**
     * The repository/ref/SHA, persisted actor state version, and delivery-id
     * deduplication are checked under ONE actor row lock. A stale push creates no mail.
     */
    public Admission admitSignedPush(DurableActorStore.Address authorizedAddress,SignedPush event){
        Objects.requireNonNull(authorizedAddress);Objects.requireNonNull(event);
        return transaction(c->{
            lockActor(c,authorizedAddress);
            long version;
            try(PreparedStatement p=c.prepareStatement(
                    "SELECT state_version FROM ores_durable_actor WHERE "+KEY)){
                key(p,1,authorizedAddress);
                try(ResultSet r=p.executeQuery()){r.next();version=r.getLong(1);}
            }
            // The exact delivery identity is idempotent even after an expectation
            // has advanced from waiting_webhook to done.
            try(PreparedStatement p=c.prepareStatement(
                "SELECT payload FROM ores_durable_inbox WHERE "+KEY+" AND delivery_id=?")){
                key(p,1,authorizedAddress);p.setString(5,event.deliveryId());
                try(ResultSet r=p.executeQuery()){
                    if(r.next()){
                        // Same delivery identifier but different signed content
                        // is a collision, not a successful replay.
                        return java.security.MessageDigest.isEqual(r.getBytes(1),event.encodedEvent())
                            ? Admission.DUPLICATE : Admission.STALE;
                    }
                }
            }
            try(PreparedStatement p=c.prepareStatement(
                "SELECT 1 FROM ores_durable_webhook_wait WHERE "+KEY+
                " AND installation_id=? AND repository_id=? AND expected_ref=? AND expected_sha=?"+
                " AND state_version=? AND stage='waiting_webhook' FOR UPDATE")){
                key(p,1,authorizedAddress);p.setLong(5,event.installationId());
                p.setLong(6,event.repositoryId());p.setString(7,event.ref());
                p.setString(8,event.sha());p.setLong(9,version);
                try(ResultSet r=p.executeQuery()){if(!r.next())return Admission.STALE;}
            }
            try(PreparedStatement p=c.prepareStatement(
                "SELECT COUNT(*),COALESCE(SUM(OCTET_LENGTH(payload)),0)"+
                " FROM ores_durable_inbox WHERE "+KEY+" AND NOT consumed")){
                key(p,1,authorizedAddress);
                try(ResultSet r=p.executeQuery()){
                    r.next();
                    if(r.getLong(1)>=MAX_INBOX ||
                        r.getLong(2)+event.encodedEvent().length>MAX_INBOX_BYTES)
                        throw new IllegalStateException("webhook inbox backpressure");
                }
            }
            try(PreparedStatement p=c.prepareStatement(
                "INSERT INTO ores_durable_inbox(tenant_id,workflow_id,run_id,node_id,delivery_id,payload)"+
                " VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING")){
                key(p,1,authorizedAddress);p.setString(5,event.deliveryId());
                p.setBytes(6,event.encodedEvent());
                if(p.executeUpdate()!=1)return Admission.DUPLICATE;
            }
            // Logical event consumed once, not merely once per GitHub delivery ID.
            // State transition and inbox insert commit atomically.
            try(PreparedStatement p=c.prepareStatement(
                "UPDATE ores_durable_webhook_wait SET stage='done' WHERE "+KEY+
                " AND stage='waiting_webhook' AND state_version=?")){
                key(p,1,authorizedAddress);p.setLong(5,version);
                if(p.executeUpdate()!=1)throw new IllegalStateException("webhook wait changed");
            }
            return Admission.ACCEPTED;
        });
    }

    /**
     * Claim one persisted outbox operation under the actor activation owner.
     * Claim expiry is database-clock based; 60 seconds permits retry/reconciliation
     * but does not make external side effects exactly once.
     */
    public Claim claimNext(DurableActorStore.Address a,long epoch,UUID owner,UUID claimToken){
        Objects.requireNonNull(a);Objects.requireNonNull(owner);Objects.requireNonNull(claimToken);
        return transaction(c->{
            lockActor(c,a);
            try(PreparedStatement p=c.prepareStatement(
                "SELECT 1 FROM ores_durable_actor WHERE "+KEY+
                " AND active AND activation_epoch=? AND lease_owner=?"+
                " AND lease_until>clock_timestamp()+interval '5 seconds'")){
                key(p,1,a);p.setLong(5,epoch);p.setObject(6,owner);
                try(ResultSet r=p.executeQuery()){
                    if(!r.next())throw new IllegalStateException("outbox actor lease fenced");
                }
            }
            String intent,keyString;byte[] payload;int attempts;
            try(PreparedStatement p=c.prepareStatement(
                "SELECT intent_id,idempotency_key,payload,attempt_count "+
                "FROM ores_durable_outbox WHERE "+KEY+" AND receipt_id IS NULL"+
                " AND (claim_until IS NULL OR claim_until<=clock_timestamp())"+
                " ORDER BY intent_id FOR UPDATE SKIP LOCKED LIMIT 1")){
                key(p,1,a);
                try(ResultSet r=p.executeQuery()){
                    if(!r.next())return null;
                    intent=r.getString(1);keyString=r.getString(2);
                    payload=r.getBytes(3);attempts=r.getInt(4)+1;
                }
            }
            try(PreparedStatement p=c.prepareStatement(
                "UPDATE ores_durable_outbox SET claim_token=?,claim_epoch=?,"+
                "claim_until=LEAST(clock_timestamp()+interval '60 seconds',"+
                "(SELECT lease_until FROM ores_durable_actor a WHERE "+
                "a.tenant_id=ores_durable_outbox.tenant_id AND "+
                "a.workflow_id=ores_durable_outbox.workflow_id AND "+
                "a.run_id=ores_durable_outbox.run_id AND a.node_id=ores_durable_outbox.node_id)),"+
                "attempt_count=attempt_count+1 WHERE "+KEY+" AND intent_id=? AND receipt_id IS NULL")){
                p.setObject(1,claimToken);p.setLong(2,epoch);key(p,3,a);p.setString(7,intent);
                if(p.executeUpdate()!=1)throw new IllegalStateException("claim raced with receipt");
            }
            return new Claim(new DurableActorStore.Outbound(intent,keyString,payload),claimToken,epoch,attempts);
        });
    }

    /** Store an external receipt only for the current unexpired claim and actor owner. */
    public boolean recordReceipt(DurableActorStore.Address a,long epoch,UUID owner,Claim claim,
                                 String receipt){
        Objects.requireNonNull(a);Objects.requireNonNull(owner);Objects.requireNonNull(claim);
        if(receipt==null||receipt.isBlank())throw new IllegalArgumentException("blank receipt");
        if(claim.activationEpoch()!=epoch)throw new IllegalStateException("claim epoch changed");
        return transaction(c->{
            lockActor(c,a);
            try(PreparedStatement p=c.prepareStatement(
                "SELECT 1 FROM ores_durable_actor WHERE "+KEY+
                " AND active AND activation_epoch=? AND lease_owner=?"+
                " AND lease_until>clock_timestamp()")){
                key(p,1,a);p.setLong(5,epoch);p.setObject(6,owner);
                try(ResultSet r=p.executeQuery()){
                    if(!r.next())throw new IllegalStateException("outbox receipt actor fenced");
                }
            }
            String previous;
            try(PreparedStatement p=c.prepareStatement(
                "SELECT receipt_id FROM ores_durable_outbox WHERE "+KEY+
                " AND intent_id=? AND claim_token=? AND claim_epoch=?"+
                " AND claim_until>clock_timestamp() FOR UPDATE")){
                key(p,1,a);p.setString(5,claim.intent().intentId());
                p.setObject(6,claim.claimToken());p.setLong(7,epoch);
                try(ResultSet r=p.executeQuery()){
                    if(!r.next())throw new IllegalStateException("outbox claim lost or expired");
                    previous=r.getString(1);
                }
            }
            if(previous!=null){
                if(!previous.equals(receipt))throw new IllegalStateException("receipt conflict");
                return false;
            }
            try(PreparedStatement p=c.prepareStatement(
                "UPDATE ores_durable_outbox SET receipt_id=? WHERE "+KEY+" AND intent_id=?")){
                p.setString(1,receipt);key(p,2,a);p.setString(6,claim.intent().intentId());
                if(p.executeUpdate()!=1)throw new IllegalStateException("receipt disappeared");
            }
            return true;
        });
    }
}
