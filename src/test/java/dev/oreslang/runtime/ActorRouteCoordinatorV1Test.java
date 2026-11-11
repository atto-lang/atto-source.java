package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real JUnit tests for HOST-only route publication; not compiler guest tests. */
final class ActorRouteCoordinatorV1Test {
    private static String sha(char digit) { return String.valueOf(digit).repeat(64); }

    private static ActorUpgradeEpochGate.Image image(char code) {
        return new ActorUpgradeEpochGate.Image(sha(code), sha('1'), sha('2'), sha('3'));
    }

    private static ActorRouteCoordinatorV1.StateSnapshot state(int count) {
        return new ActorRouteCoordinatorV1.StateSnapshot(
                sha('1'), ByteBuffer.allocate(4).putInt(count).array());
    }

    private static int count(ActorRouteCoordinatorV1.StateSnapshot snapshot) {
        return ByteBuffer.wrap(snapshot.bytes()).getInt();
    }

    private static final class Physical {
        final String label;
        final int stride;
        final List<Long> received = new ArrayList<>();
        int count;

        Physical(String label, int stride, int count) {
            this.label = label;
            this.stride = stride;
            this.count = count;
        }
    }

    private static ActorRouteCoordinatorV1<Physical> coordinator(
            Object seal, Physical initial, int maxMessages) {
        return new ActorRouteCoordinatorV1<>(77, seal, image('a'),
                state(initial.count), initial, maxMessages, 32, maxMessages * 32L);
    }

    private static ActorRouteCoordinatorV1.TrackedTransport<Physical> completed() {
        return (route, envelope, receipt) -> {
            route.count += route.stride * envelope.payload()[0];
            route.received.add(envelope.messageSequence());
            receipt.acknowledge(); // synchronous completion ONLY in this host simulator
        };
    }

    @Test void stableLogicalRefCountStateAndFifoAcrossV1V2() {
        Object seal = new Object();
        Physical old = new Physical("v1", 1, 0);
        try (var c = coordinator(seal, old, 32)) {
            var ref = c.logicalRef();
            for (int i = 0; i < 10; i++) ref.send(new byte[]{1});
            for (int i = 0; i < 10; i++) assertTrue(c.dispatchNext(seal, completed()));
            assertEquals(10, old.count);
            assertEquals(0, c.snapshot().mailbox().retainedMessages());

            var stage = c.stage(seal, image('b'));
            for (int i = 0; i < 5; i++) ref.send(new byte[]{1});
            assertEquals(5, c.snapshot().mailbox().queuedMessages());
            assertFalse(c.dispatchNext(seal, completed()));
            assertTrue(stage.quiescent(seal));

            var checkpoint = state(old.count);
            Physical newer = new Physical("v2", 1, count(checkpoint));
            assertTrue(stage.publish(seal, checkpoint, newer));
            assertSame(ref, c.logicalRef());
            assertEquals(77, ref.id());
            assertEquals(2, c.current().routeEpoch());
            assertSame(newer, c.current().physicalRoute());
            assertEquals(image('b'), c.current().image());
            assertEquals(10, count(c.current().state()));
            for (int i = 0; i < 5; i++) assertTrue(c.dispatchNext(seal, completed()));
            assertEquals(15, newer.count);
            assertEquals(10, old.count);
            assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L),
                    old.received);
            assertEquals(List.of(11L, 12L, 13L, 14L, 15L), newer.received);
            assertEquals(0, c.snapshot().mailbox().retainedMessages());
        }
    }

    @Test void asyncOldRouteReceiptBlocksCutoverAndKeepsCapacity() {
        Object seal = new Object();
        Physical old = new Physical("old", 1, 0);
        try (var c = coordinator(seal, old, 2)) {
            var ref = c.logicalRef();
            ref.send(new byte[]{1});
            AtomicReference<ActorRouteCoordinatorV1<Physical>.PhysicalCompletion> saved =
                    new AtomicReference<>();
            assertTrue(c.dispatchNext(seal, (r, msg, receipt) -> saved.set(receipt)));
            var stage = c.stage(seal, image('b'));
            ref.send(new byte[]{2});
            assertThrows(IllegalStateException.class, () -> ref.send(new byte[]{3}));
            assertEquals(2, c.snapshot().mailbox().retainedMessages());
            assertFalse(stage.quiescent(seal));
            assertFalse(stage.publish(seal, state(0), new Physical("new", 1, 0)));
            saved.get().acknowledge();
            assertTrue(stage.quiescent(seal));
            Physical next = new Physical("new", 1, 0);
            assertTrue(stage.publish(seal, state(0), next));
            assertTrue(c.dispatchNext(seal, completed()));
            assertEquals(2, next.count);
            assertEquals(List.of(2L), next.received);
        }
    }

    @Test void cancelBeforeCommitPreservesOldCodeAndQueuedFifo() {
        Object seal = new Object();
        Physical old = new Physical("v1", 1, 0);
        try (var c = coordinator(seal, old, 4)) {
            var ref = c.logicalRef();
            ref.send(new byte[]{1});
            try (var stage = c.stage(seal, image('b'))) {
                ref.send(new byte[]{2});
                assertThrows(IllegalStateException.class, stage::close);
                stage.abort(seal);
            }
            assertEquals(1, c.current().routeEpoch());
            assertSame(old, c.current().physicalRoute());
            assertTrue(c.dispatchNext(seal, completed()));
            assertTrue(c.dispatchNext(seal, completed()));
            assertEquals(3, old.count);
            assertEquals(List.of(1L, 2L), old.received);
        }
    }

    @Test void duplicateAndStalePhysicalReceiptsAreRejected() {
        Object seal = new Object();
        try (var c = coordinator(seal, new Physical("v1", 1, 0), 4)) {
            c.logicalRef().send(new byte[]{1});
            AtomicReference<ActorRouteCoordinatorV1<Physical>.PhysicalCompletion> saved =
                    new AtomicReference<>();
            c.dispatchNext(seal, (route, envelope, receipt) -> {
                saved.set(receipt);
                receipt.acknowledge();
            });
            assertThrows(IllegalStateException.class, saved.get()::acknowledge);
            var stage = c.stage(seal, image('b'));
            assertTrue(stage.publish(seal, state(1), new Physical("v2", 1, 1)));
            assertThrows(IllegalStateException.class, saved.get()::acknowledge);
        }
    }

    @Test void unknownPhysicalAcceptancePoisonsInsteadOfReplaying() {
        Object seal = new Object();
        var c = coordinator(seal, new Physical("old", 1, 0), 4);
        c.logicalRef().send(new byte[]{1});
        assertThrows(IllegalStateException.class, () ->
                c.dispatchNext(seal, (route, envelope, receipt) -> {
                    throw new IllegalStateException("transport acceptance unknown");
                }));
        assertTrue(c.snapshot().poisoned());
        assertEquals(1, c.snapshot().mailbox().outstandingMessages());
        assertEquals(1, c.snapshot().physicalSubmissions());
        assertThrows(IllegalStateException.class, () -> c.dispatchNext(seal, completed()));
        assertThrows(IllegalStateException.class, () -> c.stage(seal, image('b')));
        assertThrows(IllegalStateException.class, c::close);
    }

    @Test void wrongSealAndAbiDriftFailBeforeFreeze() {
        Object seal = new Object();
        try (var c = coordinator(seal, new Physical("old", 1, 0), 4)) {
            assertThrows(SecurityException.class, () ->
                    c.stage(new Object(), image('b')));
            assertThrows(SecurityException.class, () ->
                    c.dispatchNext(new Object(), completed()));
            assertThrows(SecurityException.class, () ->
                    c.pinOldTask(new Object()));
            var bad = new ActorUpgradeEpochGate.Image(
                    sha('b'), sha('4'), sha('2'), sha('3'));
            assertThrows(SecurityException.class, () -> c.stage(seal, bad));
            assertFalse(c.snapshot().mailbox().dispatchFrozen());
            try (var stage = c.stage(seal, image('b'))) {
                assertThrows(SecurityException.class, () -> stage.abort(new Object()));
                stage.abort(seal);
            }
            assertEquals(1, c.current().routeEpoch());
        }
    }

    @Test void incompatibleCheckpointRejectsWithoutPublishing() {
        Object seal = new Object();
        try (var c = coordinator(seal, new Physical("old", 1, 0), 4)) {
            var stage = c.stage(seal, image('b'));
            var mismatched = new ActorRouteCoordinatorV1.StateSnapshot(
                    sha('4'), new byte[]{1});
            assertThrows(SecurityException.class, () ->
                    stage.publish(seal, mismatched, new Physical("v2", 1, 0)));
            assertThrows(IllegalArgumentException.class, () ->
                    stage.publish(seal, state(0), c.current().physicalRoute()));
            assertEquals(1, c.current().routeEpoch());
            assertTrue(c.snapshot().mailbox().dispatchFrozen());
            assertTrue(stage.publish(seal, state(0), new Physical("v2", 1, 0)));
        }
    }

    @Test void taskPinBlocksPublicationEvenAfterOldMailDone() {
        Object seal = new Object();
        try (var c = coordinator(seal, new Physical("old", 1, 0), 4)) {
            var pin = c.pinOldTask(seal);
            var stage = c.stage(seal, image('b'));
            assertThrows(IllegalStateException.class, () -> c.pinOldTask(seal));
            assertFalse(stage.quiescent(seal));
            assertFalse(stage.publish(seal, state(0), new Physical("new", 1, 0)));
            pin.close();
            pin.close();
            assertTrue(stage.quiescent(seal));
            assertTrue(stage.publish(seal, state(0), new Physical("new", 1, 0)));
            assertEquals(2, c.current().routeEpoch());
        }
    }

    @Test void anEarlyAckCannotReleaseStillExecutingSubmit() throws Exception {
        Object seal = new Object();
        try (var c = coordinator(seal, new Physical("old", 1, 0), 4)) {
            c.logicalRef().send(new byte[]{1});
            var acknowledged = new CountDownLatch(1);
            var allowReturn = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>();
            Thread worker = new Thread(() -> {
                try {
                    c.dispatchNext(seal, (route, envelope, receipt) -> {
                        receipt.acknowledge();
                        acknowledged.countDown();
                        try {
                            if (!allowReturn.await(5, TimeUnit.SECONDS)) {
                                throw new AssertionError("physical submit did not exit");
                            }
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(failure);
                        }
                    });
                } catch (Throwable failure) { error.set(failure); }
            });
            worker.setDaemon(true);
            worker.start();
            assertTrue(acknowledged.await(5, TimeUnit.SECONDS));
            var stage = c.stage(seal, image('b'));
            try {
                assertEquals(0, c.snapshot().mailbox().outstandingMessages());
                assertEquals(1, c.snapshot().physicalSubmissions());
                assertFalse(stage.publish(seal, state(1), new Physical("v2", 1, 1)));
            } finally {
                allowReturn.countDown();
                worker.join(5000);
            }
            assertFalse(worker.isAlive());
            assertNull(error.get());
            assertTrue(stage.publish(seal, state(1), new Physical("v2", 1, 1)));
        }
    }

    @Test void transportMayReenterHostCoordinatorWithoutDeadlock() {
        Object seal = new Object();
        try (var c = coordinator(seal, new Physical("old", 1, 0), 4)) {
            c.logicalRef().send(new byte[]{1});
            AtomicReference<ActorRouteCoordinatorV1<Physical>.Stage> stage =
                    new AtomicReference<>();
            assertTrue(c.dispatchNext(seal, (route, envelope, receipt) -> {
                assertEquals(1, c.current().routeEpoch());
                stage.set(c.stage(seal, image('b')));
                c.logicalRef().send(new byte[]{2});
                receipt.acknowledge();
            }));
            assertEquals(0, c.snapshot().physicalSubmissions());
            assertTrue(stage.get().quiescent(seal));
            assertTrue(stage.get().publish(seal, state(1), new Physical("next", 1, 1)));
            assertTrue(c.dispatchNext(seal, completed()));
        }
    }

    @Test void liveMailOrActiveFreezeForbidsNormalClose() {
        Object seal = new Object();
        var c = coordinator(seal, new Physical("old", 1, 0), 4);
        c.logicalRef().send(new byte[]{1});
        assertThrows(IllegalStateException.class, c::close);
        var stage = c.stage(seal, image('b'));
        assertThrows(IllegalStateException.class, c::close);
        stage.abort(seal);
        c.dispatchNext(seal, completed());
        c.close();
        c.close();
        assertTrue(c.snapshot().closed());
        assertThrows(IllegalStateException.class, () -> c.logicalRef().send(new byte[]{2}));
    }

    @Test void snapshotBytesAreClonedAndCheckpointDigestStable() {
        byte[] source = {1, 2, 3};
        var checkpoint = new ActorRouteCoordinatorV1.StateSnapshot(sha('1'), source);
        String digest = checkpoint.contentSha256();
        source[0] = 7;
        checkpoint.bytes()[1] = 9;
        assertArrayEquals(new byte[]{1, 2, 3}, checkpoint.bytes());
        assertEquals(digest, checkpoint.contentSha256());
    }

    @Test void oldStageCannotPublishAfterAbortOrHandover() {
        Object seal = new Object();
        try (var c = coordinator(seal, new Physical("old", 1, 0), 4)) {
            var stale = c.stage(seal, image('b'));
            stale.abort(seal);
            assertThrows(IllegalStateException.class, () ->
                    stale.publish(seal, state(0), new Physical("unused", 1, 0)));
            var fresh = c.stage(seal, image('c'));
            assertTrue(fresh.publish(seal, state(0), new Physical("v3", 1, 0)));
            assertThrows(IllegalStateException.class, () -> fresh.abort(seal));
            assertEquals(2, c.current().routeEpoch());
            assertEquals(image('c'), c.current().image());
        }
    }
    @Test void twoConcurrentDispatchersCannotInvertPhysicalFifo() throws Exception {
        Object seal = new Object();
        Physical old = new Physical("v1", 1, 0);
        try (var c = coordinator(seal, old, 4)) {
            c.logicalRef().send(new byte[]{1});
            c.logicalRef().send(new byte[]{2});
            var callbackEntered = new CountDownLatch(1);
            var finishFirst = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            Thread first = new Thread(() -> {
                try {
                    assertTrue(c.dispatchNext(seal, (route, envelope, receipt) -> {
                        callbackEntered.countDown();
                        try {
                            if (!finishFirst.await(5, TimeUnit.SECONDS))
                                throw new AssertionError("first physical submit stalled");
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(error);
                        }
                        route.count += route.stride * envelope.payload()[0];
                        route.received.add(envelope.messageSequence());
                        receipt.acknowledge();
                    }));
                } catch (Throwable error) { failure.set(error); }
            });
            first.setDaemon(true);
            first.start();
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
            try {
                assertEquals(1, c.snapshot().physicalSubmissions());
                assertEquals(1, c.snapshot().mailbox().queuedMessages());
                assertEquals(1, c.snapshot().mailbox().outstandingMessages());
                // This second carrier cannot submit sequence 2 before the
                // still-running first transport has returned.
                assertFalse(c.dispatchNext(seal, completed()));
                assertEquals(List.of(), old.received);
            } finally {
                finishFirst.countDown();
                first.join(5000);
            }
            assertFalse(first.isAlive());
            assertNull(failure.get());
            assertTrue(c.dispatchNext(seal, completed()));
            assertEquals(List.of(1L, 2L), old.received);
            assertEquals(3, old.count);
        }
    }

    private static final class AttestedHostPhysical
            implements ActorRouteCoordinatorV1.PhysicalRouteWitness {
        private final Object domain;
        private final AtomicReference<Object> owner = new AtomicReference<>();
        volatile boolean ready;
        volatile boolean retired;
        volatile boolean preclaimDrained = true;

        AttestedHostPhysical(Object domain, boolean ready) {
            this.domain = domain;
            this.ready = ready;
        }
        @Override public boolean readyForPublication() {
            return ready && !retired && preclaimDrained;
        }
        @Override public boolean preclaimDirectMailDrained() {
            return preclaimDrained;
        }
        @Override public boolean sameAuthorityDomain(
                ActorRouteCoordinatorV1.PhysicalRouteWitness other) {
            return other instanceof AttestedHostPhysical test && domain == test.domain;
        }
        @Override public void bindToCoordinator(Object coordinator) {
            if (!owner.compareAndSet(null, coordinator))
                throw new SecurityException("physical endpoint already bound");
        }
        @Override public void retireFromLogicalRoute() { retired = true; }
    }

    @Test void candidateMustBeReadyAndOwnedByExactlyOneLogicalRoute() {
        Object seal = new Object();
        Object runtimeDomain = new Object();
        var original = new AttestedHostPhysical(runtimeDomain, true);
        try (var authority = new ActorRouteCoordinatorV1<>(
                85, seal, image('a'), state(0), original, 4, 32, 128)) {
            var candidate = new AttestedHostPhysical(runtimeDomain, false);
            var stage = authority.stage(seal, image('b'));
            assertThrows(IllegalStateException.class,
                    () -> stage.publish(seal, state(0), candidate));
            assertFalse(original.retired);
            candidate.ready = true;
            assertTrue(stage.publish(seal, state(0), candidate));
            assertTrue(original.retired);
            assertThrows(SecurityException.class, () -> new ActorRouteCoordinatorV1<>(
                    86, new Object(), image('a'), state(0), candidate, 4, 32, 128));
        }
    }

    @Test void foreignAuthorityDomainAndReplayedPhysicalReceiptAreRejected() {
        Object seal = new Object();
        Object firstRuntime = new Object();
        var original = new AttestedHostPhysical(firstRuntime, true);
        try (var authority = new ActorRouteCoordinatorV1<>(
                88, seal, image('a'), state(0), original, 4, 32, 128)) {
            authority.logicalRef().send(new byte[]{1});
            assertTrue(authority.dispatchNext(seal, (route, mail, receipt) -> {
                receipt.claimPhysicalAdmission(route);
                assertThrows(SecurityException.class,
                        () -> receipt.claimPhysicalAdmission(route));
                assertThrows(SecurityException.class,
                        () -> receipt.claimPhysicalAdmission(new Object()));
                receipt.acknowledge();
            }));
            var stage = authority.stage(seal, image('b'));
            var foreign = new AttestedHostPhysical(new Object(), true);
            assertThrows(SecurityException.class,
                    () -> stage.publish(seal, state(1), foreign));
            assertFalse(original.retired);
            stage.abort(seal);
            assertSame(original, authority.current().physicalRoute());
        }
    }

    @Test void preclaimDirectPhysicalMailMustDrainBeforeCodeSwitch() {
        Object seal = new Object();
        Object runtimeDomain = new Object();
        var old = new AttestedHostPhysical(runtimeDomain, true);
        old.preclaimDrained = false;
        try (var authority = new ActorRouteCoordinatorV1<>(
                89, seal, image('a'), state(10), old, 4, 32, 128)) {
            var candidate = new AttestedHostPhysical(runtimeDomain, true);
            var stage = authority.stage(seal, image('b'));
            assertFalse(stage.quiescent(seal),
                    "messages admitted directly before sealing old physical ActorRef are still active");
            assertFalse(stage.publish(seal, state(10), candidate));
            assertSame(old, authority.current().physicalRoute());
            assertFalse(old.retired);

            old.preclaimDrained = true; // Host-only simulation of real ActorRef.drain() receipt
            assertTrue(stage.quiescent(seal));
            assertTrue(stage.publish(seal, state(10), candidate));
            assertTrue(old.retired);
        }
    }

}
