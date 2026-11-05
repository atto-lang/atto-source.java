package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host admission/claim/ack tests. These do not claim actual supervisor guest
 * CONTROL execution or source-compiled .atto throw/raise/panic parity.
 */
@Timeout(20)
final class SupervisorFailureInboxTest {
    private static ActorFailureTrace.Snapshot snapshot(
            UUID actor, UUID supervisor, long generation) {
        return ActorFailureTrace.capture(
                ActorFailureTrace.FailureKind.UNCLASSIFIED,
                actor, generation, List.of(), List.of(supervisor),
                false, true);
    }

    @Test
    void boundedInboxRejectsOverflowAndReplaysUnackedSequenceWithoutDuplicateAdmission() {
        UUID supervisor = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        var inbox = new SupervisorFailureInbox(supervisor, 1);
        var first = snapshot(actor, supervisor, 5);
        assertTrue(inbox.offerTrusted(first));
        assertTrue(inbox.offerTrusted(first), "duplicate incarnation is idempotent");
        assertFalse(inbox.offerTrusted(snapshot(UUID.randomUUID(), supervisor, 1)),
                "full inbox must fail admission instead of dropping an older failure");
        assertEquals(1, inbox.outstandingCount());
        assertEquals(1, inbox.queuedCount());

        var receipt = inbox.pollHost().orElseThrow();
        assertEquals(actor.toString(), receipt.actorIdentity());
        assertEquals(5L, receipt.actorGeneration());
        assertEquals(supervisor.toString(), receipt.supervisorIdentity());
        assertEquals(1L, receipt.claimAttempt());
        assertFalse(receipt.supervisorControlDelivered());
        assertEquals(0, inbox.queuedCount());
        assertTrue(inbox.pollHost().isEmpty(), "claim is single-winner");
        assertFalse(inbox.acknowledgeHost(receipt.sequence(), actor.toString(), 6,
                receipt.claimAttempt()), "wrong actor generation cannot acknowledge receipt");
        assertFalse(inbox.acknowledgeHost(receipt.sequence(), UUID.randomUUID().toString(), 5,
                receipt.claimAttempt()));
        assertFalse(inbox.offerTrusted(snapshot(UUID.randomUUID(), supervisor, 1)),
                "claimed but unacknowledged receipt still consumes capacity");

        assertEquals(1, inbox.requeueUnacknowledgedHost());
        assertEquals(0, inbox.requeueUnacknowledgedHost(),
                "requeue itself must not duplicate delivery candidates");
        var retry = inbox.pollHost().orElseThrow();
        assertEquals(receipt.sequence(), retry.sequence());
        assertEquals(receipt.claimAttempt() + 1, retry.claimAttempt());
        assertFalse(inbox.acknowledgeHost(retry.sequence(), actor.toString(), 5,
                receipt.claimAttempt()), "old claim attempt cannot acknowledge retry");
        assertFalse(inbox.acknowledgeHost(retry.sequence(), actor.toString(), 6,
                retry.claimAttempt()));
        assertTrue(inbox.acknowledgeHost(retry.sequence(), actor.toString(), 5,
                retry.claimAttempt()));
        assertFalse(inbox.acknowledgeHost(retry.sequence(), actor.toString(), 5,
                retry.claimAttempt()), "duplicate ack must never count twice");
        assertEquals(0, inbox.outstandingCount());
        assertTrue(inbox.offerTrusted(snapshot(UUID.randomUUID(), supervisor, 1)));
    }

    @Test
    void onlyDirectSupervisorOwnsTheReceiptAndOnlyCorrectIncarnationIsAccepted() {
        UUID supervisor = UUID.randomUUID();
        UUID ancestor = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        var inbox = new SupervisorFailureInbox(supervisor, 2);
        var otherInbox = new SupervisorFailureInbox(ancestor, 2);
        var trace = ActorFailureTrace.capture(
                ActorFailureTrace.FailureKind.UNCLASSIFIED,
                actor, 3, List.of(), List.of(supervisor, ancestor), false, true);
        assertTrue(inbox.offerTrusted(trace));
        assertFalse(otherInbox.offerTrusted(trace),
                "an ancestor association does not grant direct delivery authority");
        assertEquals(0, otherInbox.outstandingCount());

        var claimed = inbox.pollHost().orElseThrow();
        assertFalse(inbox.acknowledgeHost(claimed.sequence(), actor.toString(), 4,
                claimed.claimAttempt()));
        assertTrue(inbox.acknowledgeHost(claimed.sequence(), actor.toString(), 3,
                claimed.claimAttempt()));
        assertEquals(0, inbox.outstandingCount());
        assertFalse(new SupervisorFailureInbox(supervisor, 1).offerTrusted(
                ActorFailureTrace.capture(
                        ActorFailureTrace.FailureKind.UNCLASSIFIED, actor, 3,
                        List.of(), List.of(), false, false)));
    }

    @Test
    void integrationPersistsHostReceiptAfterActorFinalizationWithoutGuestException() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var supervisor = runtime.createSupervisor();
            supervisor.enableHostFailureInbox(4);
            var group = runtime.createActorGroup(supervisor);
            var failed = supervisor.<String>startChildHost(
                    group, ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    ignored -> (message, context) -> {
                        throw new IllegalArgumentException("child private arena secret");
                    });
            failed.getReady().get(5, TimeUnit.SECONDS);
            failed.send("fail");
            assertTrue(failed.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, supervisor.outstandingHostFailureReceipts());
            var receipt = supervisor.pollHostFailureReceipt().orElseThrow();
            assertEquals(failed.id().value().toString(), receipt.actorIdentity());
            assertEquals(failed.generation(), receipt.actorGeneration());
            assertEquals(supervisor.id().value().toString(), receipt.supervisorIdentity());
            assertTrue(receipt.trace().supervisorAssociationKnown());
            assertFalse(receipt.trace().sourceFramesAvailable());
            assertFalse(receipt.supervisorControlDelivered());
            assertFalse(receipt.toString().contains("child private arena secret"));
            assertTrue(supervisor.pollHostFailureReceipt().isEmpty());
            assertFalse(supervisor.acknowledgeHostFailureReceipt(
                    receipt.sequence(), receipt.actorIdentity(),
                    receipt.actorGeneration() + 1, receipt.claimAttempt()));
            assertEquals(1, supervisor.requeueUnacknowledgedHostFailureReceipts());
            var retry = supervisor.pollHostFailureReceipt().orElseThrow();
            assertEquals(receipt.sequence(), retry.sequence());
            assertEquals(receipt.claimAttempt() + 1, retry.claimAttempt());
            assertFalse(supervisor.acknowledgeHostFailureReceipt(
                    receipt.sequence(), receipt.actorIdentity(),
                    receipt.actorGeneration(), receipt.claimAttempt()),
                    "stale claim must never acknowledge the retry");
            assertTrue(supervisor.acknowledgeHostFailureReceipt(
                    retry.sequence(), retry.actorIdentity(),
                    retry.actorGeneration(), retry.claimAttempt()));
            assertEquals(0, supervisor.outstandingHostFailureReceipts());
            group.close();
        }
    }

    @Test
    void actorExecutionCannotClaimOrAcknowledgeSupervisorHostReceipts() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var supervisor = runtime.createSupervisor();
            supervisor.enableHostFailureInbox(2);
            var denied = new AtomicReference<Throwable>();
            var done = new CountDownLatch(1);
            var actor = runtime.<String>spawnPrivateTrusted(
                    ignored -> (message, context) -> {
                        try {
                            supervisor.pollHostFailureReceipt();
                            denied.set(new AssertionError("actor stole supervisor host receipt"));
                        } catch (SecurityException expected) {
                            // Host/CONTROL authority is not acquired by actor execution.
                        } catch (Throwable unexpected) {
                            denied.set(unexpected);
                        }
                        try {
                            supervisor.acknowledgeHostFailureReceipt(1, UUID.randomUUID().toString(), 1, 1);
                            denied.set(new AssertionError("actor forged supervisor ack"));
                        } catch (SecurityException expected) {
                            // Must be rejected before any receipt state is touched.
                        } catch (Throwable unexpected) {
                            denied.set(unexpected);
                        } finally {
                            done.countDown();
                        }
                    });
            actor.getReady().get(5, TimeUnit.SECONDS);
            actor.send("inspect");
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertNull(denied.get());
            actor.stop();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsInvalidQuotaBeforeAllocatingOrPublishingInbox() {
        var supervisor = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> new SupervisorFailureInbox(supervisor, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new SupervisorFailureInbox(supervisor,
                        SupervisorFailureInbox.MAX_CAPACITY + 1));
    }
}
