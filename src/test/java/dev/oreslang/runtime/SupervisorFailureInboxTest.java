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
    void concurrentDuplicateTerminalOffersPublishOnlyOneOutstandingReceipt() throws Exception {
        UUID supervisor = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        var inbox = new SupervisorFailureInbox(supervisor, 1);
        var trace = snapshot(actor, supervisor, 15);
        int publishers = 24;
        var allReady = new CountDownLatch(publishers);
        var release = new CountDownLatch(1);
        var completed = new CountDownLatch(publishers);
        var failure = new AtomicReference<Throwable>();
        for (int i = 0; i < publishers; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    allReady.countDown();
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("publisher start deadline");
                    }
                    if (!inbox.offerTrusted(trace)) {
                        throw new AssertionError("duplicate admission unexpectedly denied");
                    }
                } catch (Throwable unexpected) {
                    failure.compareAndSet(null, unexpected);
                } finally {
                    completed.countDown();
                }
            });
        }
        assertTrue(allReady.await(5, TimeUnit.SECONDS));
        release.countDown();
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertEquals(1, inbox.outstandingCount());
        assertEquals(1, inbox.queuedCount());
        var claim = inbox.pollHost().orElseThrow();
        assertEquals(actor.toString(), claim.actorIdentity());
        assertTrue(inbox.pollHost().isEmpty());
        assertTrue(inbox.acknowledgeHost(
                claim.sequence(), claim.actorIdentity(),
                claim.actorGeneration(), claim.claimAttempt()));
    }

    @Test
    void replayOfMultipleClaimedReceiptsPreservesQueueAndClaimFences() {
        UUID supervisor = UUID.randomUUID();
        var inbox = new SupervisorFailureInbox(supervisor, 4);
        UUID[] actors = {UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID()};
        for (UUID actor : actors) {
            assertTrue(inbox.offerTrusted(snapshot(actor, supervisor, 11)));
        }
        var oldFirst = inbox.pollHost().orElseThrow();
        var oldSecond = inbox.pollHost().orElseThrow();
        assertEquals(2, inbox.queuedCount());
        assertEquals(4, inbox.outstandingCount());
        assertEquals(2, inbox.requeueUnacknowledgedHost());
        assertEquals(4, inbox.queuedCount(),
                "claimed receipts must return to the queue without missing slots");
        assertEquals(0, inbox.requeueUnacknowledgedHost(),
                "a second retry must not duplicate already queued receipts");

        var receipts = new java.util.ArrayList<SupervisorFailureInbox.Receipt>();
        for (int i = 0; i < actors.length; i++) {
            var claim = inbox.pollHost().orElseThrow();
            receipts.add(claim);
            assertEquals(actors[i].toString(), claim.actorIdentity(),
                    "earlier failed claims must replay before unclaimed work");
        }
        assertEquals(oldFirst.sequence(), receipts.get(0).sequence());
        assertEquals(oldSecond.sequence(), receipts.get(1).sequence());
        assertEquals(oldFirst.claimAttempt() + 1, receipts.get(0).claimAttempt());
        assertEquals(oldSecond.claimAttempt() + 1, receipts.get(1).claimAttempt());
        assertEquals(1, receipts.get(2).claimAttempt());
        assertEquals(1, receipts.get(3).claimAttempt());
        assertFalse(inbox.acknowledgeHost(oldFirst.sequence(),
                oldFirst.actorIdentity(), 11, oldFirst.claimAttempt()));
        assertFalse(inbox.acknowledgeHost(oldSecond.sequence(),
                oldSecond.actorIdentity(), 11, oldSecond.claimAttempt()));
        for (var claim : receipts) {
            assertTrue(inbox.acknowledgeHost(claim.sequence(),
                    claim.actorIdentity(), claim.actorGeneration(),
                    claim.claimAttempt()));
        }
        assertEquals(0, inbox.outstandingCount());
        assertEquals(0, inbox.queuedCount());
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
    void saturatedInboxRetainsActorUntilExplicitExactSupervisorRetry() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var owner = runtime.createSupervisor();
            var other = runtime.createSupervisor();
            owner.enableHostFailureInbox(1);
            other.enableHostFailureInbox(1);
            var group = runtime.createActorGroup(owner);
            var first = owner.<String>startChildHost(
                    group, ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    ignored -> (message, context) -> {
                        throw new IllegalStateException("first guest secret");
                    });
            first.getReady().get(5, TimeUnit.SECONDS);
            first.send("fail");
            assertTrue(first.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, owner.outstandingHostFailureReceipts());

            var blocked = owner.<String>startChildHost(
                    group, ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    ignored -> (message, context) -> {
                        throw new IllegalStateException("second guest secret");
                    });
            blocked.getReady().get(5, TimeUnit.SECONDS);
            blocked.send("fail");
            assertFalse(blocked.awaitTermination(200, TimeUnit.MILLISECONDS),
                    "a full inbox must not silently finalize an undelivered failure");
            assertFalse(owner.retryHostFailureAdmission(blocked),
                    "retry cannot bypass a full inbox");
            assertFalse(other.retryHostFailureAdmission(blocked),
                    "unrelated supervisor must not recover a blocked actor");
            assertFalse(owner.retryHostFailureAdmission(first),
                    "completed actor cannot mint another receipt");

            var admittedFirst = owner.pollHostFailureReceipt().orElseThrow();
            assertTrue(owner.acknowledgeHostFailureReceipt(
                    admittedFirst.sequence(), admittedFirst.actorIdentity(),
                    admittedFirst.actorGeneration(), admittedFirst.claimAttempt()));
            assertEquals(0, owner.outstandingHostFailureReceipts());
            assertTrue(owner.retryHostFailureAdmission(blocked),
                    "capacity release permits retry of the original child failure");
            assertTrue(blocked.awaitTermination(5, TimeUnit.SECONDS),
                    "successful host admission must release only the inbox fence");
            var admittedSecond = owner.pollHostFailureReceipt().orElseThrow();
            assertEquals(blocked.id().value().toString(),
                    admittedSecond.actorIdentity());
            assertEquals(blocked.generation(),
                    admittedSecond.actorGeneration());
            assertNotEquals(admittedFirst.sequence(), admittedSecond.sequence());
            assertFalse(admittedSecond.toString().contains("second guest secret"));
            assertFalse(owner.retryHostFailureAdmission(blocked),
                    "a finalized child cannot be replayed as a new terminal failure");
            assertTrue(owner.acknowledgeHostFailureReceipt(
                    admittedSecond.sequence(), admittedSecond.actorIdentity(),
                    admittedSecond.actorGeneration(), admittedSecond.claimAttempt()));
            group.close();
        }
    }

    @Test
    void releasedWorkerFailureIsAttributedOnlyToNewSupervisor() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var original = runtime.createSupervisor();
            var replacement = runtime.createSupervisor();
            original.enableHostFailureInbox(4);
            replacement.enableHostFailureInbox(4);
            var firstGroup = runtime.createActorGroup(original);
            var secondGroup = runtime.createActorGroup(replacement);
            var worker = runtime.<String>spawnShared(
                    ignored -> (message, context) -> {
                        throw new IllegalArgumentException("transferred private secret");
                    });
            worker.getReady().get(5, TimeUnit.SECONDS);
            firstGroup.manageWorker(worker);
            assertTrue(firstGroup.releaseWorker(worker));
            secondGroup.manageWorker(worker);
            // Closing the prior group must not steal the new supervisor route.
            firstGroup.close();
            worker.send("fail");
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(0, original.outstandingHostFailureReceipts());
            assertEquals(1, replacement.outstandingHostFailureReceipts());
            var receipt = replacement.pollHostFailureReceipt().orElseThrow();
            assertEquals(worker.id().value().toString(), receipt.actorIdentity());
            assertEquals(replacement.id().value().toString(), receipt.supervisorIdentity());
            assertEquals(worker.generation(), receipt.actorGeneration());
            assertFalse(receipt.toString().contains("transferred private secret"));
            assertTrue(replacement.acknowledgeHostFailureReceipt(
                    receipt.sequence(), receipt.actorIdentity(),
                    receipt.actorGeneration(), receipt.claimAttempt()));
            secondGroup.close();
        }
    }

    @Test
    void managedWorkerCloseStillRetainsSupervisorFailureReceipt() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            var supervisor = runtime.createSupervisor();
            supervisor.enableHostFailureInbox(2);
            var group = runtime.createActorGroup(supervisor);
            var worker = supervisor.<String>startChildHost(
                    group, ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(),
                    ignored -> (message, context) -> { });
            worker.getReady().get(5, TimeUnit.SECONDS);
            group.close();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, supervisor.outstandingHostFailureReceipts(),
                    "closing the management group must not discard its terminal receipt");
            var receipt = supervisor.pollHostFailureReceipt().orElseThrow();
            assertEquals(worker.id().value().toString(), receipt.actorIdentity());
            assertEquals(supervisor.id().value().toString(), receipt.supervisorIdentity());
            assertEquals(ActorFailureTrace.FailureKind.CANCELLED,
                    receipt.failureKind());
            assertTrue(supervisor.acknowledgeHostFailureReceipt(
                    receipt.sequence(), receipt.actorIdentity(),
                    receipt.actorGeneration(), receipt.claimAttempt()));
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
