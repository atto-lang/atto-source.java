package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
    /** Simulate lease deadline after abrupt process loss; never used in the host adapter. */
    private static void expire(org.postgresql.ds.PGSimpleDataSource ds,
                               DurableActorStore.Address key) throws Exception {
        try (var c=ds.getConnection(); var p=c.prepareStatement(
                "UPDATE ores_durable_actor SET lease_expires_at=clock_timestamp()-interval '1 second' " +
                "WHERE tenant_id=? AND workflow_id=? AND run_id=? AND node_id=?")) {
            p.setString(1,key.tenantId()); p.setString(2,key.workflowId());
            p.setString(3,key.runId()); p.setString(4,key.nodeId());
            if(p.executeUpdate()!=1)throw new AssertionError("lease fixture failed");
        }
    }


    public static void main(String[] args) throws Exception {
        String jdbc = System.getenv("AI_DEV_LOOP_JDBC_URL");
        String user = System.getenv("AI_DEV_LOOP_DB_USER");
        String pass = System.getenv("AI_DEV_LOOP_DB_PASSWORD");
        if (jdbc == null || user == null || pass == null)
            throw new IllegalStateException("test database environment required");
        var ds = new PGSimpleDataSource();
        ds.setURL(jdbc); ds.setUser(user); ds.setPassword(pass);
        String schemaFile = System.getenv("AI_DEV_LOOP_SCHEMA_FILE");
        if (schemaFile == null) throw new IllegalStateException("test schema path required");
        try (Connection c = ds.getConnection(); Statement statement = c.createStatement()) {
            statement.execute(Files.readString(Path.of(schemaFile)));
        }

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
        rejects(() -> rebooted.activate(key),"live actor cannot be displaced");
        check(first.renew(key,a.epoch()),"owned actor can renew");
        expire(ds,key);
        check(!first.renew(key,a.epoch()),"expired actor cannot renew");
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
        expire(ds,key); // restart after a failed transaction
        var d = rebooted.activate(key);
        check(d.epoch()==4 && d.snapshot().stateVersion()==1 && d.inbox().size()==1,
            "failed transaction rolled back all tables");

        check(rebooted.commitTurn(new DurableActorStore.Turn(key,d.epoch(),1,"msg-2",next,List.of()))==2,
            "replay after rollback");
        check(rebooted.activate(other).snapshot().stateVersion()==0, "tenant isolated");
        // Host coordinator seam enforces a runtime-provided quiescence check.
        var unsafe = new DurableActivationCoordinator(rebooted, address -> false);
        rejects(() -> unsafe.restore(key), "unsafe activate");
        rejects(() -> unsafe.freeze(key, d.epoch()), "unsafe freeze");
        var coordinator = new DurableActivationCoordinator(rebooted, address -> true);
        check(coordinator.deliver(key,
            new DurableActorStore.Inbound("msg-3",bytes("wake"))), "dormant queue");
        expire(ds,key); // supervised takeover after worker loss
        var restored = coordinator.restore(key);
        check(restored.epoch()==5 && restored.inbox().size()==1,
            "coordinator persisted identity and delivered mail");
        coordinator.freeze(key,restored.epoch());
        rejects(() -> coordinator.freeze(key,restored.epoch()), "double freeze fenced");
        System.out.println("POSTGRES_DURABLE_ACTOR_INTEGRATION_PASS");
    }
}
