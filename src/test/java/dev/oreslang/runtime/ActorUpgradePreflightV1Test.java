package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host preflight only. No test here asserts an atomic ActorRef/image cutover:
 * publishing two independent epochs is deliberately NOT exposed by this API.
 */
final class ActorUpgradePreflightV1Test {
    private static String sha(char n) { return String.valueOf(n).repeat(64); }

    private static ActorUpgradeEpochGate.Image image(char c) {
        return new ActorUpgradeEpochGate.Image(
                sha(c), sha('1'), sha('2'), sha('3'));
    }

    @Test
    void pendingLogicalMessageAndSuspendsTaskBothBlockUpgradeReadiness() {
        try (AsyncRuntime runtime = new AsyncRuntime();
             OresScheduler scheduler = new OresScheduler(1);
             OresTaskScope owner = new OresTaskScope(runtime);
             ActorUpgradeEpochGate gate = new ActorUpgradeEpochGate(42, image('a'));
             LogicalActorProxyMailboxV1 proxy =
                     new LogicalActorProxyMailboxV1(4, 16, 64)) {
            var first = proxy.admit(new byte[]{1});
            var dispatched = proxy.pollDispatch();
            assertEquals(first.messageSequence(), dispatched.messageSequence());
            var unresolved = new OresFuture<Integer>();
            AtomicInteger resumes = new AtomicInteger();
            OresTask<Integer> waiting = ActorTaskGenerationBridge.start(
                    gate, owner, scheduler, resume -> {
                        if (resumes.getAndIncrement() == 0)
                            return new OresScheduler.Await<Integer>(unresolved);
                        return new OresScheduler.Done<>(17);
                    });

            try (var preflight = ActorUpgradePreflightV1.prepare(
                    gate, proxy, image('b'))) {
                assertFalse(preflight.quiescentSnapshot());
                assertTrue(gate.frozen());
                assertTrue(proxy.snapshot().dispatchFrozen());
                assertThrows(IllegalStateException.class, () ->
                        ActorTaskGenerationBridge.start(gate, owner, scheduler,
                                ignored -> new OresScheduler.Done<>(8)));

                // DATA admission can remain open within hard credits, but
                // no accepted bytes reach the frozen physical old actor.
                var second = proxy.admit(new byte[]{2});
                assertEquals(first.messageSequence() + 1,
                        second.messageSequence());
                assertNull(proxy.pollDispatch());
                proxy.complete(first.messageSequence());
                assertFalse(preflight.quiescentSnapshot(),
                        "suspended Task code pin must still block publication");
                unresolved.completeFromRuntime(0);
                assertEquals(17, AsyncRuntime.await(waiting.getAwaited()));
                assertNull(AsyncRuntime.await(waiting.getDone()));
                assertEquals(0, gate.livePins());
                assertTrue(preflight.quiescentSnapshot());
                // Publication is intentionally separate and disabled until
                // one authoritative route+generation CAS exists.
                assertEquals(1, gate.epoch());
                assertEquals(1, proxy.snapshot().routeEpoch());
            }

            assertFalse(gate.frozen());
            assertFalse(proxy.snapshot().dispatchFrozen());
            assertEquals(image('a'), gate.image());
            assertEquals(1, proxy.snapshot().routeEpoch());
            var secondDispatched = proxy.pollDispatch();
            assertNotNull(secondDispatched);
            assertEquals(2, secondDispatched.messageSequence());
            proxy.complete(secondDispatched.messageSequence());
            assertEquals(0, proxy.snapshot().retainedMessages());
        }
    }

    @Test
    void mailboxFreezeFailureMustRollBackActorGenerationFreeze() {
        try (ActorUpgradeEpochGate gate = new ActorUpgradeEpochGate(10, image('a'));
             LogicalActorProxyMailboxV1 proxy =
                     new LogicalActorProxyMailboxV1(2, 8, 16)) {
            var existing = proxy.freezeDispatchForUpgrade();
            assertThrows(IllegalStateException.class, () ->
                    ActorUpgradePreflightV1.prepare(gate, proxy, image('b')));
            assertFalse(gate.frozen(),
                    "failed joint preparation must never strand Task admission");
            assertEquals(0, gate.livePins());
            var lease = gate.pin(ActorUpgradeEpochGate.LeaseKind.ACTOR_TURN);
            lease.close();
            assertTrue(proxy.snapshot().dispatchFrozen(),
                    "foreign freeze must remain owned by its actual ticket");
            existing.rollback();
            assertFalse(proxy.snapshot().dispatchFrozen());
        }
    }

    @Test
    void failedGenerationCompatibilityNeverFreezesTheLogicalMailbox() {
        try (ActorUpgradeEpochGate gate = new ActorUpgradeEpochGate(11, image('a'));
             LogicalActorProxyMailboxV1 proxy =
                     new LogicalActorProxyMailboxV1(2, 8, 16)) {
            var incompatible = new ActorUpgradeEpochGate.Image(
                    sha('b'), sha('4'), sha('2'), sha('3'));
            assertThrows(SecurityException.class, () ->
                    ActorUpgradePreflightV1.prepare(gate, proxy, incompatible));
            assertFalse(proxy.snapshot().dispatchFrozen());
            assertFalse(gate.frozen());
            assertEquals(1, gate.epoch());
        }
    }

    @Test
    void abortIsIdempotentAndOldRouteKeepsItsSequenceAndActorIdentity() {
        try (ActorUpgradeEpochGate gate = new ActorUpgradeEpochGate(12, image('a'));
             LogicalActorProxyMailboxV1 proxy =
                     new LogicalActorProxyMailboxV1(2, 8, 16)) {
            var preflight = ActorUpgradePreflightV1.prepare(
                    gate, proxy, image('b'));
            assertTrue(preflight.quiescentSnapshot());
            preflight.close();
            preflight.close();
            assertThrows(IllegalStateException.class, preflight::quiescentSnapshot);
            assertEquals(12, gate.actorIdentity());
            assertEquals(1, gate.epoch());
            assertEquals(image('a'), gate.image());
            assertEquals(1, proxy.snapshot().routeEpoch());
            var message = proxy.admit(new byte[]{7});
            assertEquals(1, proxy.pollDispatch().messageSequence());
            proxy.complete(message.messageSequence());
        }
    }
}
