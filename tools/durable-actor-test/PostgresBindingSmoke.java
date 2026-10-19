package dev.oreslang.runtime;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Exact-runtime + PostgreSQL v0 handoff integration. Schema, activation,
 * inbox, lease, state and outbox live in a real database. A Java actor uses
 * the actual ActorRuntime scheduling / quiescence protocol.
 */
public final class PostgresBindingSmoke {
    private static void check(boolean value, String why) {
        if (!value) throw new AssertionError(why);
    }

    private static void rejects(Runnable action, String why) {
        try { action.run(); }
        catch (IllegalArgumentException | IllegalStateException accepted) { return; }
        throw new AssertionError("expected rejection: " + why);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    public static void main(String[] ignored) throws Exception {
        var ds = new PGSimpleDataSource();
        ds.setURL(System.getenv("AI_DEV_LOOP_JDBC_URL"));
        ds.setUser(System.getenv("AI_DEV_LOOP_DB_USER"));
        ds.setPassword(System.getenv("AI_DEV_LOOP_DB_PASSWORD"));
        try (Connection c = ds.getConnection(); Statement statement = c.createStatement()) {
            statement.execute(Files.readString(Path.of(System.getenv("AI_DEV_LOOP_SCHEMA_FILE"))));
        }
        var store = new PostgresDurableActorStore(ds);
        // Advisory callback deliberately says false: no advisory predicate
        // can grant this operation; the real ActorRuntime proof is required.
        var coordinator = new DurableActivationCoordinator(store, key -> false);
        var key = new DurableActorStore.Address(
                "binding-tenant", "workflow", "run", UUID.randomUUID().toString());
        var initial = new DurableActorStore.Snapshot(0, 1, "code-v1", 0, bytes("initial"));
        check(store.initialize(key, initial), "actor registered");
        check(coordinator.deliver(key, new DurableActorStore.Inbound("input-1", bytes("input"))),
                "inbox committed before actor acknowledgment");
        var owner = UUID.randomUUID();
        var lease = store.acquireLease(key, owner);
        var next = new DurableActorStore.Snapshot(0, 1, "code-v1", 1, bytes("done"));
        var outgoing = new DurableActorStore.Outbound("intent-1", "run:actor:1", bytes("operation"));
        var turn = new DurableActorStore.Turn(
                key, lease.activation().epoch(), 0, "input-1", next, List.of(outgoing));

        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(2, 2, 1, 32));
             var io = Executors.newSingleThreadExecutor()) {
            var ref = runtime.<String>spawnShared(() -> (mail, context) -> {});
            ref.ready().get(5, TimeUnit.SECONDS);
            rejects(() -> DurableActorBinding.bind(runtime, ref, key, lease,
                    "mismatched-code", coordinator), "generation mismatch");
            var binding = DurableActorBinding.bind(
                    runtime, ref, key, lease, "code-v1", coordinator);
            rejects(() -> binding.commitAndHibernateAsync(new DurableActorStore.Turn(
                    key, lease.activation().epoch(), 0, "not-inbox", next, List.of()), io),
                    "consumed delivery must exist in leased activation");
            check(binding.commitAndHibernateAsync(turn, io).get(10, TimeUnit.SECONDS) == 1,
                    "atomic commit plus hibernate");
            ref.done().get(5, TimeUnit.SECONDS);
            check(!ref.isAlive(), "old runtime incarnation stopped");
            rejects(() -> store.commitOwnedTurn(turn, owner),
                    "old lease cannot commit after hibernation");
            var restored = store.acquireLease(key, UUID.randomUUID());
            check(restored.activation().epoch() == lease.activation().epoch() + 1,
                    "new owner acquired new epoch");
            check(restored.activation().snapshot().stateVersion() == 1,
                    "latest state survived hibernate");
            check(new String(restored.activation().snapshot().encodedState(),
                    StandardCharsets.UTF_8).equals("done"), "snapshot persisted");
            check(restored.activation().inbox().isEmpty(), "inbox consumed only once");
            check(restored.activation().pendingOutbox().size() == 1,
                    "outbox intent committed in same transaction");
        }

        // A forged owner may not mutate state or release the lease. The bridge
        // fails its own actor closed after uncertain PostgreSQL rejection.
        var badKey = new DurableActorStore.Address(
                "binding-tenant", "workflow", "run", UUID.randomUUID().toString());
        check(store.initialize(badKey, initial), "second actor registered");
        check(coordinator.deliver(badKey, new DurableActorStore.Inbound("input-2", bytes("input"))),
                "second inbox persisted");
        var validOwner = UUID.randomUUID();
        var leased = store.acquireLease(badKey, validOwner);
        var fake = new PostgresDurableActorStore.LeaseActivation(
                leased.activation(), UUID.randomUUID());
        var failedTurn = new DurableActorStore.Turn(
                badKey, leased.activation().epoch(), 0, "input-2", next, List.of());
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(2, 2, 1, 32));
             var io = Executors.newSingleThreadExecutor()) {
            var ref = runtime.<String>spawnShared(() -> (mail, context) -> {});
            ref.ready().get(5, TimeUnit.SECONDS);
            var binding = DurableActorBinding.bind(
                    runtime, ref, badKey, fake, "code-v1", coordinator);
            try {
                binding.commitAndHibernateAsync(failedTurn, io).get(10, TimeUnit.SECONDS);
                throw new AssertionError("forged DB owner unexpectedly committed");
            } catch (java.util.concurrent.ExecutionException expected) {
                check(expected.getCause() instanceof PostgresDurableActorStore.StaleActivationException,
                        "forged owner failed with DB fencing exception");
            }
            ref.done().get(5, TimeUnit.SECONDS); // cancel may fail done future: handled below
        } catch (java.util.concurrent.CancellationException
                 | java.util.concurrent.ExecutionException expectedActorCancellation) {
            // Runtime stops when the owner is rejected; no unsafe resume.
        }
        rejects(() -> store.acquireLease(badKey, UUID.randomUUID()),
                "original owner remains healthy");
        check(store.renewLease(badKey, leased.activation().epoch(), validOwner),
                "original healthy owner retained lease");
        store.hibernateLease(badKey, leased.activation().epoch(), validOwner);
        var recovered = store.acquireLease(badKey, UUID.randomUUID()).activation();
        check(recovered.snapshot().stateVersion() == 0
                && recovered.inbox().size() == 1
                && recovered.pendingOutbox().isEmpty(),
                "failed atomic handoff left snapshot/inbox/outbox unchanged");

        System.out.println("POSTGRES_BINDING_ATOMIC_HANDOFF_PASS");
    }
}
