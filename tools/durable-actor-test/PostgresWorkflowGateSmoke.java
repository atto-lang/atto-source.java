package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.postgresql.ds.PGSimpleDataSource;

/** Real PostgreSQL integration for webhook state-CAS and outbox reconciliation. */
public final class PostgresWorkflowGateSmoke {
    static byte[] bytes(String x){return x.getBytes(StandardCharsets.UTF_8);}
    static void check(boolean yes,String msg){if(!yes)throw new AssertionError(msg);}
    static void fail(Runnable r,String msg) {
        try{r.run();}catch(IllegalStateException expected){return;}
        throw new AssertionError("expected failure: "+msg);
    }
    static void exec(PGSimpleDataSource source,String sql,DurableActorStore.Address a) throws Exception {
        try(Connection c=source.getConnection();PreparedStatement p=c.prepareStatement(sql)){
            p.setString(1,a.tenantId());p.setString(2,a.workflowId());
            p.setString(3,a.runId());p.setString(4,a.nodeId());
            check(p.executeUpdate()==1,"fixture changed exactly one row");
        }
    }
    public static void main(String[] args)throws Exception {
        var source=new PGSimpleDataSource();
        source.setURL(System.getenv("AI_DEV_LOOP_JDBC_URL"));
        source.setUser(System.getenv("AI_DEV_LOOP_DB_USER"));
        source.setPassword(System.getenv("AI_DEV_LOOP_DB_PASSWORD"));
        var db=new PostgresDurableActorStore(source);
        var gate=new PostgresDurableWorkflowGate(source,db);
        var address=new DurableActorStore.Address("tenant-a","wf","webhook","node");
        var other=new DurableActorStore.Address("tenant-b","wf","webhook","node");
        var initial=new DurableActorStore.Snapshot(0,1,"code-a",0,bytes("v0"));
        check(db.initialize(address,initial),"first actor created");
        check(db.initialize(other,initial),"other tenant created");
        String sha="a".repeat(40);
        gate.stage(new PostgresDurableWorkflowGate.Expected(address,7,13,sha,
                "refs/heads/feature",1,0));
        byte[] raw=bytes("{\"after\":\""+sha+"\"}");
        check(gate.accept("tenant-b",7,13,sha,"refs/heads/feature","delivery-1",raw)==
            PostgresDurableWorkflowGate.Admission.STALE,"tenant isolation");
        check(gate.accept("tenant-a",7,13,"b".repeat(40),"refs/heads/feature",
                "delivery-1",raw)==PostgresDurableWorkflowGate.Admission.STALE,"SHA gate");
        check(gate.accept("tenant-a",7,13,sha,"refs/heads/wrong","delivery-1",raw)==
            PostgresDurableWorkflowGate.Admission.STALE,"ref gate");

        var ready=new CountDownLatch(1);
        var admitted=new AtomicInteger();
        var duplicates=new AtomicInteger();
        var workers=Executors.newFixedThreadPool(8);
        var futures=new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for(int i=0;i<8;i++){
            futures.add(workers.submit(()->{
                try {
                    ready.await();
                    var accepted=gate.accept("tenant-a",7,13,sha,"refs/heads/feature",
                        "delivery-1",raw);
                    if(accepted==PostgresDurableWorkflowGate.Admission.ACCEPTED)admitted.incrementAndGet();
                    if(accepted==PostgresDurableWorkflowGate.Admission.DUPLICATE)duplicates.incrementAndGet();
                }catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
            }));
        }
        ready.countDown();
        for(var future:futures)future.get(30,TimeUnit.SECONDS);
        workers.shutdown();
        check(admitted.get()==1 && duplicates.get()==7,"one durable event for eight racing webhooks");
        // Exercise the actor->expectation lock order against concurrent staging
        // and webhook delivery. Neither path may deadlock or lose valid mail.
        var racing=Executors.newFixedThreadPool(2);
        var go=new CountDownLatch(1);
        var restage=racing.submit(()->{
            try{go.await();for(int i=0;i<20;i++)
                gate.stage(new PostgresDurableWorkflowGate.Expected(address,7,13,sha,
                    "refs/heads/feature",1,0));
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
        });
        var webhook=racing.submit(()->{
            try{go.await();for(int i=0;i<20;i++)
                check(gate.accept("tenant-a",7,13,sha,"refs/heads/feature",
                    "race-"+i,raw)==PostgresDurableWorkflowGate.Admission.ACCEPTED,
                    "racing stage preserved matching webhook");
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
        });
        go.countDown();
        restage.get(30,TimeUnit.SECONDS);
        webhook.get(30,TimeUnit.SECONDS);
        racing.shutdown();
        var a=db.activate(address);
        var stranger=new PostgresDurableWorkflowGate(source,new PostgresDurableActorStore(source));
        fail(()->stranger.claim(address,a.epoch(),30),"non-owner cannot claim outbox");
        check(a.inbox().size()==21 && a.epoch()==1,"authenticated events delivered without deadlock");
        var o1=new DurableActorStore.Outbound("push-1","unique:push:1",bytes("git"));
        var o2=new DurableActorStore.Outbound("test-1","unique:test:1",bytes("CI"));
        var v1=new DurableActorStore.Snapshot(0,1,"code-a",1,bytes("v1"));
        check(db.commitTurn(new DurableActorStore.Turn(address,a.epoch(),0,
            "delivery-1",v1,List.of(o1,o2)))==1,"state advance");
        check(gate.accept("tenant-a",7,13,sha,"refs/heads/feature","delivery-2",raw)==
            PostgresDurableWorkflowGate.Admission.STALE,"stale expectation state version");

        var claim1=gate.claim(address,a.epoch(),30);
        var claim2=gate.claim(address,a.epoch(),30);
        check(claim1!=null && claim2!=null && !claim1.token().equals(claim2.token()),"unique claims");
        check(gate.claim(address,a.epoch(),30)==null,"claim lease prevents immediate retry");
        check(gate.finish(claim1,"github-receipt-1"),"receipt persisted");
        fail(()->gate.finish(claim1,"different"),"already complete");
        exec(source,"UPDATE ores_durable_outbox SET claim_until=clock_timestamp()-interval '1 second'"+
            " WHERE tenant_id=? AND workflow_id=? AND run_id=? AND node_id=?"+
            " AND intent_id='test-1'",address);
        var retry=gate.claim(address,a.epoch(),30);
        check(retry!=null && retry.intent().idempotencyKey().equals("unique:test:1") &&
            retry.attempt()==2,"retry preserves external idempotency key");
        fail(()->gate.finish(claim2,"old-receipt"),"stale claim token fenced");
        check(gate.finish(retry,"ci-receipt-2"),"retry receipt confirmed");
        check(gate.claim(address,a.epoch(),30)==null,"nothing left to dispatch");

        var in=new DurableActorStore.Inbound("m2",bytes("another"));
        check(db.enqueue(address,in),"second message enqueued");
        var v2=new DurableActorStore.Snapshot(0,1,"code-a",2,bytes("v2"));
        var pending=new DurableActorStore.Outbound("push-2","unique:push:2",bytes("git2"));
        check(db.commitTurn(new DurableActorStore.Turn(address,a.epoch(),1,"m2",v2,
                List.of(pending)))==2,"second commit");
        var vulnerable=gate.claim(address,a.epoch(),30);
        check(vulnerable!=null,"claim before takeover");
        exec(source,"UPDATE ores_durable_actor SET lease_expires_at=clock_timestamp()-interval '1 second'"+
            " WHERE tenant_id=? AND workflow_id=? AND run_id=? AND node_id=?",address);
        var fresh=new PostgresDurableActorStore(source);
        var b=fresh.activate(address);
        var freshGate=new PostgresDurableWorkflowGate(source,fresh);
        check(b.epoch()==2,"supervised reactivation after lease expiry");
        fail(()->gate.finish(vulnerable,"late-receipt"),"previous epoch rejected");
        fail(()->gate.claim(address,b.epoch(),30),"stale owner cannot claim new epoch");
        // An old outbox claim isn't automatically stolen while its own timer remains alive.
        check(freshGate.claim(address,b.epoch(),30)==null,"claim lease honored on new epoch");
        exec(source,"UPDATE ores_durable_outbox SET claim_until=clock_timestamp()-interval '1 second'"+
            " WHERE tenant_id=? AND workflow_id=? AND run_id=? AND node_id=?"+
            " AND intent_id='push-2'",address);
        var takeover=freshGate.claim(address,b.epoch(),30);
        check(takeover!=null && takeover.attempt()==2,"expired claim reclaimed");
        fail(()->gate.finish(takeover,"forged-owner"),"wrong owner cannot finish new claim");
        check(freshGate.finish(takeover,"success-after-restart"),"reconciled");

        // A delivery is unique across actors even after the first workflow
        // finishes and a different actor begins waiting for the same push.
        var second=new DurableActorStore.Address("tenant-a","wf","next-workflow","node");
        check(db.initialize(second,initial),"second actor initialized");
        exec(source,"UPDATE ores_durable_webhook_expectation SET stage='closed'"+
            " WHERE tenant_id=? AND workflow_id=? AND run_id=? AND node_id=?",address);
        gate.stage(new PostgresDurableWorkflowGate.Expected(second,7,13,sha,
            "refs/heads/feature",1,0));
        check(gate.accept("tenant-a",7,13,sha,"refs/heads/feature","delivery-1",raw)==
            PostgresDurableWorkflowGate.Admission.DUPLICATE,"cross-workflow replay prevented");
        check(db.activate(second).inbox().isEmpty(),"replay did not reach second actor");
        fail(()->gate.accept("tenant-a",7,13,"b".repeat(40),
            "refs/heads/feature","delivery-1",raw),
            "same delivery identity cannot change signed source metadata");
        System.out.println("POSTGRES_WORKFLOW_GATE_PASS");
    }
}
