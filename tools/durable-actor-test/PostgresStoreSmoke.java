package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import org.postgresql.ds.PGSimpleDataSource;

/** Standalone integration test, executed with a dedicated disposable PostgreSQL database. */
public final class PostgresStoreSmoke {
    private static void check(boolean yes, String why) {
        if (!yes) throw new AssertionError(why);
    }
    private static void rejects(Runnable action, String why) {
        try { action.run(); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("expected rejection: " + why);
    }
    private static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    public static void main(String[] args) throws Exception {
        String jdbc = System.getenv("AI_DEV_LOOP_JDBC_URL");
        String user = System.getenv("AI_DEV_LOOP_DB_USER");
        String pass = System.getenv("AI_DEV_LOOP_DB_PASSWORD");
        if (jdbc == null || user == null || pass == null)
            throw new IllegalStateException("test database environment required");
        var ds = new PGSimpleDataSource();
        ds.setURL(jdbc); ds.setUser(user); ds.setPassword(pass);

        var first = new PostgresDurableActorStore(ds);
        var key = new DurableActorStore.Address("tenant-a", "workflow", "run", "editor");
        var other = new DurableActorStore.Address("tenant-b", "workflow", "run", "editor");
        var initial = new DurableActorStore.Snapshot(0, 1, "code-gen-A", 0, bytes("start"));
        check(first.initialize(key, initial), "initialized");
        check(!first.initialize(key, initial), "initialization idempotent");
        check(first.initialize(other, initial), "tenant isolation registration");
        var m = new DurableActorStore.Inbound("msg-1", bytes("request"));
        check(first.enqueue(key,m), "enqueued");
        check(!first.enqueue(key,m), "deduplicated");
        check(first.enqueue(other,m), "different tenant permitted");

        var a = first.activate(key);
        check(a.epoch()==1 && a.inbox().size()==1, "first activation loads inbox");
        var outbound = new DurableActorStore.Outbound("intent-1","workflow:run:push:1",bytes("push"));
        var committed = new DurableActorStore.Snapshot(0,1,"code-gen-A",1,bytes("committed"));
        check(first.commitTurn(new DurableActorStore.Turn(key,a.epoch(),0,"msg-1",committed,List.of(outbound)))==1,
            "atomic first transition");

        // Simulate process loss: use a fresh store adapter against the same live database.
        var rebooted = new PostgresDurableActorStore(ds);
        var b = rebooted.activate(key);
        check(b.epoch()==2 && b.inbox().isEmpty(), "persisted acked inbox and new epoch");
        check(b.snapshot().stateVersion()==1, "persisted state");
        check(new String(b.snapshot().encodedState(),StandardCharsets.UTF_8).equals("committed"),"state bytes survive restart");
        check(b.pendingOutbox().size()==1, "pending operation survives restart");
        rejects(() -> first.hibernate(key,a.epoch()),"stale hibernate");
        rejects(() -> first.acknowledge(key,a.epoch(),"intent-1","receipt"),"stale receipt");
        rejects(() -> first.commitTurn(new DurableActorStore.Turn(key,a.epoch(),1,"msg-1",
            new DurableActorStore.Snapshot(0,1,"code-gen-A",2,bytes("BAD")),List.of())),"stale commit");

        check(rebooted.acknowledge(key,b.epoch(),"intent-1","external-run-123"),"receipt saved");
        check(!rebooted.acknowledge(key,b.epoch(),"intent-1","external-run-123"),"receipt idempotent");
        rejects(() -> rebooted.acknowledge(key,b.epoch(),"intent-1","conflicting-receipt"),"conflicting receipt");
        rebooted.hibernate(key,b.epoch());
        check(rebooted.enqueue(key,new DurableActorStore.Inbound("msg-2",bytes("next"))),"dormant inbox");
        var c = rebooted.activate(key);
        check(c.epoch()==3 && c.inbox().size()==1 && c.pendingOutbox().isEmpty(),"wake with unconsumed mail");

        // A duplicate intent must roll back BOTH inbox consumption and state mutation.
        var bad = new DurableActorStore.Outbound("intent-1","different-key",bytes("duplicate"));
        var next = new DurableActorStore.Snapshot(0,1,"code-gen-A",2,bytes("next"));
        rejects(() -> rebooted.commitTurn(new DurableActorStore.Turn(key,c.epoch(),1,"msg-2",next,List.of(bad))),
            "duplicate outbox intent rollback");
        var d = rebooted.activate(key);
        check(d.epoch()==4 && d.snapshot().stateVersion()==1 && d.inbox().size()==1,
            "failed transaction rolled back all tables");

        check(rebooted.commitTurn(new DurableActorStore.Turn(key,d.epoch(),1,"msg-2",next,List.of()))==2,
            "replay after rollback");
        check(rebooted.activate(other).snapshot().stateVersion()==0, "tenant isolated");
        System.out.println("POSTGRES_DURABLE_ACTOR_INTEGRATION_PASS");
    }
}
