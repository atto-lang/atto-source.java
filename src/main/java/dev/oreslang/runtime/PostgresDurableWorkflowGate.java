package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Experimental, host-only GitHub admission + external-outbox claim port.
 * The caller MUST authorize tenant identity from an authenticated GitHub App
 * installation mapping, never from event JSON. This class performs no network IO.
 */
public final class PostgresDurableWorkflowGate {
    private static final String KEY="tenant_id=? AND workflow_id=? AND run_id=? AND node_id=?";
    private final DataSource source;
    private final PostgresDurableActorStore activationOwner;
    /**
     * The outbox gate is paired with the actor-store instance that owns its
     * current activation. Epoch-only authority is deliberately insufficient.
     */
    public PostgresDurableWorkflowGate(DataSource source, PostgresDurableActorStore activationOwner) {
        this.source=Objects.requireNonNull(source,"source");
        this.activationOwner=Objects.requireNonNull(activationOwner,"activationOwner");
    }
    public enum Admission { ACCEPTED, DUPLICATE, STALE }
    public record Expected(DurableActorStore.Address address,long installationId,
                           long repositoryId,String sha,String ref,int iteration,long stateVersion) {
        public Expected {
            Objects.requireNonNull(address,"address");
            if(installationId<=0 || repositoryId<=0 || iteration<1 || stateVersion<0 ||
               !validSha(sha) || !validRef(ref)) throw new IllegalArgumentException("invalid expectation");
        }
    }
    public record Claim(DurableActorStore.Address address,long epoch,String token,
                        DurableActorStore.Outbound intent,int attempt) {
        public Claim {
            Objects.requireNonNull(address); Objects.requireNonNull(intent);
            if(epoch<=0 || attempt<1 || token==null || token.isBlank())
                throw new IllegalArgumentException("invalid outbox claim");
        }
    }
    @FunctionalInterface private interface Work<T> { T run(Connection c) throws SQLException; }
    private <T> T transaction(Work<T> action) {
        try(Connection c=source.getConnection()) {
            c.setAutoCommit(false);
            try {
                T result=action.run(c);
                c.commit();
                return result;
            }catch(SQLException | RuntimeException e) {
                try{c.rollback();}catch(SQLException failure){e.addSuppressed(failure);}
                throw e;
            }
        }catch(SQLException e){throw new IllegalStateException("workflow storage operation failed",e);}
    }
    private static void bind(PreparedStatement p,int start,DurableActorStore.Address key)throws SQLException {
        p.setString(start,key.tenantId());
        p.setString(start+1,key.workflowId());
        p.setString(start+2,key.runId());
        p.setString(start+3,key.nodeId());
    }
    private static boolean validSha(String sha) {return sha!=null && sha.matches("[a-f0-9]{40}");}
    private static boolean validRef(String ref) {return ref!=null && ref.startsWith("refs/heads/") &&
        ref.length()>11 && ref.length()<=255;}
    private static void validateTenant(String tenant) {
        if(tenant==null || tenant.isBlank())throw new IllegalArgumentException("authorized tenant required");
    }

    /**
     * Install a waiting expectation under a locked actor state version.
     * Only a trusted, tenant-authorized workflow coordinator may stage it.
     * For actual production deployment this should be enlisted in the same
     * transaction as the workflow state transition that sets waiting_webhook.
     */
    public void stage(Expected expected) {
        Objects.requireNonNull(expected);
        transaction(c -> {
            try(PreparedStatement p=c.prepareStatement(
                    "SELECT state_version FROM ores_durable_actor WHERE "+KEY+" FOR UPDATE")) {
                bind(p,1,expected.address());
                try(ResultSet r=p.executeQuery()) {
                    if(!r.next() || r.getLong(1)!=expected.stateVersion())
                        throw new IllegalStateException("stale workflow expectation");
                }
            }
            try(PreparedStatement p=c.prepareStatement(
                    "INSERT INTO ores_durable_webhook_expectation" +
                    " (tenant_id,workflow_id,run_id,node_id,installation_id,repository_id," +
                    "expected_sha,expected_ref,iteration,expected_state_version,stage)" +
                    " VALUES (?,?,?,?,?,?,?,?,?,?,'waiting_webhook')" +
                    " ON CONFLICT (tenant_id,workflow_id,run_id,node_id) DO UPDATE SET" +
                    " installation_id=excluded.installation_id,repository_id=excluded.repository_id," +
                    " expected_sha=excluded.expected_sha,expected_ref=excluded.expected_ref," +
                    " iteration=excluded.iteration,expected_state_version=excluded.expected_state_version," +
                    " stage=excluded.stage")) {
                bind(p,1,expected.address());
                p.setLong(5,expected.installationId());
                p.setLong(6,expected.repositoryId());
                p.setString(7,expected.sha());p.setString(8,expected.ref());
                p.setInt(9,expected.iteration());p.setLong(10,expected.stateVersion());
                p.executeUpdate();
            }
            return null;
        });
    }

    /**
     * Return DUPLICATE for a matching installation-wide delivery, including
     * after its original workflow has advanced. Fail closed if a delivery ID
     * is reused with conflicting signed routing information.
     */
    private static Admission seenDelivery(Connection c, String tenant, long installationId,
                                          long repositoryId, String sha, String ref,
                                          String deliveryId) throws SQLException {
        try (PreparedStatement p=c.prepareStatement(
                "SELECT tenant_id,repository_id,expected_sha,expected_ref" +
                " FROM ores_durable_github_delivery WHERE installation_id=? AND delivery_id=?")) {
            p.setLong(1,installationId);
            p.setString(2,deliveryId);
            try (ResultSet result=p.executeQuery()) {
                if (!result.next()) return null;
                if (!tenant.equals(result.getString(1)) || repositoryId!=result.getLong(2) ||
                        !sha.equals(result.getString(3)) || !ref.equals(result.getString(4))) {
                    throw new IllegalStateException("conflicting GitHub delivery identity");
                }
                return Admission.DUPLICATE;
            }
        }
    }

    /**
     * Called AFTER verifying the GitHub HMAC signature over the raw bytes.
     * Tenant must be resolved by trusted installation policy, not parsed from
     * the webhook body. Expected SHA/ref, actor state and quota are checked
     * under the same database transaction as durable inbox admission.
     */
    public Admission accept(String authorizedTenant,long installationId,long repositoryId,
                            String sha,String ref,String deliveryId,byte[] rawBody) {
        validateTenant(authorizedTenant);
        if(installationId<=0 || repositoryId<=0 || !validSha(sha) || !validRef(ref) ||
           deliveryId==null || !deliveryId.matches("[A-Za-z0-9-]{1,128}") ||
           rawBody==null || rawBody.length<1 || rawBody.length>1_048_576)
            throw new IllegalArgumentException("invalid verified event");
        return transaction(c -> {
            Admission earlier=seenDelivery(c,authorizedTenant,installationId,
                    repositoryId,sha,ref,deliveryId);
            if(earlier!=null)return earlier;
            DurableActorStore.Address actor;
            long actualVersion;
            // First find the candidate WITHOUT locking the expectation.
            // Staging locks the actor row first, so admission must acquire
            // actor -> expectation in that same order to avoid SQL deadlock.
            try(PreparedStatement p=c.prepareStatement(
                    "SELECT tenant_id,workflow_id,run_id,node_id" +
                    " FROM ores_durable_webhook_expectation WHERE tenant_id=?" +
                    " AND installation_id=? AND repository_id=? AND expected_sha=?" +
                    " AND expected_ref=? AND stage='waiting_webhook'")) {
                p.setString(1,authorizedTenant);p.setLong(2,installationId);
                p.setLong(3,repositoryId);p.setString(4,sha);p.setString(5,ref);
                try(ResultSet r=p.executeQuery()) {
                    if(!r.next())return Admission.STALE;
                    actor=new DurableActorStore.Address(r.getString(1),r.getString(2),
                           r.getString(3),r.getString(4));
                }
            }
            try(PreparedStatement p=c.prepareStatement(
                    "SELECT state_version FROM ores_durable_actor WHERE "+KEY+" FOR UPDATE")) {
                bind(p,1,actor);
                try(ResultSet r=p.executeQuery()) {
                    if(!r.next())return Admission.STALE;
                    actualVersion=r.getLong(1);
                }
            }
            // Recheck matching routing and expected version UNDER its own row
            // lock after acquiring the actor lock. A stage update that races
            // with the initial lookup cannot admit an obsolete webhook.
            try(PreparedStatement p=c.prepareStatement(
                    "SELECT expected_state_version FROM ores_durable_webhook_expectation" +
                    " WHERE "+KEY+" AND installation_id=? AND repository_id=?" +
                    " AND expected_sha=? AND expected_ref=? AND stage='waiting_webhook' FOR UPDATE")) {
                bind(p,1,actor);
                p.setLong(5,installationId);p.setLong(6,repositoryId);
                p.setString(7,sha);p.setString(8,ref);
                try(ResultSet r=p.executeQuery()) {
                    if(!r.next() || r.getLong(1)!=actualVersion)return Admission.STALE;
                }
            }
            try(PreparedStatement p=c.prepareStatement(
                    "SELECT 1 FROM ores_durable_inbox WHERE "+KEY+" AND delivery_id=?")) {
                bind(p,1,actor);p.setString(5,deliveryId);
                try(ResultSet r=p.executeQuery()){if(r.next())return Admission.DUPLICATE;}
            }
            try(PreparedStatement p=c.prepareStatement(
                    "SELECT COUNT(*),COALESCE(SUM(OCTET_LENGTH(payload)),0)" +
                    " FROM ores_durable_inbox WHERE "+KEY+" AND NOT consumed")) {
                bind(p,1,actor);
                try(ResultSet r=p.executeQuery()) {
                    r.next();
                    if(r.getLong(1)>=256 || r.getLong(2)+rawBody.length>32L*1024L*1024L)
                        throw new IllegalStateException("webhook inbox quota exhausted");
                }
            }
            // This global ledger insert and actor inbox insert commit together.
            // It also serializes concurrent replay across different actor routes.
            try(PreparedStatement p=c.prepareStatement(
                    "INSERT INTO ores_durable_github_delivery" +
                    "(installation_id,delivery_id,repository_id,tenant_id,expected_sha," +
                    "expected_ref,workflow_id,run_id,node_id)" +
                    " VALUES (?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
                p.setLong(1,installationId);p.setString(2,deliveryId);
                p.setLong(3,repositoryId);p.setString(4,authorizedTenant);
                p.setString(5,sha);p.setString(6,ref);
                p.setString(7,actor.workflowId());p.setString(8,actor.runId());
                p.setString(9,actor.nodeId());
                if(p.executeUpdate()!=1) {
                    return seenDelivery(c,authorizedTenant,installationId,
                        repositoryId,sha,ref,deliveryId);
                }
            }
            try(PreparedStatement p=c.prepareStatement(
                    "INSERT INTO ores_durable_inbox" +
                    "(tenant_id,workflow_id,run_id,node_id,delivery_id,payload)" +
                    " VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
                bind(p,1,actor);
                p.setString(5,deliveryId);
                p.setBytes(6,rawBody);
                return p.executeUpdate()==1?Admission.ACCEPTED:Admission.DUPLICATE;
            }
        });
    }

    /** One claim at a time, with a bounded lease and a fresh opaque claim token. */
    public Claim claim(DurableActorStore.Address actor,long epoch,int seconds) {
        Objects.requireNonNull(actor);
        if(epoch<1 || seconds<1 || seconds>60)throw new IllegalArgumentException("claim deadline");
        return transaction(c -> {
            activationOwner.requireOwnedActivation(c, actor, epoch);
            String intentId,idempotencyKey;byte[] payload;int oldAttempt;
            try(PreparedStatement p=c.prepareStatement(
                    "SELECT intent_id,idempotency_key,payload,attempt_count FROM ores_durable_outbox" +
                    " WHERE "+KEY+" AND receipt_id IS NULL" +
                    " AND (claim_until IS NULL OR claim_until<clock_timestamp())" +
                    " ORDER BY intent_id LIMIT 1 FOR UPDATE SKIP LOCKED")) {
                bind(p,1,actor);
                try(ResultSet r=p.executeQuery()) {
                    if(!r.next())return null;
                    intentId=r.getString(1);idempotencyKey=r.getString(2);
                    payload=r.getBytes(3);oldAttempt=r.getInt(4);
                }
            }
            if(oldAttempt==Integer.MAX_VALUE)throw new IllegalStateException("attempt budget overflow");
            String token=UUID.randomUUID().toString();
            try(PreparedStatement p=c.prepareStatement(
                    "UPDATE ores_durable_outbox SET claim_token=?,claim_epoch=?," +
                    "claim_until=clock_timestamp()+make_interval(secs => ?)," +
                    "attempt_count=attempt_count+1 WHERE "+KEY+" AND intent_id=?" +
                    " AND receipt_id IS NULL")) {
                p.setString(1,token);p.setLong(2,epoch);p.setInt(3,seconds);
                bind(p,4,actor);p.setString(8,intentId);
                if(p.executeUpdate()!=1)throw new IllegalStateException("claim lost");
            }
            return new Claim(actor,epoch,token,
                    new DurableActorStore.Outbound(intentId,idempotencyKey,payload),oldAttempt+1);
        });
    }

    /**
     * Atomically reconcile external receipt, rejecting stale token, expired
     * claim and superseded actor epoch. A lost receipt may require external
     * idempotency-key reconciliation; storage cannot guarantee exactly-once IO.
     */
    public boolean finish(Claim claim,String receipt) {
        Objects.requireNonNull(claim);
        if(receipt==null || receipt.isBlank())throw new IllegalArgumentException("receipt required");
        return transaction(c -> {
            activationOwner.requireOwnedActivation(c, claim.address(), claim.epoch());
            try(PreparedStatement p=c.prepareStatement(
                    "UPDATE ores_durable_outbox SET receipt_id=?,claim_token=NULL," +
                    "claim_until=NULL,claim_epoch=NULL WHERE "+KEY+" AND intent_id=?" +
                    " AND receipt_id IS NULL AND claim_token=? AND claim_epoch=?" +
                    " AND claim_until>clock_timestamp()")) {
                p.setString(1,receipt);bind(p,2,claim.address());
                p.setString(6,claim.intent().intentId());
                p.setString(7,claim.token());p.setLong(8,claim.epoch());
                if(p.executeUpdate()!=1)throw new IllegalStateException("stale/expired outbox claim");
                return true;
            }
        });
    }
}
