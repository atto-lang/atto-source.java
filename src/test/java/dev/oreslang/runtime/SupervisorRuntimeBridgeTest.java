package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
final class SupervisorRuntimeBridgeTest {
    @Test
    void realFinalizedActorFailureBecomesTrustedControlNotice() throws Exception {
        try (var runtime = new ActorRuntime();
             var inbox = new TrustedSupervisorInbox(4, 8, 4096, 1024);
             var table = new SupervisorDenseTable(SupervisorDenseTable.Layout.AOSOA, 8, 8);
             var bridge = new SupervisorRuntimeBridge(runtime, inbox, table, notice -> {
                 fail("unexpected parent escalation");
                 return false;
             })) {
            var child = runtime.<String>spawnShared(() -> (message, context) -> {
                throw new IllegalStateException("boom");
            });
            bridge.register(child);

            child.send("boom");
            assertThrows(Exception.class, () -> child.getDone().get(3, TimeUnit.SECONDS));

            var notice = awaitControl(inbox);
            assertEquals(child.generation(), notice.incarnation());
            assertTrue(notice.childId() > 0);
            assertTrue(notice.sequence() > 0);
            assertNull(inbox.pollControl());
            awaitTableSize(table, 0);
        }
    }

    @Test
    void gracefulFinalizationDoesNotForgeFailureControl() throws Exception {
        try (var runtime = new ActorRuntime();
             var inbox = new TrustedSupervisorInbox(4, 8, 4096, 1024);
             var table = new SupervisorDenseTable(SupervisorDenseTable.Layout.AOSOA, 8, 8);
             var bridge = new SupervisorRuntimeBridge(runtime, inbox, table, notice -> true)) {
            var child = runtime.<String>spawnShared(() -> (message, context) -> context.self().stop());
            bridge.register(child);

            child.send("stop");
            child.getDone().get(3, TimeUnit.SECONDS);
            awaitTableSize(table, 0);

            assertNull(inbox.pollControl());
            assertEquals(0, inbox.outstandingMessages());
        }
    }

    @Test
    void fullControlLaneEscalatesSecondFinalizedFailureExactlyOnce() throws Exception {
        AtomicInteger escalations = new AtomicInteger();
        try (var runtime = new ActorRuntime();
             var inbox = new TrustedSupervisorInbox(1, 8, 4096, 1024);
             var table = new SupervisorDenseTable(SupervisorDenseTable.Layout.AOSOA, 8, 8);
             var bridge = new SupervisorRuntimeBridge(runtime, inbox, table, notice -> {
                 escalations.incrementAndGet();
                 return true;
             })) {
            var first = runtime.<String>spawnShared(() -> (message, context) -> {
                throw new IllegalArgumentException("first");
            });
            var second = runtime.<String>spawnShared(() -> (message, context) -> {
                throw new IllegalStateException("second");
            });
            bridge.register(first);
            bridge.register(second);

            first.send("x");
            assertThrows(Exception.class, () -> first.getDone().get(3, TimeUnit.SECONDS));
            awaitTableSize(table, 1);

            second.send("x");
            assertThrows(Exception.class, () -> second.getDone().get(3, TimeUnit.SECONDS));
            awaitAtomicValue(escalations, 1);
            awaitTableSize(table, 0);

            assertEquals(1, escalations.get());
            assertTrue(inbox.needsParentEscalation());
            assertFalse(bridge.healthy());
        }
    }

    @Test
    void foreignRuntimeActorRefIsRejectedBeforeSupervisorAuthorityIsMinted() {
        try (var runtime = new ActorRuntime();
             var foreign = new ActorRuntime();
             var inbox = new TrustedSupervisorInbox(4, 8, 4096, 1024);
             var table = new SupervisorDenseTable(SupervisorDenseTable.Layout.AOSOA, 8, 8);
             var bridge = new SupervisorRuntimeBridge(runtime, inbox, table, notice -> true)) {
            var child = foreign.<String>spawnShared(() -> (message, context) -> {});
            assertThrows(IllegalArgumentException.class, () -> bridge.register(child));
            child.stop();
        }
    }

    @Test
    void failedMandatoryEscalationPoisonsBridgeAndBlocksNewAuthority() throws Exception {
        try (var runtime = new ActorRuntime();
             var inbox = new TrustedSupervisorInbox(1, 8, 4096, 1024);
             var table = new SupervisorDenseTable(SupervisorDenseTable.Layout.AOSOA, 8, 8);
             var bridge = new SupervisorRuntimeBridge(runtime, inbox, table, notice -> false)) {
            var first = runtime.<String>spawnShared(() -> (message, context) -> {
                throw new IllegalStateException("first");
            });
            var overflow = runtime.<String>spawnShared(() -> (message, context) -> {
                throw new IllegalArgumentException("overflow");
            });
            bridge.register(first);
            bridge.register(overflow);

            first.send("x");
            assertThrows(Exception.class, () -> first.getDone().get(3, TimeUnit.SECONDS));
            awaitTableSize(table, 1);

            overflow.send("x");
            assertThrows(Exception.class, () -> overflow.getDone().get(3, TimeUnit.SECONDS));
            awaitBridgeUnhealthy(bridge);

            assertFalse(bridge.healthy());
            assertThrows(IllegalStateException.class, bridge::requireHealthy);
            assertEquals(1, table.size(),
                    "failed mandatory escalation retains one FAILED diagnostic row");

            var later = runtime.<String>spawnShared(() -> (message, context) -> {});
            assertThrows(IllegalStateException.class, () -> bridge.register(later));
            later.stop();
        }
    }

    @Test
    void liveChildMetadataWorksAcrossAllThreePhysicalLayouts() throws Exception {
        for (SupervisorDenseTable.Layout layout : SupervisorDenseTable.Layout.values()) {
            try (var runtime = new ActorRuntime();
                 var inbox = new TrustedSupervisorInbox(4, 8, 4096, 1024);
                 var table = new SupervisorDenseTable(layout, 2, 8);
                 var bridge = new SupervisorRuntimeBridge(runtime, inbox, table, notice -> true)) {
                var child = runtime.<String>spawnShared(
                        () -> (message, context) -> context.self().stop());

                bridge.register(child);
                assertEquals(1, table.size());
                assertEquals(layout, table.layout());

                child.send("stop");
                child.getDone().get(3, TimeUnit.SECONDS);
                awaitTableSize(table, 0);

                assertEquals(0, table.size());
                assertNull(inbox.pollControl());
            }
        }
    }


    private static TrustedSupervisorInbox.FailureNotice awaitControl(
            TrustedSupervisorInbox inbox) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        for (;;) {
            var notice = inbox.pollControl();
            if (notice != null) return notice;
            if (System.nanoTime() >= deadline) {
                fail("timed out waiting for supervisor CONTROL notice");
            }
            Thread.sleep(1);
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

    private static void awaitAtomicValue(
            AtomicInteger value, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (value.get() != expected) {
            if (System.nanoTime() >= deadline) {
                fail("timed out waiting for value " + expected
                        + "; actual=" + value.get());
            }
            Thread.sleep(1);
        }
    }

    private static void awaitBridgeUnhealthy(
            SupervisorRuntimeBridge bridge) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (bridge.healthy()) {
            if (System.nanoTime() >= deadline) {
                fail("timed out waiting for supervisor bridge terminal state");
            }
            Thread.sleep(1);
        }
    }

}
