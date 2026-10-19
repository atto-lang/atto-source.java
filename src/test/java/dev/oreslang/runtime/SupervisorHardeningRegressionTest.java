package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class SupervisorHardeningRegressionTest {
    @Test
    void successfulParentEscalationFencesFurtherRegistration() throws Exception {
        try (var runtime = new ActorRuntime();
             var inbox = new TrustedSupervisorInbox(1, 8, 4096, 1024);
             var table = new SupervisorDenseTable(SupervisorDenseTable.Layout.AOSOA, 8, 8);
             var bridge = new SupervisorRuntimeBridge(runtime, inbox, table, notice -> true)) {
            var first = runtime.<String>spawnShared(() -> (message, context) -> {
                throw new IllegalStateException("first");
            });
            var second = runtime.<String>spawnShared(() -> (message, context) -> {
                throw new IllegalStateException("second");
            });
            var third = runtime.<String>spawnShared(() -> (message, context) -> {});

            bridge.register(first);
            bridge.register(second);

            first.send("x");
            assertThrows(Exception.class, () -> first.getDone().get(3, TimeUnit.SECONDS));
            awaitTableSize(table, 1);

            second.send("x");
            assertThrows(Exception.class, () -> second.getDone().get(3, TimeUnit.SECONDS));
            awaitBridgeUnhealthy(bridge);
            awaitTableSize(table, 0);

            assertTrue(inbox.needsParentEscalation());
            assertFalse(bridge.healthy());
            assertThrows(IllegalStateException.class, () -> bridge.register(third));
            third.stop();
        }
    }

    @Test
    void actorIdentityColumnCannotBeMutatedAfterInsertion() {
        try (var table = new SupervisorDenseTable(
                SupervisorDenseTable.Layout.SOA, 1, 8)) {
            var handle = table.add(41, 1, 0, 0, 0);
            assertThrows(IllegalArgumentException.class, () ->
                    table.set(handle, SupervisorDenseTable.Field.ACTOR_ID, 42));
            assertEquals(41, table.get(handle, SupervisorDenseTable.Field.ACTOR_ID));
        }
    }
    private static void awaitTableSize(
            SupervisorDenseTable table, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        for (;;) {
            int actual = table.size();
            if (actual == expected) return;
            if (System.nanoTime() >= deadline) {
                fail("timed out waiting for supervisor metadata size "
                        + expected + "; actual=" + actual);
            }
            Thread.sleep(1);
        }
    }

    private static void awaitBridgeUnhealthy(
            SupervisorRuntimeBridge bridge) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (bridge.healthy()) {
            if (System.nanoTime() >= deadline) {
                fail("timed out waiting for supervisor bridge escalation state");
            }
            Thread.sleep(1);
        }
    }

}
