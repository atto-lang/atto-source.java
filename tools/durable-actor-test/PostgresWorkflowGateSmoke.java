package dev.oreslang.runtime;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Real PostgreSQL integration of healthy-owner admission, atomic webhook
 * dedup/version checks and external outbox claim/receipt fencing.
 */
public final class PostgresWorkflowGateSmoke {
    private static byte[] bytes(String s){return s.getBytes(StandardCharsets.UTF_8);}
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    private static void rejects(Runnable f,String why){
        try{f.run();}catch(IllegalStateException expected){return;}
        throw new AssertionError("expected rejection: "+why);
    }
    private static void expire(PGSimpleDataSource ds,DurableActorStore.Address a)throws Exception{
        try(Connection c=ds.getConnection();PreparedStatement p=c.prepareStatement(
            "UPDATE ores_durable_actor SET lease_until=clock_timestamp()-interval '1 second' "+
            "WHERE tenant_id=? AND workflow_id=? AND run_id=? AND node_id=?")){
            p.setString(1,a.tenantId());p.setString(2,a.workflowId());
            p.setString(3,a.runId());p.setString(4,a.nodeId());p.executeUpdate();
        }
    }
    private static void expireClaim(PGSimpleDataSource ds,DurableActorStore.Address a)throws Exception{
        try(Connection c=ds.getConnection();PreparedStatement p=c.prepareStatement(
            "UPDATE ores_durable_outbox SET claim_until=clock_timestamp()-interval '1 second' "+
            "WHERE tenant_id=? AND workflow_id=? AND run_id=? AND node_id=?")){
            p.setString(1,a.tenantId());p.setString(2,a.workflowId());
            p.setString(3,a.runId());p.setString(4,a.nodeId());p.executeUpdate();
        }
    }
    public static void main(String[] args)throws Exception{
        var ds=new PGSimpleDataSource();
        ds.setURL(System.getenv("AI_DEV_LOOP_JDBC_URL"));
        ds.setUser(System.getenv("AI_DEV_LOOP_DB_USER"));
        ds.setPassword(System.getenv("AI_DEV_LOOP_DB_PASSWORD"));
        var store=new PostgresDurableActorStore(ds);
        var gate=new PostgresDurableWorkflowGate(ds);
        var key=new DurableActorStore.Address("tenant-gate","wf","run","worker");
        var initial=new DurableActorStore.Snapshot(0,1,"code-v1",0,bytes("initial"));
        check(store.initialize(key,initial),"first creation");
        String sha="a".repeat(40),ref="refs/heads/develop";
        var expected=new PostgresDurableWorkflowGate.ExpectedPush(9,10,ref,sha,1,0);
        gate.expectPush(key,expected);
        gate.expectPush(key,expected); // identical pending expectation is idempotent
        var push=new PostgresDurableWorkflowGate.SignedPush(9,10,ref,sha,"delivery-1",bytes("signed-raw-push"));
        check(gate.admitSignedPush(key,push)==PostgresDurableWorkflowGate.Admission.ACCEPTED,
            "first authenticated push");
        check(gate.admitSignedPush(key,push)==PostgresDurableWorkflowGate.Admission.DUPLICATE,
            "duplicate delivery ignored atomically");
        rejects(()->gate.expectPush(key,expected),
            "completed expected workflow state cannot be reopened");
        check(gate.admitSignedPush(key,new PostgresDurableWorkflowGate.SignedPush(
            9,10,ref,sha,"delivery-1",bytes("different-payload"))) ==
            PostgresDurableWorkflowGate.Admission.STALE,
            "same delivery ID with different raw bytes must not count as duplicate");
        check(gate.admitSignedPush(key,new PostgresDurableWorkflowGate.SignedPush(
            9,10,ref,sha,"different-delivery-same-logical-push",bytes("another-signed-delivery"))) ==
            PostgresDurableWorkflowGate.Admission.STALE,
            "one logical expected event cannot enqueue twice with different delivery IDs");
        check(gate.admitSignedPush(key,new PostgresDurableWorkflowGate.SignedPush(
            9,10,ref,"b".repeat(40),"delivery-b",bytes("wrong"))) ==
            PostgresDurableWorkflowGate.Admission.STALE,"reject uncorrelated push");
        check(gate.admitSignedPush(key,new PostgresDurableWorkflowGate.SignedPush(
            9,11,ref,sha,"delivery-c",bytes("wrong"))) ==
            PostgresDurableWorkflowGate.Admission.STALE,"reject wrong repository");

        var owner=UUID.randomUUID();
        var a=store.acquireLease(key,owner);
        check(a.activation().epoch()==1 && a.activation().inbox().size()==1,"lease activation");
        rejects(()->store.acquireLease(key,UUID.randomUUID()),"cannot steal healthy actor");
        check(!store.renewLease(key,1,UUID.randomUUID()),"incorrect owner cannot renew");
        check(store.renewLease(key,1,owner),"holder can renew");
        // Having the correct epoch does not grant ownership.
        var intruder=UUID.randomUUID();
        rejects(()->store.commitOwnedTurn(new DurableActorStore.Turn(
            key,1,0,"delivery-1",
            new DurableActorStore.Snapshot(0,1,"code-v1",1,bytes("intruder")),
            List.of()),intruder),"other owner cannot commit at current epoch");
        rejects(()->store.hibernateLease(key,1,intruder),
            "other owner cannot hibernate current epoch");
        rejects(()->gate.claimNext(key,1,intruder,UUID.randomUUID()),
            "other owner cannot claim at current epoch");
        check(store.renewLease(key,1,owner),
            "intrusion did not invalidate valid lease");

        var outbound=new DurableActorStore.Outbound("op-1","workflow:run:sha:1",bytes("request"));
        var outbound2=new DurableActorStore.Outbound("op-2","workflow:run:sha:2",bytes("request2"));
        var updated=new DurableActorStore.Snapshot(0,1,"code-v1",1,bytes("after-event"));
        check(store.commitOwnedTurn(new DurableActorStore.Turn(key,1,0,"delivery-1",updated,
            List.of(outbound,outbound2)),owner)==1,"durable event + two outbox intents");
        check(gate.admitSignedPush(key,new PostgresDurableWorkflowGate.SignedPush(
            9,10,ref,sha,"delivery-after-state-advanced",bytes("replayed"))) ==
            PostgresDurableWorkflowGate.Admission.STALE,
            "waiting event cannot enqueue after actor advances version");

        var claim=gate.claimNext(key,1,owner,UUID.randomUUID());
        check(claim!=null && claim.intent().intentId().equals("op-1") && claim.attempts()==1,
            "fenced claim");
        try(Connection c=ds.getConnection();PreparedStatement p=c.prepareStatement(
            "SELECT o.claim_until<=a.lease_until FROM ores_durable_outbox o "+
            "JOIN ores_durable_actor a USING(tenant_id,workflow_id,run_id,node_id) "+
            "WHERE o.tenant_id=? AND o.workflow_id=? AND o.run_id=? "+
            "AND o.node_id=? AND o.intent_id=?")){
            p.setString(1,key.tenantId());p.setString(2,key.workflowId());
            p.setString(3,key.runId());p.setString(4,key.nodeId());
            p.setString(5,"op-1");
            try(var r=p.executeQuery()){
                check(r.next()&&r.getBoolean(1),"claim deadline bounded by activation lease");
            }
        }
        rejects(()->gate.recordReceipt(key,1,intruder,claim,"forged-receipt"),
            "other owner cannot acknowledge a claimed operation");
        var second=gate.claimNext(key,1,owner,UUID.randomUUID());
        check(second!=null && second.intent().intentId().equals("op-2"),"second independent claim");
        check(gate.claimNext(key,1,owner,UUID.randomUUID())==null,
            "no duplicate claim before expiry");
        check(gate.recordReceipt(key,1,owner,claim,"github-run-101"),"first receipt");
        check(!gate.recordReceipt(key,1,owner,claim,"github-run-101"),"same receipt is idempotent");
        rejects(()->gate.recordReceipt(key,1,owner,claim,"different-receipt"),
            "receipt conflict");

        expire(ds,key); // controlled simulation of crashed owner/expired database lease
        check(!store.renewLease(key,1,owner),"expired lease cannot be resurrected");
        var recoveryOwner=UUID.randomUUID();
        var b=store.acquireLease(key,recoveryOwner);
        check(b.activation().epoch()==2 && b.activation().pendingOutbox().size()==1,
            "expired actor reactivated and pending operation retained");
        rejects(()->gate.claimNext(key,1,owner,UUID.randomUUID()),"old epoch claim fenced");
        rejects(()->gate.recordReceipt(key,1,owner,second,"stale-result"),
            "old receipt fenced");
        expireClaim(ds,key); // controlled simulation of expired external operation claim
        var retry=gate.claimNext(key,2,recoveryOwner,UUID.randomUUID());
        check(retry!=null && retry.intent().intentId().equals("op-2") && retry.attempts()==2,
            "operation is reclaimable with same idempotency key");
        check(retry.intent().idempotencyKey().equals(second.intent().idempotencyKey()),
            "retry retains external idempotency identity");
        rejects(()->gate.recordReceipt(key,2,recoveryOwner,second,"old-claim"),
            "stale claim token cannot acknowledge");
        check(gate.recordReceipt(key,2,recoveryOwner,retry,"github-run-102"),"new claim receipt");
        check(store.renewLease(key,2,recoveryOwner),"new owner can renew");
        System.out.println("POSTGRES_WORKFLOW_GATE_PASS");
    }
}
