package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.postgresql.ds.PGSimpleDataSource;

/** PostgreSQL-backed regression coverage for per-actor bounded queues. */
public final class PostgresQuotaSmoke {
    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void ok(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("expected quota/lease rejection: " + message);
    }
    public static void main(String[] args) throws Exception {
        var ds = new PGSimpleDataSource();
        ds.setURL(System.getenv("AI_DEV_LOOP_JDBC_URL"));
        ds.setUser(System.getenv("AI_DEV_LOOP_DB_USER"));
        ds.setPassword(System.getenv("AI_DEV_LOOP_DB_PASSWORD"));
        var store = new PostgresDurableActorStore(ds, 2, 1);
        var address = new DurableActorStore.Address("tenant-q", "wf", "quota", "node");
        var initial = new DurableActorStore.Snapshot(0,1,"code-v1",0,bytes("zero"));
        ok(store.initialize(address, initial), "initialized");
        var m1 = new DurableActorStore.Inbound("m1", bytes("a"));
        var m2 = new DurableActorStore.Inbound("m2", bytes("b"));
        ok(store.enqueue(address,m1), "first inbox entry");
        ok(store.enqueue(address,m2), "second inbox entry");
        ok(!store.enqueue(address,m1), "duplicate accepted as no-op even when full");
        rejects(() -> store.enqueue(address,new DurableActorStore.Inbound("m3",bytes("c"))),
            "inbox admission must stop at two entries");

        var first=store.activate(address);
        ok(first.inbox().size()==2, "bounded activation");
        var next = new DurableActorStore.Snapshot(0,1,"code-v1",1,bytes("one"));
        var firstIntent = new DurableActorStore.Outbound("o1","idempotent-key-one",bytes("push"));
        ok(store.commitTurn(new DurableActorStore.Turn(address,first.epoch(),0,
            "m1",next,List.of(firstIntent)))==1,"first atomic commit");
        ok(store.enqueue(address,new DurableActorStore.Inbound("m3",bytes("c"))),
            "consumption frees one inbox slot");
        var blocked= new DurableActorStore.Outbound("o2","idempotent-key-two",bytes("test"));
        var two = new DurableActorStore.Snapshot(0,1,"code-v1",2,bytes("two"));
        rejects(() -> store.commitTurn(new DurableActorStore.Turn(address,first.epoch(),1,
            "m2",two,List.of(blocked))),"pending outbox must stop at one entry");
        var rebooted = new PostgresDurableActorStore(ds,2,1).activate(address);
        ok(rebooted.snapshot().stateVersion()==1 && rebooted.inbox().size()==2 &&
            rebooted.pendingOutbox().size()==1,"outbox limit rolls back state and consumption");
        ok(store.acknowledge(address,rebooted.epoch(),"o1","receipt-1"),"clear pending outbox");
        ok(store.commitTurn(new DurableActorStore.Turn(address,rebooted.epoch(),1,
            "m2",two,List.of(blocked)))==2,"outbox capacity recovers after receipt");

        // Eight independent writers race for one available inbox slot. An actor
        // row lock must make quota check and insertion one serialization point.
        var raceStore = new PostgresDurableActorStore(ds,1,1);
        var race = new DurableActorStore.Address("tenant-q","wf","concurrent","node");
        ok(raceStore.initialize(race,initial), "race actor initialized");
        var started=new CountDownLatch(1);
        var pool=Executors.newFixedThreadPool(8);
        var admitted=new AtomicInteger();
        var rejected=new AtomicInteger();
        var futures=new ArrayList<java.util.concurrent.Future<?>>();
        for(int i=0;i<8;i++){
            final int n=i;
            futures.add(pool.submit(()->{
                try {
                    started.await();
                    if(raceStore.enqueue(race,new DurableActorStore.Inbound("r"+n,bytes("x"))))
                        admitted.incrementAndGet();
                }catch(IllegalStateException quota){rejected.incrementAndGet();}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}
            }));
        }
        started.countDown();
        for(var future:futures)future.get(30,TimeUnit.SECONDS);
        pool.shutdown();
        ok(admitted.get()==1 && rejected.get()==7,
            "quota enforced across concurrent DB transactions");
        ok(raceStore.activate(race).inbox().size()==1,"only one durable message committed");
        System.out.println("POSTGRES_DURABLE_QUOTA_PASS");
    }
}
