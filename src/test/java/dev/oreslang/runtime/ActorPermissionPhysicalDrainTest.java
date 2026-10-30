package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Immutable, nonblocking physical retirement receipts for nested actor scopes. */
final class ActorPermissionPhysicalDrainTest {
    private static RuntimePermissions empty() {
        return RuntimePermissions.denyAll();
    }

    @Test
    void revocationSealsAdmissionsAndReceiptWaitsForAllDescendantLeases() {
        var root = ActorPermissionScope.root(empty());
        var parent = root.attenuate(empty());
        var child = parent.attenuate(empty());
        var work = child.acquirePhysicalLease("inflight worker");
        var loan = child.acquirePhysicalLease("registered socket loan");
        assertTrue(parent.revoke());
        assertTrue(parent.tryDrainReceipt().isEmpty());
        assertThrows(SecurityException.class,
                () -> child.acquirePhysicalLease("post-revocation worker"));
        work.close();
        assertTrue(parent.tryDrainReceipt().isEmpty(),
                "one remaining physical owner must prevent a receipt");
        loan.close();
        var receipt = parent.tryDrainReceipt().orElseThrow();
        assertTrue(receipt.scopeSerial() > 0);
        assertEquals(receipt, parent.tryDrainReceipt().orElseThrow(),
                "receipt identity remains stable after physical quiescence");
    }

    @Test
    void siblingCanContinueWhenAnotherDelegationIsRevoked() {
        var root = ActorPermissionScope.root(empty());
        var revoked = root.attenuate(empty());
        var sibling = root.attenuate(empty());
        try (var siblingLease = sibling.acquirePhysicalLease("sibling")) {
            assertTrue(revoked.revoke());
            assertTrue(revoked.tryDrainReceipt().isPresent(),
                    "sibling does not count against a separate delegated node");
            assertThrows(SecurityException.class,
                    () -> revoked.acquirePhysicalLease("revoked"));
            assertTrue(sibling.tryDrainReceipt().isEmpty(),
                    "unrevoked delegation must not issue a receipt");
        }
    }

    @Test
    void parentDrainAccountsForDescendantAndChildDrainIsIndependent() {
        var root = ActorPermissionScope.root(empty());
        var parent = root.attenuate(empty());
        var child = parent.attenuate(empty());
        var lease = child.acquirePhysicalLease("descendant");
        assertTrue(parent.revoke());
        assertTrue(parent.tryDrainReceipt().isEmpty());
        assertTrue(child.tryDrainReceipt().isEmpty());
        assertTrue(child.revoke());
        assertTrue(child.tryDrainReceipt().isEmpty());
        lease.close();
        assertTrue(parent.tryDrainReceipt().isPresent());
        assertTrue(child.tryDrainReceipt().isPresent());
        assertNotEquals(parent.tryDrainReceipt().orElseThrow(),
                child.tryDrainReceipt().orElseThrow());
    }

    @Test
    void duplicateRetirementIsRejectedWithoutCounterUnderflow() {
        var scope = ActorPermissionScope.root(empty()).attenuate(empty());
        var lease = scope.acquirePhysicalLease("once");
        assertTrue(scope.revoke());
        lease.close();
        assertThrows(IllegalStateException.class, lease::close);
        assertTrue(scope.tryDrainReceipt().isPresent());
    }

    @Test
    void lateNativeOwnershipQuarantineInvalidatesAncestorDrainProof() {
        var root = ActorPermissionScope.root(empty());
        var parent = root.attenuate(empty());
        var child = parent.attenuate(empty());
        var sibling = root.attenuate(empty());
        var held = child.acquirePhysicalLease("already admitted worker");
        child.quarantinePhysicalDrain();
        assertTrue(parent.revoke());
        assertTrue(child.revoke());
        held.close();
        assertTrue(parent.tryDrainReceipt().isEmpty(),
                "quarantined child must prevent ancestor from claiming drain");
        assertTrue(child.tryDrainReceipt().isEmpty(),
                "late native owner lacks independent physical retirement witness");
        assertTrue(sibling.revoke());
        assertTrue(sibling.tryDrainReceipt().isPresent(),
                "quarantine of one delegation must not erase sibling evidence");
    }

    @Test
    void workerStartAndRevocationRaceCannotIssuePrematureDrain() throws Exception {
        var scope = ActorPermissionScope.root(empty()).attenuate(empty());
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var error = new AtomicReference<Throwable>();
        Thread worker = Thread.ofVirtual().start(() -> {
            try (var held = scope.acquirePhysicalLease("physical worker")) {
                ready.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("worker lease release timed out");
                }
            } catch (Throwable failure) {
                error.set(failure);
            } finally {
                done.countDown();
            }
        });
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(scope.revoke());
            assertTrue(scope.tryDrainReceipt().isEmpty());
        } finally {
            release.countDown();
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertNull(error.get(), () -> String.valueOf(error.get()));
        assertTrue(scope.tryDrainReceipt().isPresent());
    }
}
