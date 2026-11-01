package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live ActorRuntime + ETS lifecycle proof for the supervisor-owned table
 * portion of UHL-1. This intentionally does NOT claim executable Oreslang
 * source hot swapping, ETS heir migration or route/ETS atomic publication.
 *
 * A supervisor-owned table must be independent of a particular worker's
 * physical ActorId and actor-private heap. The old worker's retirement must
 * not destroy or expose it to a replacement worker's mutation.
 */
@Timeout(20)
final class EtsSupervisedReplacementLifecycleTest {
    @Test
    void supervisorTableSurvivesTwoPhysicalWorkersAndRetainsFrozenRows()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            EtsTable<String, Map<String, Long>> table =
                    runtime.createEtsTable(4, EtsTable.Access.PROTECTED);
            assertTrue(table.ownerId().isEmpty(),
                    "supervisor-owned ETS must not be attached to a worker lifetime");
            Map<String, Long> stable = Map.of("counter", 42L);
            assertTrue(table.put("state", stable));

            CountDownLatch firstDone = new CountDownLatch(1);
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> first = runtime.spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            assertEquals(stable, table.get("state").orElseThrow());
                            assertThrows(SecurityException.class,
                                    () -> table.put("state", Map.of("counter", 999L)));
                            assertThrows(SecurityException.class, table::close);
                        } catch (Throwable failure) {
                            firstFailure.set(failure);
                        } finally {
                            firstDone.countDown();
                        }
                    });
            first.send(1);
            assertTrue(firstDone.await(5, TimeUnit.SECONDS));
            assertNull(firstFailure.get(), "the old worker must observe protected ETS");
            first.stop();
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));

            // This is a *worker replacement*, not executable code-image
            // publication. The row remains protected and physically alive.
            assertFalse(table.closed());
            assertEquals(stable, table.get("state").orElseThrow());

            CountDownLatch secondDone = new CountDownLatch(1);
            AtomicReference<Throwable> secondFailure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> second = runtime.spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            assertEquals(stable, table.get("state").orElseThrow());
                            assertThrows(SecurityException.class,
                                    () -> table.remove("state"));
                            assertEquals(1, table.size());
                        } catch (Throwable failure) {
                            secondFailure.set(failure);
                        } finally {
                            secondDone.countDown();
                        }
                    });
            second.send(1);
            assertTrue(secondDone.await(5, TimeUnit.SECONDS));
            assertNull(secondFailure.get(), "replacement reads the same supervisor table");
            assertNotEquals(first.id(), second.id(),
                    "physical worker IDs must differ after replacement");
            second.stop();
            assertTrue(second.awaitTermination(5, TimeUnit.SECONDS));

            assertFalse(table.closed());
            assertEquals(1, table.size());
            assertEquals(stable, table.snapshot().get("state"));
            table.close();
            assertTrue(table.closed());
        }
    }

    @Test
    void untrustedOtherRuntimeActorCannotReadSupervisorTable() throws Exception {
        try (ActorRuntime original = new ActorRuntime();
             ActorRuntime other = new ActorRuntime()) {
            EtsTable<String, Long> table =
                    original.createEtsTable(2, EtsTable.Access.PUBLIC);
            table.put("value", 42L);

            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> attacker = other.spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            assertThrows(SecurityException.class,
                                    () -> table.get("value"));
                            assertThrows(SecurityException.class,
                                    () -> table.put("value", 100L));
                            assertThrows(SecurityException.class,
                                    table::close);
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            done.countDown();
                        }
                    });
            attacker.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            attacker.stop();
            assertTrue(attacker.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(42L, table.get("value").orElseThrow());
            table.close();
        }
    }

    @Test
    void actorOwnedTableDoesNotSurviveItsOwnerWithoutAnAuthenticatedHeir()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            CountDownLatch made = new CountDownLatch(1);
            AtomicReference<EtsTable<String, Long>> captured = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            ActorRuntime.ActorRef<Integer> owner = runtime.spawnSharedTrusted(
                    factory -> (message, context) -> {
                        try {
                            EtsTable<String, Long> table =
                                    context.runtime().createEtsTable(
                                            2, EtsTable.Access.PRIVATE);
                            table.put("state", 42L);
                            captured.set(table);
                        } catch (Throwable problem) {
                            failure.set(problem);
                        } finally {
                            made.countDown();
                            context.self().stop();
                        }
                    });
            owner.send(1);
            assertTrue(made.await(5, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertTrue(owner.awaitTermination(5, TimeUnit.SECONDS));

            EtsTable<String, Long> orphan = captured.get();
            assertNotNull(orphan);
            assertTrue(orphan.closed(),
                    "no implicit owner transfer or open orphan table is allowed");
            assertThrows(IllegalStateException.class, () -> orphan.get("state"));
        }
    }
}
