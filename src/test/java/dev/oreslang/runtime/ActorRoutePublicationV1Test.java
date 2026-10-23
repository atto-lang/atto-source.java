package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host coordinator proof only: guest state validation, physical ready
 * attestations, authenticated mail.done and isolation are separate gates.
 */
final class ActorRoutePublicationV1Test {
    private static String sha(char c) { return String.valueOf(c).repeat(64); }

    private static ActorUpgradeEpochGate.Image image(char code) {
        return new ActorUpgradeEpochGate.Image(
                sha(code), sha('1'), sha('2'), sha('3'));
    }

    private static ActorRoutePublicationV1.StateSnapshot state(int value) {
        return new ActorRoutePublicationV1.StateSnapshot(
                sha('1'), new byte[] {(byte) value});
    }

    private static ActorRoutePublicationV1<String> newAuthority(
            ActorUpgradeEpochGate gate, LogicalActorProxyMailboxV1 mailbox) {
        return new ActorRoutePublicationV1<>(gate, mailbox, state(7), "old-physical");
    }

    @Test
    void singleSnapshotPublishesIdentityCodeStatePhysicalAndWatermarkTogether() {
        try (var gate = new ActorUpgradeEpochGate(40, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(8, 64, 128);
             var authority = newAuthority(gate, mailbox)) {
            var old = authority.current();
            assertEquals(40, old.actorIdentity());
            authority.admit(new byte[] {1});
            List<String> delivered = new ArrayList<>();
            var oldReceipt = authority.dispatchNext((route, envelope, receipt) ->
                    delivered.add(route + ":" + envelope.messageSequence()));
            assertNotNull(oldReceipt);
            assertEquals(40L, oldReceipt.actorIdentity());
            assertEquals(1L, oldReceipt.incarnation());
            assertEquals(1L, oldReceipt.routeEpoch());
            assertEquals(1L, oldReceipt.messageSequence());
            assertEquals(List.of("old-physical:1"), delivered);

            try (var stage = authority.stage(image('b'))) {
                var queued = authority.admit(new byte[] {2});
                assertEquals(2, queued.messageSequence());
                assertFalse(stage.quiescent());
                assertNull(authority.dispatchNext(
                        (route, mail, receipt) -> fail("frozen")));
                oldReceipt.complete();
                assertTrue(stage.quiescent());
                var restored = state(9);
                assertTrue(stage.publish(restored, "new-physical"));
                var next = authority.current();
                assertEquals(old.actorIdentity(), next.actorIdentity());
                assertEquals(old.incarnation(), next.incarnation());
                assertEquals(2, next.routeEpoch());
                assertEquals(image('b'), next.image());
                assertSame(restored, next.state());
                assertEquals("new-physical", next.physicalRoute());
                assertEquals(1, next.oldDispatchWatermark());
                assertEquals(2, mailbox.snapshot().routeEpoch());
                assertFalse(mailbox.snapshot().dispatchFrozen());
                assertEquals(0, gate.livePins());
                assertThrows(IllegalStateException.class, gate::pin);
                try (var newTask = next.taskAdmission().pin(
                        ActorUpgradeEpochGate.LeaseKind.SUSPENDED_TASK)) {
                    assertTrue(next.taskAdmission().permits(newTask));
                }
                var newReceipt = authority.dispatchNext((route, envelope, receipt) ->
                        delivered.add(route + ":" + envelope.messageSequence()));
                assertNotNull(newReceipt);
                assertEquals(2L, newReceipt.routeEpoch());
                assertEquals(List.of("old-physical:1", "new-physical:2"), delivered);
                newReceipt.complete();
                assertEquals(0, mailbox.snapshot().retainedMessages());
            }
        }
    }

    @Test
    void oldSuspendedTaskAndLogicalMailBothGateCommit() {
        try (var gate = new ActorUpgradeEpochGate(41, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox)) {
            var lease = gate.pin(ActorUpgradeEpochGate.LeaseKind.SUSPENDED_TASK);
            authority.admit(new byte[] {1});
            var messageReceipt = authority.dispatchNext((r, e, receipt) -> {});
            assertNotNull(messageReceipt);
            try (var stage = authority.stage(image('b'))) {
                assertFalse(stage.publish(state(2), "candidate"));
                messageReceipt.complete();
                assertFalse(stage.publish(state(2), "candidate"),
                        "Task remains pinned after the last old mail.done");
                lease.close(); // actual system closes only on physical Task exit
                assertTrue(stage.publish(state(2), "candidate"));
                assertEquals(2, authority.current().routeEpoch());
            } finally {
                lease.close();
            }
        }
    }

    @Test
    void rollbackReopensOldRouteWithoutEpochOrSequenceChange() {
        try (var gate = new ActorUpgradeEpochGate(42, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox)) {
            authority.admit(new byte[] {1});
            try (var stage = authority.stage(image('b'))) {
                authority.admit(new byte[] {2});
                assertTrue(stage.quiescent());
            }
            assertEquals(image('a'), authority.current().image());
            assertEquals(1, authority.current().routeEpoch());
            assertEquals(1, mailbox.snapshot().routeEpoch());
            List<Long> routed = new ArrayList<>();
            var firstReceipt = authority.dispatchNext((r, e, receipt) -> {
                assertEquals("old-physical", r);
                routed.add(e.messageSequence());
            });
            assertNotNull(firstReceipt);
            firstReceipt.complete();
            var secondReceipt = authority.dispatchNext(
                    (r, e, receipt) -> routed.add(e.messageSequence()));
            assertNotNull(secondReceipt);
            secondReceipt.complete();
            assertEquals(List.of(1L, 2L), routed);
            assertFalse(gate.frozen());
        }
    }

    @Test
    void schemaMismatchAndSamePhysicalRouteRejectBeforePublication() {
        try (var gate = new ActorUpgradeEpochGate(43, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox);
             var stage = authority.stage(image('b'))) {
            var invalid = new ActorRoutePublicationV1.StateSnapshot(
                    sha('f'), new byte[]{1});
            assertThrows(SecurityException.class,
                    () -> stage.publish(invalid, "candidate"));
            assertThrows(IllegalArgumentException.class,
                    () -> stage.publish(state(3), "old-physical"));
            assertEquals(1, authority.current().routeEpoch());
            assertEquals(image('a'), authority.current().image());
            assertTrue(mailbox.snapshot().dispatchFrozen());
            assertTrue(stage.publish(state(3), "candidate"));
            assertThrows(IllegalStateException.class,
                    () -> stage.publish(state(3), "candidate"));
        }
    }

    @Test
    void staleStageAndDuplicatePublicationCannotReplayRouteChange() {
        try (var gate = new ActorUpgradeEpochGate(44, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox)) {
            var stale = authority.stage(image('b'));
            stale.close();
            assertThrows(IllegalStateException.class, stale::quiescent);
            assertThrows(IllegalStateException.class,
                    () -> stale.publish(state(2), "candidate"));
            try (var fresh = authority.stage(image('c'))) {
                assertTrue(fresh.publish(state(3), "replacement"));
            }
            assertEquals(image('c'), authority.current().image());
            assertEquals(2, authority.current().routeEpoch());
        }
    }

    @Test
    void authorityLossBeforePublicationCannotExposeCandidateOrOldData() {
        try (var gate = new ActorUpgradeEpochGate(45, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox)) {
            var stage = authority.stage(image('b'));
            gate.close(); // supervisor teardown invalidates candidate
            assertThrows(IllegalStateException.class,
                    () -> stage.publish(state(2), "candidate"));
            assertEquals(image('a'), authority.current().image());
            assertTrue(mailbox.snapshot().dispatchFrozen());
            assertThrows(IllegalStateException.class, stage::close);
            assertThrows(IllegalStateException.class, authority::current);
            assertThrows(IllegalStateException.class,
                    () -> authority.dispatchNext((r, e, receipt) -> fail("unsafe")));
            assertEquals(1, mailbox.snapshot().routeEpoch());
        }
    }

    @Test
    void stateBytesAreDefensivelyClonedAndDigestBound() {
        byte[] bytes = {1, 2, 3};
        var snapshot = new ActorRoutePublicationV1.StateSnapshot(sha('1'), bytes);
        var digest = snapshot.contentSha256();
        bytes[0] = 9;
        snapshot.bytes()[1] = 8;
        assertArrayEquals(new byte[] {1, 2, 3}, snapshot.bytes());
        assertEquals(digest, snapshot.contentSha256());
        assertEquals(64, digest.length());
    }

    @Test
    void concurrentPhysicalAdmissionCannotRacePastFreeze() throws Exception {
        try (var gate = new ActorUpgradeEpochGate(46, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox);
             var pool = Executors.newFixedThreadPool(2)) {
            authority.admit(new byte[] {1});
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var send = pool.submit(() -> authority.dispatchNext((route, msg, receipt) -> {
                assertEquals("old-physical", route);
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("test submit blocked too long");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var staging = pool.submit(() -> authority.stage(image('b')));
            try {
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> staging.get(50, TimeUnit.MILLISECONDS),
                        "stage cannot freeze or publish inside a physical send");
            } finally {
                release.countDown();
            }
            var inFlightReceipt = send.get(5, TimeUnit.SECONDS);
            assertNotNull(inFlightReceipt);
            try (var stage = staging.get(5, TimeUnit.SECONDS)) {
                assertFalse(stage.quiescent());
                inFlightReceipt.complete();
                assertTrue(stage.publish(state(2), "replacement"));
                assertEquals(2, authority.current().routeEpoch());
            }
        }
    }

    @Test
    void protectedKindsRemainFailClosedUntilAttestedWorkerBackend() {
        var protectedPlacement = new ActorUpgradeEpochGate.Placement(
                ActorUpgradeEpochGate.ActorKind.PRIVATE,
                ActorUpgradeEpochGate.ProtectionDomain.TRUSTED_PROCESS,
                sha('0'));
        try (var gate = new ActorUpgradeEpochGate(47, protectedPlacement, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128)) {
            assertThrows(SecurityException.class,
                    () -> newAuthority(gate, mailbox));
        }
    }
    @Test
    void candidateAuthorityLockExcludesConcurrentTeardownAtCommitPoint() throws Exception {
        try (var gate = new ActorUpgradeEpochGate(48, image('a'));
             var pool = Executors.newFixedThreadPool(2)) {
            var candidate = gate.prepare(image('b'));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var committing = pool.submit(() ->
                    gate.withPreparedAuthority(candidate, () -> {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("test authority callback stalled");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(e);
                        }
                        assertTrue(gate.ownsPrepared(candidate));
                        return true;
                    }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var teardown = pool.submit(gate::close);
            try {
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> teardown.get(50, TimeUnit.MILLISECONDS),
                        "teardown must not invalidate a publication mid-CAS");
            } finally {
                release.countDown();
            }
            assertTrue(committing.get(5, TimeUnit.SECONDS));
            teardown.get(5, TimeUnit.SECONDS);
            assertTrue(gate.retired());
        }
    }
    @Test
    void oversizedStateCheckpointIsRejectedBeforeImmutableCopy() {
        assertThrows(IllegalArgumentException.class,
                () -> new ActorRoutePublicationV1.StateSnapshot(
                        sha('1'), new byte[1024 * 1024 + 1]));
    }

    @Test
    void rejectedStageNeverStrandsOldAdmissionOrLosesBufferedMessages() {
        try (var gate = new ActorUpgradeEpochGate(49, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox)) {
            authority.admit(new byte[]{11});
            var wrongSchema = new ActorUpgradeEpochGate.Image(
                    sha('b'), sha('9'), sha('2'), sha('3'));
            assertThrows(SecurityException.class, () -> authority.stage(wrongSchema));
            assertFalse(gate.frozen(), "incompatible image may not freeze Task admission");
            assertFalse(mailbox.snapshot().dispatchFrozen(),
                    "rejected staging cannot lock the old DATA route");
            assertEquals(image('a'), authority.current().image());
            assertEquals(1L, authority.current().routeEpoch());
            assertEquals(1, mailbox.snapshot().retainedMessages());

            var retainedReceipt = authority.dispatchNext((route, envelope, receipt) -> {
                assertEquals("old-physical", route);
                assertEquals(1L, envelope.messageSequence());
            });
            assertNotNull(retainedReceipt);
            retainedReceipt.complete();
            assertEquals(0, mailbox.snapshot().retainedMessages());

            try (var valid = authority.stage(image('c'))) {
                assertTrue(valid.quiescent());
                assertTrue(valid.publish(state(17), "candidate"));
            }
            assertEquals(image('c'), authority.current().image());
            assertEquals(2L, authority.current().routeEpoch());
        }
    }

    @Test
    void completionReceiptIsSingleUseAndCannotBeReplayedAfterCompletion() {
        try (var gate = new ActorUpgradeEpochGate(51, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox)) {
            authority.admit(new byte[] {1});
            var receipt = authority.dispatchNext((route, envelope, completion) -> {
                assertEquals("old-physical", route);
                assertEquals(1L, completion.messageSequence());
            });
            assertNotNull(receipt);
            receipt.complete();
            assertThrows(IllegalStateException.class, receipt::complete);
            assertEquals(1L, mailbox.snapshot().completionWatermark());
            assertEquals(0, mailbox.snapshot().retainedMessages());
        }
    }

    @Test
    void physicalFenceCommitFailureQuarantinesRatherThanReopeningOldData() {
        var gate = new ActorUpgradeEpochGate(52, image('a'));
        var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
        var authority = newAuthority(gate, mailbox);
        var stage = authority.stage(image('b'));
        assertTrue(stage.quiescent());

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> stage.publish(
                        state(2),
                        "candidate",
                        () -> {
                            throw new IllegalStateException(
                                    "physical fence commit failed");
                        }));
        assertTrue(failure.getMessage().contains("DATA quarantined"));
        assertTrue(mailbox.snapshot().dispatchFrozen(),
                "ambiguous physical commit must retain the logical DATA freeze");
        assertThrows(IllegalStateException.class, authority::current,
                "quarantined authority must not expose a routable old generation");
        assertThrows(IllegalStateException.class, stage::close,
                "ordinary cleanup must not reopen an ambiguous old route");

        // The authority intentionally remains quarantined/frozen; force-close
        // is not modeled by this host primitive.
        mailbox.close();
        gate.close();
    }

    @Test
    void competingStagingAttemptCannotTakeOrReleaseAnotherStageFreeze() {
        try (var gate = new ActorUpgradeEpochGate(50, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox)) {
            try (var first = authority.stage(image('b'))) {
                assertThrows(IllegalStateException.class,
                        () -> authority.stage(image('c')));
                assertTrue(gate.frozen());
                assertTrue(mailbox.snapshot().dispatchFrozen());
                assertEquals(1L, authority.current().routeEpoch());
            }
            assertFalse(gate.frozen());
            assertFalse(mailbox.snapshot().dispatchFrozen());
            assertEquals(image('a'), authority.current().image());
            try (var next = authority.stage(image('c'))) {
                assertTrue(next.publish(state(12), "second-candidate"));
            }
            assertEquals(2L, authority.current().routeEpoch());
        }
    }
}
