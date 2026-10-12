package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Two trusted/untrusted execution domains; never a Graal isolate per actor. */
final class SharedUntrustedActorSupervisorTest {
    private static final String WORKER = """
            define untrusted actor IsolatedWorker as
              receive(ActorMail<String> mail): void {
                if mail.value == "stop"; do
                  self.end();
                fi
                return;
              }
            end
            """;

    @Test
    void validatesGlobalHeapAndActorCapacityWithoutCreatingAnEngine() {
        assertThrows(IllegalArgumentException.class, () ->
                SharedUntrustedActorSupervisor.validateConfiguration(64L << 20, 1));
        assertThrows(IllegalArgumentException.class, () ->
                SharedUntrustedActorSupervisor.validateConfiguration(2048L << 20, 1));
        assertThrows(IllegalArgumentException.class, () ->
                SharedUntrustedActorSupervisor.validateConfiguration(256L << 20, 0));
        assertThrows(IllegalArgumentException.class, () ->
                SharedUntrustedActorSupervisor.validateConfiguration(256L << 20, 257));
        assertDoesNotThrow(() ->
                SharedUntrustedActorSupervisor.validateConfiguration(256L << 20, 2));
    }

    @Test
    void closedSupervisorRejectsSpawnWithoutCreatingAnEngine() {
        var host = new SharedUntrustedActorSupervisor(256L << 20, 2);
        assertEquals(0, host.activeActors());
        host.close();
        host.close();
        assertThrows(IllegalStateException.class,
                () -> host.spawn(WORKER, "worker", "IsolatedWorker"));
    }

    @Test
    void malformedSourceAndIdentifiersRejectedBeforeIsolateConstruction() {
        try (var host = new SharedUntrustedActorSupervisor(256L << 20, 2)) {
            assertThrows(NullPointerException.class,
                    () -> host.spawn(null, "worker", "IsolatedWorker"));
            assertThrows(IllegalArgumentException.class,
                    () -> host.spawn("x".repeat(262145), "worker", "IsolatedWorker"));
            assertThrows(IllegalArgumentException.class,
                    () -> host.spawn(WORKER, "../unsafe", "IsolatedWorker"));
            assertThrows(IllegalArgumentException.class,
                    () -> host.spawn(WORKER, "worker", "Bad;Actor"));
            assertEquals(0, host.activeActors());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="ORES_TEST_SHARED_UNTRUSTED_ISOLATE", matches="true")
    void priorityForceCancelRevokesOneContextWithoutKillingSibling() {
        try (var pool = new SharedUntrustedIsolatePool(256L << 20, 3);
             var host = new SharedUntrustedActorSupervisor(pool, 2);
             var first = host.spawn(WORKER, "first", "IsolatedWorker");
             var sibling = host.spawn(WORKER, "sibling", "IsolatedWorker")) {
            assertTrue(first.sharesPhysicalCollectorWith(sibling));
            assertTrue(first.forceCancel(), "must retire hostile guest Context");
            assertTrue(first.closed());
            assertFalse(first.forceCancel(), "revocation is single-use");
            assertEquals(1, host.activeActors());
            assertEquals(1, host.reservedActorSlots(),
                    "a fully confirmed Context close releases only its own reservation");
            assertFalse(sibling.closed(), "shared Engine must survive this actor's revocation");
            assertThrows(IllegalStateException.class, () -> first.send("stop"));
            try (var replacement = host.spawn(WORKER, "replacement", "IsolatedWorker")) {
                assertTrue(sibling.sharesPhysicalCollectorWith(replacement),
                        "replacement MUST reuse same Engine, not spawn another isolate");
                assertEquals(2, host.activeActors());
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="ORES_TEST_SHARED_UNTRUSTED_ISOLATE", matches="true")
    void sourceActorsShareONEIsolateCollectorButKeepDifferentContexts() throws Exception {
        try (var host = new SharedUntrustedActorSupervisor(256L << 20, 2);
             var first = host.spawn(WORKER, "actor-one", "IsolatedWorker");
             var second = host.spawn(WORKER, "actor-two", "IsolatedWorker")) {
            assertEquals(2, host.activeActors());
            assertEquals(2, host.reservedActorSlots());
            assertNotEquals(first.identity(), second.identity());
            assertTrue(first.sandboxedInUntrustedIsolate());
            assertTrue(second.sandboxedInUntrustedIsolate());
            assertFalse(first.independentlyCollectable());
            assertFalse(second.independentlyCollectable());
            assertTrue(first.sharesPhysicalCollectorWith(second),
                    "all ordinary untrusted actors MUST share one Engine/GC");
            assertFalse(first.sharesGuestContextWith(second),
                    "per-actor Context is not per-actor isolate");
            assertThrows(SecurityException.class, () -> first.send(new Object()));

            first.send("stop");
            second.send("stop");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while ((!first.done() || !second.done())
                    && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(first.done() && second.done());

            first.close();
            assertTrue(first.closed());
            assertEquals(1, host.activeActors());
            assertFalse(second.closed(), "retiring one Context must not close the Engine");
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="ORES_TEST_SHARED_UNTRUSTED_ISOLATE", matches="true")
    void hotReloadAndActorSupervisorUseTheSameOnlyUntrustedEngine() {
        try (var pool = new SharedUntrustedIsolatePool(256L << 20, 4);
             var loader = HotReloadManager.forUntrustedActors(
                     IsolatePolicy.developer(), ExecutionProfile.serverJit(), pool);
             var supervisor = new SharedUntrustedActorSupervisor(pool, 2)) {
            var generation = loader.load("code.ores",
                    "pub routine main(): void { return; }");
            generation.start();
            try (var actor = supervisor.spawn(WORKER, "worker", "IsolatedWorker")) {
                assertTrue(actor.sharesPhysicalCollectorWith(generation.context()),
                        "hot reload and source actors must share one Engine, not two");
            }
            assertEquals(0, supervisor.activeActors());
            // Loader is not permitted to close the caller-owned shared Engine.
            var engine = generation.context().getEngine();
            loader.close();
            try (var group = pool.createGroup()) {
                assertSame(engine, group.contextForSupervisor().getEngine(),
                        "externally owned pool must stay live after loader close");
            }
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named="ORES_TEST_SHARED_UNTRUSTED_ISOLATE", matches="true")
    void externallyOwnedPoolSupportsTwoSupervisorsWithoutExtraIsolates() {
        try (var pool = new SharedUntrustedIsolatePool(256L << 20, 2);
             var one = new SharedUntrustedActorSupervisor(pool, 1);
             var two = new SharedUntrustedActorSupervisor(pool, 1);
             var a = one.spawn(WORKER, "actor-a", "IsolatedWorker");
             var b = two.spawn(WORKER, "actor-b", "IsolatedWorker")) {
            assertTrue(a.sharesPhysicalCollectorWith(b),
                    "supervisors sharing a pool must NOT allocate a second Engine");
        }
    }
}
