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
             var bridge = new SupervisorRuntimeBridge(inbox, notice -> {
                 fail("unexpected parent escalation");
                 return false;
             })) {
            var child = runtime.<String>spawnShared(() -> (message, context) -> {
                throw new IllegalStateException("boom");
            });
            bridge.register(child);

            child.send("boom");
            assertThrows(Exception.class, () -> child.getDone().get(3, TimeUnit.SECONDS));

            var notice = inbox.pollControl();
            assertNotNull(notice);
            assertEquals(child.generation(), notice.incarnation());
            assertTrue(notice.childId() > 0);
            assertTrue(notice.sequence() > 0);
            assertNull(inbox.pollControl());
        }
    }

    @Test
    void gracefulFinalizationDoesNotForgeFailureControl() throws Exception {
        try (var runtime = new ActorRuntime();
             var inbox = new TrustedSupervisorInbox(4, 8, 4096, 1024);
             var bridge = new SupervisorRuntimeBridge(inbox, notice -> true)) {
            var child = runtime.<String>spawnShared(() -> (message, context) -> context.self().stop());
            bridge.register(child);

            child.send("stop");
            child.getDone().get(3, TimeUnit.SECONDS);

            assertNull(inbox.pollControl());
            assertEquals(0, inbox.outstandingMessages());
        }
    }

    @Test
    void fullControlLaneEscalatesSecondFinalizedFailureExactlyOnce() throws Exception {
        AtomicInteger escalations = new AtomicInteger();
        try (var runtime = new ActorRuntime();
             var inbox = new TrustedSupervisorInbox(1, 8, 4096, 1024);
             var bridge = new SupervisorRuntimeBridge(inbox, notice -> {
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

            // Leave the first terminal notice queued so the second authenticated
            // failure must take the mandatory parent escalation path.
            first.send("x");
            assertThrows(Exception.class, () -> first.getDone().get(3, TimeUnit.SECONDS));
            assertNotNull(inbox.pollControl());

            // Refill and deliberately keep the CONTROL lane occupied.
            var occupied = runtime.<String>spawnShared(() -> (message, context) -> {
                throw new RuntimeException("occupied");
            });
            bridge.register(occupied);
            occupied.send("x");
            assertThrows(Exception.class, () -> occupied.getDone().get(3, TimeUnit.SECONDS));

            second.send("x");
            assertThrows(Exception.class, () -> second.getDone().get(3, TimeUnit.SECONDS));

            assertEquals(1, escalations.get());
            assertTrue(inbox.needsParentEscalation());
        }
    }
}
