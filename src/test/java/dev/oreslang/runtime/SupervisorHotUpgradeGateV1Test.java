package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Host-only gate tests; these do not certify compiler-generated guest upgrades. */
final class SupervisorHotUpgradeGateV1Test {
    private static String sha(char c) { return String.valueOf(c).repeat(64); }
    private static SupervisorHotUpgradeGateV1.Image image(char code) {
        return new SupervisorHotUpgradeGateV1.Image(sha(code), sha('1'), sha('2'), sha('3'));
    }
    private static SupervisorHotUpgradeGateV1 gate(Object authority,
            SupervisorHotUpgradeGateV1.ChildRegistry registry, UUID id, UUID parent) {
        return new SupervisorHotUpgradeGateV1(id, parent, authority, registry, image('a'));
    }

    @Test
    void rootSupervisorUpgradeKeepsChildrenAndIdentityAndWaitsForQueuedControl() {
        Object bootstrap = new Object();
        var registry = new SupervisorHotUpgradeGateV1.ChildRegistry();
        UUID id = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        try (var gate = gate(bootstrap, registry, id, null)) {
            gate.attachChild(child);
            var before = gate.snapshot();
            var queued = gate.reserveControl(child);
            var next = gate.prepare(bootstrap, 1, image('b'));
            assertFalse(gate.publish(bootstrap, next));
            assertEquals(image('a'), gate.snapshot().image());
            assertThrows(IllegalStateException.class, () -> gate.reserveControl(child));
            assertThrows(IllegalStateException.class, () -> gate.attachChild(UUID.randomUUID()));
            assertThrows(IllegalStateException.class, () -> gate.detachChild(child));
            queued.close();
            queued.close();
            assertTrue(gate.publish(bootstrap, next));
            var after = gate.snapshot();
            assertEquals(2, after.epoch());
            assertEquals(id, after.supervisorId());
            assertNull(after.parentSupervisorId());
            assertEquals(before.children(), after.children());
            assertEquals(id, registry.parentOf(child));
            assertEquals(image('b'), after.image());
            assertFalse(after.frozen());
            assertThrows(IllegalStateException.class, () -> gate.publish(bootstrap, next));
            try (var lease = gate.reserveControl(child)) {
                assertEquals(2, lease.originEpoch());
            }
        }
    }

    @Test
    void secondSupervisorCannotAttachSameChildEvenDuringHandover() {
        Object authority = new Object();
        var registry = new SupervisorHotUpgradeGateV1.ChildRegistry();
        UUID child = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (var a = gate(authority, registry, first, null);
             var b = gate(new Object(), registry, second, first)) {
            a.attachChild(child);
            assertThrows(IllegalStateException.class, () -> b.attachChild(child));
            var next = a.prepare(authority, 1, image('b'));
            assertThrows(IllegalStateException.class, () -> b.attachChild(child));
            assertTrue(a.publish(authority, next));
            assertThrows(IllegalStateException.class, () -> b.attachChild(child));
            a.detachChild(child);
            b.attachChild(child);
            assertEquals(second, registry.parentOf(child));
        }
    }

    @Test
    void wrongCoordinatorAndStaleEpochCannotInitiateOrCompleteHandover() {
        Object authority = new Object();
        Object otherAuthority = new Object();
        var registry = new SupervisorHotUpgradeGateV1.ChildRegistry();
        try (var a = gate(authority, registry, UUID.randomUUID(), null);
             var b = gate(otherAuthority, registry, UUID.randomUUID(), null)) {
            assertThrows(SecurityException.class, () -> a.prepare(new Object(), 1, image('b')));
            assertThrows(IllegalStateException.class, () -> a.prepare(authority, 2, image('b')));
            var next = a.prepare(authority, 1, image('b'));
            assertThrows(SecurityException.class, () -> a.publish(new Object(), next));
            assertThrows(IllegalStateException.class, () -> b.publish(otherAuthority, next));
            assertEquals(image('a'), a.snapshot().image());
            assertTrue(a.snapshot().frozen());
            a.abort(authority, next);
            assertThrows(IllegalStateException.class, () -> a.publish(authority, next));
            assertTrue(a.publish(authority, a.prepare(authority, 1, image('c'))));
            // ABA is not permitted to revive an old candidate after v1 -> v2.
            assertThrows(IllegalStateException.class, () -> a.abort(authority, next));
            assertThrows(IllegalStateException.class, () -> a.prepare(authority, 1, image('a')));
        }
    }

    @Test
    void policyControlAbiAndCapabilitiesCannotDriftWithoutMigration() {
        Object authority = new Object();
        try (var gate = gate(authority, new SupervisorHotUpgradeGateV1.ChildRegistry(),
                UUID.randomUUID(), null)) {
            for (int i = 0; i < 3; i++) {
                var incompatible = new SupervisorHotUpgradeGateV1.Image(sha('b'),
                        i == 0 ? sha('4') : sha('1'),
                        i == 1 ? sha('4') : sha('2'),
                        i == 2 ? sha('4') : sha('3'));
                assertThrows(SecurityException.class, () -> gate.prepare(authority, 1, incompatible));
                assertFalse(gate.snapshot().frozen());
            }
            assertThrows(IllegalArgumentException.class, () -> gate.prepare(authority, 1, image('a')));
            assertThrows(IllegalArgumentException.class, () -> new SupervisorHotUpgradeGateV1.Image(
                    "ABC", sha('1'), sha('2'), sha('3')));
            assertEquals(image('a'), gate.snapshot().image());
        }
    }

    @Test
    void abortIsPrecommitOnlyAndDoesNotDropPendingControl() {
        Object authority = new Object();
        UUID child = UUID.randomUUID();
        try (var gate = gate(authority, new SupervisorHotUpgradeGateV1.ChildRegistry(),
                UUID.randomUUID(), null)) {
            gate.attachChild(child);
            var existing = gate.reserveControl(child);
            var aborted = gate.prepare(authority, 1, image('b'));
            gate.abort(authority, aborted);
            assertEquals(image('a'), gate.snapshot().image());
            assertEquals(1, gate.snapshot().controlReservations());
            try (var second = gate.reserveControl(child)) {
                assertEquals(1, second.originEpoch());
            }
            // Existing pin ensures the next upgrade cannot publish.
            var next = gate.prepare(authority, 1, image('c'));
            assertFalse(gate.publish(authority, next));
            gate.abort(authority, next);
            existing.close();
        }
    }

    @Test
    void stoppingSupervisorDoesNotReleaseChildOwnershipOrLiveControlLeases() {
        var registry = new SupervisorHotUpgradeGateV1.ChildRegistry();
        UUID parentId = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        var gate = gate(new Object(), registry, parentId, null);
        gate.attachChild(child);
        var existing = gate.reserveControl(child);
        gate.close();
        assertTrue(gate.snapshot().closed());
        assertFalse(gate.retired());
        assertEquals(parentId, registry.parentOf(child));
        assertThrows(IllegalStateException.class, () -> gate.reserveControl(child));
        existing.close();
        // No false retirement while the closed supervisor still owns children.
        assertFalse(gate.retired());
        assertEquals(parentId, registry.parentOf(child));
        gate.close();
    }

    @Test
    void snapshotChildrenAreImmutable() {
        try (var gate = gate(new Object(), new SupervisorHotUpgradeGateV1.ChildRegistry(),
                UUID.randomUUID(), null)) {
            var first = gate.snapshot();
            UUID child = UUID.randomUUID();
            gate.attachChild(child);
            assertTrue(first.children().isEmpty());
            assertThrows(UnsupportedOperationException.class, () -> gate.snapshot().children().add(child));
        }
    }

    @Test
    void concurrentDrainCannotPublishWhileOldControlLeaseIsLive() throws Exception {
        Object authority = new Object();
        UUID child = UUID.randomUUID();
        try (var gate = gate(authority, new SupervisorHotUpgradeGateV1.ChildRegistry(),
                UUID.randomUUID(), null)) {
            gate.attachChild(child);
            var control = gate.reserveControl(child);
            var candidate = gate.prepare(authority, 1, image('b'));
            var entered = new CountDownLatch(1);
            var exit = new CountDownLatch(1);
            var failure = new AtomicReference<Throwable>();
            Thread worker = new Thread(() -> {
                entered.countDown();
                try { exit.await(); control.close(); }
                catch (Throwable t) { failure.set(t); }
            });
            worker.setDaemon(true);
            worker.start();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(gate.publish(authority, candidate));
            } finally {
                exit.countDown();
                worker.join(5000);
            }
            assertFalse(worker.isAlive());
            assertNull(failure.get());
            assertTrue(gate.publish(authority, candidate));
            assertEquals(2, gate.snapshot().epoch());
        }
    }
    @Test
    void hierarchyCycleIsRejectedWithoutCorruptingParentRegistry() {
        var registry = new SupervisorHotUpgradeGateV1.ChildRegistry();
        UUID alpha = UUID.randomUUID();
        UUID beta = UUID.randomUUID();
        UUID gamma = UUID.randomUUID();
        try (var a = gate(new Object(), registry, alpha, null);
             var b = gate(new Object(), registry, beta, alpha);
             var c = gate(new Object(), registry, gamma, beta)) {
            a.attachChild(beta);
            b.attachChild(gamma);
            assertThrows(IllegalStateException.class, () -> c.attachChild(alpha),
                    "alpha -> beta -> gamma -> alpha must fail");
            assertNull(registry.parentOf(alpha));
            assertEquals(alpha, registry.parentOf(beta));
            assertEquals(beta, registry.parentOf(gamma));
            assertFalse(c.snapshot().children().contains(alpha));
            b.detachChild(gamma);
            a.detachChild(beta);
            c.attachChild(alpha); // no cycle once both prior edges are gone
            assertEquals(gamma, registry.parentOf(alpha));
        }
    }

    @Test
    void concurrentCompetingParentClaimsHaveExactlyOneWinner() throws Exception {
        var registry = new SupervisorHotUpgradeGateV1.ChildRegistry();
        UUID child = UUID.randomUUID();
        var a = gate(new Object(), registry, UUID.randomUUID(), null);
        var b = gate(new Object(), registry, UUID.randomUUID(), null);
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(2);
        var success = new java.util.concurrent.atomic.AtomicInteger();
        var rejected = new java.util.concurrent.atomic.AtomicInteger();
        var errors = new AtomicReference<Throwable>();
        Thread first = new Thread(() -> {
            try { start.await(); a.attachChild(child); success.incrementAndGet(); }
            catch (IllegalStateException expected) { rejected.incrementAndGet(); }
            catch (Throwable failure) { errors.set(failure); }
            finally { done.countDown(); }
        });
        Thread second = new Thread(() -> {
            try { start.await(); b.attachChild(child); success.incrementAndGet(); }
            catch (IllegalStateException expected) { rejected.incrementAndGet(); }
            catch (Throwable failure) { errors.set(failure); }
            finally { done.countDown(); }
        });
        first.setDaemon(true);
        second.setDaemon(true);
        first.start();
        second.start();
        try {
            start.countDown();
            assertTrue(done.await(5, TimeUnit.SECONDS), "parent claim threads stuck");
            assertNull(errors.get());
            assertEquals(1, success.get());
            assertEquals(1, rejected.get());
            UUID owner = registry.parentOf(child);
            assertTrue(owner.equals(a.snapshot().supervisorId())
                    || owner.equals(b.snapshot().supervisorId()));
        } finally {
            start.countDown();
            first.join(5000);
            second.join(5000);
            a.close();
            b.close();
        }
    }

    @Test
    void closedUnattachedSupervisorMayRetireAfterLeaseDrain() {
        Object authority = new Object();
        var gate = gate(authority, new SupervisorHotUpgradeGateV1.ChildRegistry(),
                UUID.randomUUID(), null);
        assertFalse(gate.retired());
        gate.close();
        assertTrue(gate.retired());
    }

}
