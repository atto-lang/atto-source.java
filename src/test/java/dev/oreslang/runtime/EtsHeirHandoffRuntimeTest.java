package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Actual ActorRuntime integration, not guest compiled code/route publication.
 * The host/supervisor must retire the old physical ActorRef before the
 * runtime-minted ETS table receipt may grant a new owner.
 */
@Timeout(30)
final class EtsHeirHandoffRuntimeTest {
    private record Actors(
            ActorRuntime.ActorRef<Integer> oldOwner,
            ActorRuntime.ActorRef<Integer> heir,
            EtsTable<String, Long> table) { }

    private static Actors start(ActorRuntime runtime) throws Exception {
        CountDownLatch created = new CountDownLatch(1);
        CountDownLatch successorReady = new CountDownLatch(1);
        AtomicReference<EtsTable<String, Long>> owned = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        var old = runtime.spawnSharedTrusted(factory -> (message, context) -> {
            if (message == 1) {
                try {
                    EtsTable<String, Long> table =
                            context.runtime().createEtsTable(4, EtsTable.Access.PRIVATE);
                    table.put("counter", 42L);
                    owned.set(table);
                } catch (Throwable error) {
                    failure.compareAndSet(null, error);
                } finally { created.countDown(); }
            }
        });
        old.send(1);
        assertTrue(created.await(5, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertNotNull(owned.get());

        var heir = runtime.spawnSharedTrusted(factory -> (message, context) -> {
            if (message == 1) successorReady.countDown();
        });
        heir.send(1);
        assertTrue(successorReady.await(5, TimeUnit.SECONDS));
        assertTrue(heir.ready().isDone(), "heir must finish on_start");
        return new Actors(old, heir, owned.get());
    }

    @Test
    void acknowledgedHeirOwnsSameRowsAfterPhysicalOwnerRetirement()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actors = start(runtime);
            var table = actors.table();
            assertEquals(actors.oldOwner().id(), table.ownerId().orElseThrow());
            assertEquals(1L, table.ownerEpoch());

            var receipt = runtime.prepareEtsHeir(
                    table, actors.oldOwner(), actors.heir());
            assertThrows(IllegalStateException.class,
                    () -> runtime.prepareEtsHeir(
                            table, actors.oldOwner(), actors.heir()));
            assertFalse(runtime.acknowledgeEtsHeir(receipt),
                    "old worker is still live: no dual writer");
            assertEquals(1L, table.ownerEpoch());

            actors.oldOwner().stop();
            assertTrue(actors.oldOwner().awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(table.closed(), "pending table remains fenced for heir");
            assertEquals(42L, table.get("counter").orElseThrow());
            assertTrue(runtime.acknowledgeEtsHeir(receipt));
            assertFalse(runtime.acknowledgeEtsHeir(receipt));
            assertEquals(actors.heir().id(), table.ownerId().orElseThrow());
            assertEquals(2L, table.ownerEpoch());
            assertEquals(42L, table.get("counter").orElseThrow());
            assertEquals(1, table.size());

            actors.heir().stop();
            assertTrue(actors.heir().awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(table.closed(), "heir owns retirement cleanup");
        }
    }

    @Test
    void forgedReceiptCannotAdvanceEpochOrReleaseFence() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actors = start(runtime);
            var ticket = runtime.prepareEtsHeir(
                    actors.table(), actors.oldOwner(), actors.heir());
            var fake = new ActorRuntime.EtsHeirHandoff(
                    actors.table(),
                    new EtsTable.HeirReceipt(
                            UUID.randomUUID(), ticket.previousOwner(),
                            ticket.nextOwner(), ticket.receipt().nextEpoch()),
                    ticket.previousOwner(), ticket.nextOwner());
            actors.oldOwner().stop();
            assertTrue(actors.oldOwner().awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(runtime.acknowledgeEtsHeir(fake));
            assertEquals(1L, actors.table().ownerEpoch());
            assertTrue(runtime.acknowledgeEtsHeir(ticket));
            actors.heir().stop();
            assertTrue(actors.heir().awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void preRetirementRollbackRestoresOriginalOwner() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actors = start(runtime);
            var ticket = runtime.prepareEtsHeir(
                    actors.table(), actors.oldOwner(), actors.heir());
            assertTrue(runtime.rollbackEtsHeir(ticket));
            assertFalse(runtime.rollbackEtsHeir(ticket));
            assertFalse(runtime.acknowledgeEtsHeir(ticket));
            assertEquals(1L, actors.table().ownerEpoch());
            assertEquals(actors.oldOwner().id(), actors.table().ownerId().orElseThrow());
            assertEquals(42L, actors.table().get("counter").orElseThrow());
            actors.oldOwner().stop();
            assertTrue(actors.oldOwner().awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actors.table().closed());
            actors.heir().stop();
            assertTrue(actors.heir().awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void heirDeathBeforeAckClosesTableWithoutOpeningOldOwner() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var actors = start(runtime);
            var ticket = runtime.prepareEtsHeir(
                    actors.table(), actors.oldOwner(), actors.heir());
            actors.oldOwner().stop();
            assertTrue(actors.oldOwner().awaitTermination(5, TimeUnit.SECONDS));
            actors.heir().stop();
            assertTrue(actors.heir().awaitTermination(5, TimeUnit.SECONDS));
            assertFalse(runtime.acknowledgeEtsHeir(ticket));
            assertTrue(actors.table().closed(), "dead heir must not retain orphan ETS");
            assertThrows(IllegalStateException.class,
                    () -> actors.table().get("counter"));
        }
    }

    @Test
    void cannotCrossRuntimeOrTransferHostOwnedTable() throws Exception {
        try (var runtime = new ActorRuntime();
             var foreign = new ActorRuntime()) {
            var actors = start(runtime);
            var foreignHeir = foreign.spawnSharedTrusted(
                    factory -> (message, context) -> { });
            assertThrows(SecurityException.class,
                    () -> runtime.prepareEtsHeir(
                            actors.table(), actors.oldOwner(), foreignHeir));
            var hostTable = runtime.<String, Long>createEtsTable(
                    2, EtsTable.Access.PUBLIC);
            assertThrows(SecurityException.class,
                    () -> runtime.prepareEtsHeir(
                            hostTable, actors.oldOwner(), actors.heir()));
            assertEquals(1L, actors.table().ownerEpoch());
            foreignHeir.stop();
            assertTrue(foreignHeir.awaitTermination(5, TimeUnit.SECONDS));
            actors.oldOwner().stop();
            actors.heir().stop();
            assertTrue(actors.oldOwner().awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(actors.heir().awaitTermination(5, TimeUnit.SECONDS));
            hostTable.close();
        }
    }
}
