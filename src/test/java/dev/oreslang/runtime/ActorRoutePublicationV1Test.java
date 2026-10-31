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
            assertTrue(authority.dispatchNext((route, envelope) ->
                    delivered.add(route + ":" + envelope.messageSequence())));
            assertEquals(List.of("old-physical:1"), delivered);

            try (var stage = authority.stage(image('b'))) {
                var queued = authority.admit(new byte[] {2});
                assertEquals(2, queued.messageSequence());
                assertFalse(stage.quiescent());
                assertFalse(authority.dispatchNext((route, mail) -> fail("frozen")));
                authority.complete(1);
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
                assertTrue(authority.dispatchNext((route, envelope) ->
                        delivered.add(route + ":" + envelope.messageSequence())));
                assertEquals(List.of("old-physical:1", "new-physical:2"), delivered);
                authority.complete(2);
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
            assertTrue(authority.dispatchNext((r, e) -> {}));
            try (var stage = authority.stage(image('b'))) {
                assertFalse(stage.publish(state(2), "candidate"));
                authority.complete(1);
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
            assertTrue(authority.dispatchNext((r, e) -> {
                assertEquals("old-physical", r);
                routed.add(e.messageSequence());
            }));
            authority.complete(1);
            assertTrue(authority.dispatchNext((r, e) -> routed.add(e.messageSequence())));
            authority.complete(2);
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
                    () -> authority.dispatchNext((r,e) -> fail("unsafe")));
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
    void physicalSubmitDoesNotHoldCoordinatorLockButPinsOldRoute() throws Exception {
        try (var gate = new ActorUpgradeEpochGate(46, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox);
             var pool = Executors.newFixedThreadPool(2)) {
            authority.admit(new byte[] {1});
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var send = pool.submit(() -> authority.dispatchNext((route, msg) -> {
                assertEquals("old-physical", route);
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("test transport submit stalled");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            try {
                // A second thread can inspect CONTROL while the physical
                // transport callback is parked. No external callback is run
                // under the coordinator lock.
                var observer = pool.submit(authority::current);
                assertEquals("old-physical",
                        observer.get(2, TimeUnit.SECONDS).physicalRoute());
                // No freeze, publication or retirement while send ownership
                // is ambiguous. It must be retried after the submit returns.
                assertThrows(IllegalStateException.class,
                        () -> authority.stage(image('b')));
                assertThrows(IllegalStateException.class, authority::close);
                assertEquals(1, mailbox.snapshot().outstandingMessages());
            } finally {
                release.countDown();
            }
            assertTrue(send.get(5, TimeUnit.SECONDS));
            try (var stage = authority.stage(image('b'))) {
                assertFalse(stage.quiescent());
                authority.complete(1);
                assertTrue(stage.publish(state(2), "replacement"));
                assertEquals(2, authority.current().routeEpoch());
            }
        }
    }

    @Test
    void ambiguousPhysicalSubmitPoisonsDataWithoutRefundOrReplay() {
        // The quarantined authority is intentionally NOT auto-closed: close()
        // must reject its still outstanding physical message instead of
        // silently voiding the ambiguous delivery. Test-owned gate/mailbox
        // cleanup is separate from proving supervisor release authorization.
        try (var gate = new ActorUpgradeEpochGate(51, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128)) {
            var authority = newAuthority(gate, mailbox);
            authority.admit(new byte[] {9});
            assertThrows(IllegalStateException.class,
                    () -> authority.dispatchNext((route, msg) -> {
                        throw new IllegalStateException("delivery acceptance unknown");
                    }));
            assertEquals(1, mailbox.snapshot().outstandingMessages());
            assertEquals(1, mailbox.snapshot().retainedMessages());
            assertThrows(IllegalStateException.class, authority::current);
            assertThrows(IllegalStateException.class,
                    () -> authority.stage(image('b')));
            assertThrows(IllegalStateException.class,
                    () -> authority.dispatchNext((route, msg) -> fail("replay")));
            assertThrows(IllegalStateException.class, authority::close,
                    "quarantined ambiguous mail cannot be dropped by normal shutdown");
            assertEquals(1, mailbox.snapshot().retainedMessages());
        }
    }

    @Test
    void trackedAsyncDispatchStaysPinnedAfterSubmitReturnsUntilPhysicalCompletion() {
        try (var gate = new ActorUpgradeEpochGate(52, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox)) {
            authority.admit(new byte[]{1});
            var handle = new java.util.concurrent.atomic.AtomicReference<
                    ActorRoutePublicationV1<String>.PhysicalCompletion>();
            assertTrue(authority.dispatchNextTracked((route, envelope, receipt) -> {
                assertEquals("old-physical", route);
                assertEquals(1L, envelope.messageSequence());
                handle.set(receipt);
            }));
            assertEquals(1, mailbox.snapshot().outstandingMessages());
            assertThrows(IllegalStateException.class,
                    () -> authority.complete(1L),
                    "bare sequence cannot forge a tracked physical completion");
            assertEquals(1, mailbox.snapshot().retainedMessages());
            assertThrows(IllegalStateException.class,
                    () -> authority.stage(image('b')),
                    "return from async transport submission is not physical completion");
            assertThrows(IllegalStateException.class, authority::close,
                    "logical close must not free physical actor while old work is active");
            assertEquals(1L, authority.current().routeEpoch());
            handle.get().acknowledge();
            assertThrows(IllegalStateException.class, handle.get()::acknowledge);
            assertEquals(0, mailbox.snapshot().outstandingMessages());
            try (var stage = authority.stage(image('b'))) {
                assertTrue(stage.quiescent());
                assertTrue(stage.publish(state(8), "new-physical"));
            }
            assertEquals("new-physical", authority.current().physicalRoute());
        }
    }

    @Test
    void earlyReceiptDoesNotFreePhysicalSubmitWhileCallbackStillExecuting()
            throws Exception {
        try (var gate = new ActorUpgradeEpochGate(53, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox);
             var pool = Executors.newSingleThreadExecutor()) {
            authority.admit(new byte[]{1});
            var acknowledged = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var submit = pool.submit(() ->
                    authority.dispatchNextTracked((route, envelope, receipt) -> {
                        receipt.acknowledge();
                        acknowledged.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("tracked callback stalled");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(error);
                        }
                    }));
            assertTrue(acknowledged.await(5, TimeUnit.SECONDS));
            try {
                assertEquals(0, mailbox.snapshot().outstandingMessages());
                assertThrows(IllegalStateException.class, () -> authority.stage(image('b')));
                assertThrows(IllegalStateException.class, authority::close);
            } finally {
                release.countDown();
            }
            assertTrue(submit.get(5, TimeUnit.SECONDS));
            try (var stage = authority.stage(image('b'))) {
                assertTrue(stage.publish(state(3), "replacement"));
            }
            assertEquals(2L, authority.current().routeEpoch());
        }
    }

    @Test
    void synchronousTransportReturnCannotAuthorizePrematureLogicalShutdown() {
        try (var gate = new ActorUpgradeEpochGate(54, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox)) {
            authority.admit(new byte[]{1});
            assertTrue(authority.dispatchNext((route, envelope) ->
                    assertEquals("old-physical", route)));
            assertThrows(IllegalStateException.class, authority::close,
                    "live dispatched mail retains its physical generation");
            assertEquals(1, mailbox.snapshot().retainedMessages());
            assertEquals(image('a'), authority.current().image());
            authority.complete(1L);
            authority.close();
            assertTrue(mailbox.snapshot().closed());
        }
    }

    @Test
    void suspendedTaskPinBlocksLogicalShutdownEvenWithEmptyMailbox() {
        try (var gate = new ActorUpgradeEpochGate(55, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox)) {
            var continuation = gate.pin(ActorUpgradeEpochGate.LeaseKind.SUSPENDED_TASK);
            try {
                assertThrows(IllegalStateException.class, authority::close);
                assertSame(gate, authority.current().taskAdmission());
                assertFalse(mailbox.snapshot().closed());
            } finally {
                continuation.close();
            }
            authority.close();
            assertTrue(gate.retired());
        }
    }

    @Test
    void ordinaryShutdownRequiresExplicitStageRollback() {
        try (var gate = new ActorUpgradeEpochGate(56, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox)) {
            var stage = authority.stage(image('b'));
            assertThrows(IllegalStateException.class, authority::close);
            assertTrue(mailbox.snapshot().dispatchFrozen());
            stage.close();
            assertFalse(mailbox.snapshot().dispatchFrozen());
            authority.close();
            assertTrue(mailbox.snapshot().closed());
        }
    }

    @Test
    void queuedAdmissionWithoutAnyDispatchBlocksShutdownAndRetainsCredits() {
        try (var gate = new ActorUpgradeEpochGate(57, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox)) {
            var queued = authority.admit(new byte[]{7, 8});
            var before = mailbox.snapshot();
            assertEquals(1, before.queuedMessages());
            assertEquals(0, before.outstandingMessages());
            assertThrows(IllegalStateException.class, authority::close,
                    "an admitted FIFO envelope is not disposable by ordinary shutdown");
            var unchanged = mailbox.snapshot();
            assertFalse(unchanged.closed());
            assertEquals(before.routeEpoch(), unchanged.routeEpoch());
            assertEquals(before.nextMessageSequence(), unchanged.nextMessageSequence());
            assertEquals(before.retainedMessages(), unchanged.retainedMessages());
            assertEquals(before.retainedBytes(), unchanged.retainedBytes());
            assertEquals(image('a'), authority.current().image());
            assertTrue(authority.dispatchNext((route, envelope) -> {
                assertEquals("old-physical", route);
                assertEquals(queued.messageSequence(), envelope.messageSequence());
                assertArrayEquals(new byte[]{7, 8}, envelope.payload());
            }));
            authority.complete(queued.messageSequence());
            assertEquals(0, mailbox.snapshot().retainedMessages());
            authority.close();
            assertTrue(mailbox.snapshot().closed());
        }
    }

    @Test
    void rejectedShutdownPreservesMultipleQueuedMessagesAndFifoDrainOrder() {
        try (var gate = new ActorUpgradeEpochGate(58, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox)) {
            authority.admit(new byte[]{1});
            authority.admit(new byte[]{2});
            assertThrows(IllegalStateException.class, authority::close);
            assertEquals(2, mailbox.snapshot().queuedMessages());
            var delivered = new ArrayList<Long>();
            for (long expected = 1; expected <= 2; expected++) {
                long sequence = expected;
                assertTrue(authority.dispatchNext((route, envelope) -> {
                    assertEquals("old-physical", route);
                    assertEquals(sequence, envelope.messageSequence());
                    delivered.add(envelope.messageSequence());
                }));
                authority.complete(expected);
            }
            assertEquals(List.of(1L, 2L), delivered);
            assertEquals(2L, mailbox.snapshot().completionWatermark());
            assertEquals(0, mailbox.snapshot().retainedBytes());
            authority.close();
            assertTrue(mailbox.snapshot().closed());
        }
    }

    @Test
    void stagedUpgradeCannotDiscardMessagesAdmittedWhileOldDispatchFrozen() {
        try (var gate = new ActorUpgradeEpochGate(59, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128);
             var authority = newAuthority(gate, mailbox)) {
            var stage = authority.stage(image('b'));
            var accepted = authority.admit(new byte[]{42});
            assertEquals(1L, accepted.messageSequence());
            assertEquals(1, mailbox.snapshot().queuedMessages());
            assertThrows(IllegalStateException.class, authority::close);
            assertTrue(mailbox.snapshot().dispatchFrozen());
            assertFalse(mailbox.snapshot().closed());
            assertEquals(1, mailbox.snapshot().retainedMessages());
            stage.close(); // authorized rollback reopens V1 DATA
            assertEquals(1L, authority.current().routeEpoch());
            assertTrue(authority.dispatchNext((route, envelope) -> {
                assertEquals("old-physical", route);
                assertEquals(1L, envelope.messageSequence());
                assertArrayEquals(new byte[]{42}, envelope.payload());
            }));
            authority.complete(accepted.messageSequence());
            authority.close();
            assertTrue(mailbox.snapshot().closed());
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void realSharedActorDrainRatherThanEnqueueAuthorizesTrackedRouteCutover()
            throws Exception {
        var releaseOldTurn = new CountDownLatch(1);
        try (var runtime = new ActorRuntime();
             var gate = new ActorUpgradeEpochGate(60, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128)) {
            var oldEntered = new CountDownLatch(1);
            var newDelivered = new CountDownLatch(1);
            var oldRoute = runtime.<List<Integer>>spawnShared(() ->
                    (message, context) -> {
                        assertEquals(List.of(7), message);
                        oldEntered.countDown();
                        try {
                            if (!releaseOldTurn.await(10, TimeUnit.SECONDS))
                                throw new IllegalStateException("test old actor turn stalled");
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    });
            var newRoute = runtime.<List<Integer>>spawnShared(() ->
                    (message, context) -> {
                        assertEquals(List.of(9), message);
                        newDelivered.countDown();
                    });
            var decode = (java.util.function.Function<byte[], List<Integer>>) payload -> {
                List<Integer> values = new ArrayList<>();
                for (byte item : payload) values.add(Byte.toUnsignedInt(item));
                return List.copyOf(values);
            };
            var physical = ActorRoutePublicationV1.actorRuntimeDrainSubmitter(decode);
            try (var authority = new ActorRoutePublicationV1<>(
                    gate, mailbox, state(7), oldRoute)) {
                authority.admit(new byte[]{7});
                assertTrue(authority.dispatchNextTracked(physical));
                assertTrue(oldEntered.await(10, TimeUnit.SECONDS));
                assertEquals(1, mailbox.snapshot().outstandingMessages());
                assertThrows(IllegalStateException.class, () -> authority.stage(image('b')),
                        "ActorRef.send acceptance must not authorize an upgrade");
                assertThrows(IllegalStateException.class, authority::close);
                releaseOldTurn.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (mailbox.snapshot().retainedMessages() != 0
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(0, mailbox.snapshot().retainedMessages(),
                        "runtime drain must acknowledge only after old turn completes");
                try (var stage = authority.stage(image('b'))) {
                    authority.admit(new byte[]{9});
                    assertTrue(stage.publish(state(9), newRoute));
                }
                assertSame(newRoute, authority.current().physicalRoute());
                assertEquals(2L, authority.current().routeEpoch());
                assertTrue(authority.dispatchNextTracked(physical));
                assertTrue(newDelivered.await(10, TimeUnit.SECONDS));
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (mailbox.snapshot().retainedMessages() != 0
                        && System.nanoTime() < deadline) {
                    Thread.sleep(10);
                }
                assertEquals(0, mailbox.snapshot().retainedMessages());
                authority.close();
                assertTrue(mailbox.snapshot().closed());
            }
        } finally {
            releaseOldTurn.countDown();
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void failedPhysicalActorDrainQuarantinesWithoutCreditingLogicalDelivery()
            throws Exception {
        try (var runtime = new ActorRuntime();
             var gate = new ActorUpgradeEpochGate(61, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128)) {
            var attempted = new CountDownLatch(1);
            var failingRoute = runtime.<List<Integer>>spawnShared(() ->
                    (message, context) -> {
                        attempted.countDown();
                        throw new IllegalStateException("physical actor turn failed");
                    });
            var authority = new ActorRoutePublicationV1<>(
                    gate, mailbox, state(7), failingRoute);
            authority.admit(new byte[]{1});
            var trusted = ActorRoutePublicationV1.actorRuntimeDrainSubmitter(
                    (java.util.function.Function<byte[], List<Integer>>) bytes ->
                            List.of(Byte.toUnsignedInt(bytes[0])));
            assertTrue(authority.dispatchNextTracked(trusted));
            assertTrue(attempted.await(10, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean quarantined = false;
            while (System.nanoTime() < deadline) {
                try {
                    authority.current();
                } catch (IllegalStateException rejected) {
                    quarantined = true;
                    break;
                }
                Thread.sleep(10);
            }
            assertTrue(quarantined, "failed actor drain must quarantine DATA");
            assertEquals(1, mailbox.snapshot().outstandingMessages());
            assertEquals(1, mailbox.snapshot().retainedMessages());
            assertEquals(0L, mailbox.snapshot().completionWatermark(),
                    "failed physical delivery never earns a completion receipt");
            assertThrows(IllegalStateException.class, authority::close);
            assertThrows(IllegalStateException.class,
                    () -> authority.dispatchNextTracked(trusted),
                    "ambiguous physical failure cannot replay DATA");
            // Test fixture owns the host runtime and mailbox teardown. The
            // logical authority intentionally stays quarantined; this is NOT
            // a proof of an authorized production cancellation/disposition.
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void hostMailboxDrainCannotReleasePhysicalTurnExecutorBeforeItExits()
            throws Exception {
        var handled = new CountDownLatch(1);
        var pastGuest = new CountDownLatch(1);
        var releaseCarrier = new CountDownLatch(1);
        var parked = new java.util.concurrent.atomic.AtomicBoolean();
        ActorRuntime.TurnExecutor executor = turn -> {
            turn.run();
            if (handled.getCount() == 0 && parked.compareAndSet(false, true)) {
                pastGuest.countDown();
                try {
                    if (!releaseCarrier.await(10, TimeUnit.SECONDS))
                        throw new IllegalStateException("test carrier-exit release timed out");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }
        };
        var config = new ActorRuntime.DispatcherConfig(1, 2, 16, 16);
        try (var runtime = new ActorRuntime(IsolatePolicy.developer(), config, executor);
             var gate = new ActorUpgradeEpochGate(62, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128)) {
            var old = runtime.<List<Integer>>spawnShared(() ->
                    (msg, context) -> {
                        assertEquals(List.of(3), msg);
                        handled.countDown();
                    });
            var authority = new ActorRoutePublicationV1<>(gate, mailbox, state(7), old);
            var converter = ActorRoutePublicationV1.actorRuntimeDrainSubmitter(
                    (java.util.function.Function<byte[], List<Integer>>) bytes ->
                            List.of(Byte.toUnsignedInt(bytes[0])));
            try {
                authority.admit(new byte[]{3});
                assertTrue(authority.dispatchNextTracked(converter));
                assertTrue(pastGuest.await(10, TimeUnit.SECONDS));
                // Historical drain() observes the guest message's logical
                // watermark before the enclosing physical TurnExecutor exits.
                assertTrue(old.drain().isDone());
                assertFalse(old.physicalDrain().isDone());
                assertEquals(1, mailbox.snapshot().retainedMessages(),
                        "logical completion alone cannot credit physical receipt");
                assertThrows(IllegalStateException.class,
                        () -> authority.stage(image('b')));
                releaseCarrier.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (mailbox.snapshot().retainedMessages() != 0
                        && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(0, mailbox.snapshot().retainedMessages());
                try (var staged = authority.stage(image('b'))) {
                    assertTrue(staged.publish(state(9), "replacement"));
                }
            } finally {
                releaseCarrier.countDown();
            }
        }
    }

    @Test
    @org.junit.jupiter.api.Timeout(30)
    void suspendedSourceTaskKeepsV1PinnedAfterItsMailboxMessageIsProcessed()
            throws Exception {
        var taskStarted = new CountDownLatch(1);
        var releaseTask = new OresFuture<Void>();
        try (var runtime = new ActorRuntime();
             var gate = new ActorUpgradeEpochGate(63, image('a'));
             var mailbox = new LogicalActorProxyMailboxV1(4, 64, 128)) {
            var old = runtime.<List<Integer>>spawnShared(() ->
                    (msg, context) -> {
                        assertEquals(List.of(4), msg);
                        context.runtime().<Void>startActorTask(resume -> {
                            if (resume.initial()) {
                                taskStarted.countDown();
                                return OresScheduler.<Void>await(releaseTask);
                            }
                            return OresScheduler.done(null);
                        });
                    });
            var authority = new ActorRoutePublicationV1<>(gate, mailbox, state(7), old);
            var converter = ActorRoutePublicationV1.actorRuntimeDrainSubmitter(
                    (java.util.function.Function<byte[], List<Integer>>) bytes ->
                            List.of(Byte.toUnsignedInt(bytes[0])));
            try {
                authority.admit(new byte[]{4});
                assertTrue(authority.dispatchNextTracked(converter));
                assertTrue(taskStarted.await(10, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (!old.drain().isDone() && System.nanoTime() < deadline)
                    Thread.sleep(10);
                assertTrue(old.drain().isDone(),
                        "mailbox turn has finished while its child Task awaits");
                assertFalse(old.physicalDrain().isDone());
                assertEquals(1, mailbox.snapshot().retainedMessages());
                assertThrows(IllegalStateException.class, () -> authority.stage(image('b')));
                releaseTask.completeFromRuntime(null);
                deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (mailbox.snapshot().retainedMessages() != 0
                        && System.nanoTime() < deadline) Thread.sleep(10);
                assertEquals(0, mailbox.snapshot().retainedMessages(),
                        "only physical child Task quiescence may release old route");
                try (var staged = authority.stage(image('b'))) {
                    assertTrue(staged.publish(state(9), "replacement"));
                }
            } finally {
                releaseTask.completeFromRuntime(null);
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

            assertTrue(authority.dispatchNext((route, envelope) -> {
                assertEquals("old-physical", route);
                assertEquals(1L, envelope.messageSequence());
            }));
            authority.complete(1L);
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
