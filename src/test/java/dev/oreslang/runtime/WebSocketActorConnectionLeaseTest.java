package dev.oreslang.runtime;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.*;

/** Host ledger contract only. No guest compiler or actor mailbox execution. */
@Timeout(12)
final class WebSocketActorConnectionLeaseTest {
    private static ActorRuntime.ActorAddress actor(UUID runtime, long generation) {
        return new ActorRuntime.ActorAddress(runtime, ActorRuntime.ActorId.create(),
                generation, ActorRuntime.ActorKind.PRIVATE, "local-process");
    }

    @Test
    void deniesForeignTenantActorRuntimeAndGenerationAndForeignHandle() {
        var tenant = UUID.randomUUID();
        var a = actor(UUID.randomUUID(), 11);
        var b = actor(UUID.randomUUID(), 11);
        var otherGeneration = new ActorRuntime.ActorAddress(
                a.runtimeId(), a.actorId(), a.generation() + 1,
                a.kind(), a.transport());
        var ledger = WebSocketActorConnectionLease.bindHost(tenant, a, () -> true);
        var handle = ledger.issue();
        assertThrows(SecurityException.class, () ->
                ledger.acquire(handle, UUID.randomUUID(), a));
        assertThrows(SecurityException.class, () ->
                ledger.acquire(handle, tenant, b));
        assertThrows(SecurityException.class, () ->
                ledger.acquire(handle, tenant, otherGeneration));
        var other = WebSocketActorConnectionLease.bindHost(tenant, a, () -> true);
        assertThrows(SecurityException.class, () ->
                other.acquire(handle, tenant, a));
        assertEquals(0, ledger.outstandingLoans());
        try (var loan = ledger.acquire(handle, tenant, a)) {
            assertEquals(1, ledger.outstandingLoans());
            assertTrue(loan.mayDeliver(handle, tenant, a));
        }
        assertEquals(0, ledger.outstandingLoans());
    }

    @Test
    void oldHandleCannotAdmitOrDeliverAfterAtomicTransfer() {
        var tenant = UUID.randomUUID();
        var a = actor(UUID.randomUUID(), 1);
        var b = actor(a.runtimeId(), 2);
        var ledger = WebSocketActorConnectionLease.bindHost(tenant, a, () -> true);
        var first = ledger.issue();
        var second = ledger.transferHost(first, tenant, a, b, () -> true);
        assertNotSame(first, second);
        assertThrows(SecurityException.class, () -> ledger.acquire(first, tenant, a));
        assertThrows(SecurityException.class, () -> ledger.acquire(first, tenant, b));
        assertThrows(SecurityException.class, () -> ledger.acquire(second, tenant, a));
        try (var loan = ledger.acquire(second, tenant, b)) {
            assertFalse(loan.mayDeliver(first, tenant, a));
            assertTrue(loan.mayDeliver(second, tenant, b));
        }
    }

    @Test
    void cannotTransferWithPhysicalLeaseAndCanAfterExactRelease() {
        var tenant = UUID.randomUUID();
        var a = actor(UUID.randomUUID(), 1);
        var b = actor(a.runtimeId(), 2);
        var ledger = WebSocketActorConnectionLease.bindHost(tenant, a, () -> true);
        var first = ledger.issue();
        var loan = ledger.acquire(first, tenant, a);
        assertThrows(RejectedExecutionException.class,
                () -> ledger.transferHost(first, tenant, a, b, () -> true));
        loan.close();
        loan.close();
        assertEquals(0, ledger.outstandingLoans());
        var second = ledger.transferHost(first, tenant, a, b, () -> true);
        try (var next = ledger.acquire(second, tenant, b)) {
            assertEquals(1, ledger.outstandingLoans());
        }
        assertEquals(0, ledger.outstandingLoans());
    }

    @Test
    void retireFencesPublicationButPreservesActivePhysicalReceipts() {
        var tenant = UUID.randomUUID();
        var a = actor(UUID.randomUUID(), 1);
        var ledger = WebSocketActorConnectionLease.bindHost(tenant, a, () -> true);
        var handle = ledger.issue();
        var writes = new AtomicInteger();
        var loan = ledger.acquire(handle, tenant, a);
        assertTrue(loan.enqueueIfCurrent(writes::incrementAndGet));
        assertEquals(1, writes.get());
        assertTrue(ledger.revoke());
        assertFalse(ledger.revoke());
        assertTrue(ledger.isLogicallyRetired());
        assertEquals(1, ledger.outstandingLoans(),
                "logical revoke must not forge physical operation retirement");
        assertFalse(loan.mayDeliver(handle, tenant, a));
        assertFalse(loan.enqueueIfCurrent(writes::incrementAndGet));
        assertEquals(1, writes.get());
        assertThrows(SecurityException.class, () -> ledger.acquire(handle, tenant, a));
        loan.close();
        assertEquals(0, ledger.outstandingLoans());
        assertFalse(ledger.hasOutstandingLoans());
    }

    @Test
    void stoppedActorRejectedAndCannotBecomeNextOwner() {
        var tenant = UUID.randomUUID();
        var a = actor(UUID.randomUUID(), 1);
        var b = actor(a.runtimeId(), 2);
        var alive = new AtomicBoolean(true);
        var ledger = WebSocketActorConnectionLease.bindHost(tenant, a, alive::get);
        var current = ledger.issue();
        alive.set(false);
        assertThrows(RejectedExecutionException.class, () -> ledger.acquire(current, tenant, a));
        assertThrows(RejectedExecutionException.class, () ->
                ledger.transferHost(current, tenant, a, b, () -> true));

        var fresh = WebSocketActorConnectionLease.bindHost(tenant, b, () -> true);
        var issued = fresh.issue();
        assertThrows(RejectedExecutionException.class, () ->
                fresh.transferHost(issued, tenant, b, a, () -> false));
        assertThrows(RejectedExecutionException.class, () ->
                WebSocketActorConnectionLease.bindHost(tenant, a, () -> false));
    }

    @Test
    void queuedDeliveryIsAtomicAgainstRevokeAndNeverRunsAfterIt() throws Exception {
        for (int round = 0; round < 32; round++) {
            var tenant = UUID.randomUUID();
            var a = actor(UUID.randomUUID(), 1);
            var ledger = WebSocketActorConnectionLease.bindHost(tenant, a, () -> true);
            var handle = ledger.issue();
            var loan = ledger.acquire(handle, tenant, a);
            var start = new CountDownLatch(1);
            var results = new AtomicInteger();
            var queued = new AtomicBoolean();
            Thread sender = Thread.ofVirtual().start(() -> {
                try {
                    if (!start.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("enqueue start timed out");
                    }
                    // Callback is a bounded, nonblocking queue publication;
                    // it must never execute guest work or wait under ledger
                    // lock. A simultaneous revoke can win before this call.
                    queued.set(loan.enqueueIfCurrent(results::incrementAndGet));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            });
            start.countDown();
            assertTrue(ledger.revoke());
            sender.join(2000);
            assertFalse(sender.isAlive(), "enqueue race worker did not retire");
            assertEquals(queued.get() ? 1 : 0, results.get());
            assertFalse(loan.mayDeliver(handle, tenant, a));
            assertFalse(loan.enqueueIfCurrent(results::incrementAndGet));
            loan.close();
            assertEquals(0, ledger.outstandingLoans());
        }
    }

    @Test
    void transferRacingLeaseAcquisitionNeverPublishesBothOwners() throws Exception {
        for (int round = 0; round < 48; round++) {
            var tenant = UUID.randomUUID();
            var a = actor(UUID.randomUUID(), 1);
            var b = actor(a.runtimeId(), 2);
            var ledger = WebSocketActorConnectionLease.bindHost(tenant, a, () -> true);
            var first = ledger.issue();
            var start = new CountDownLatch(1);
            var permitted = new AtomicBoolean();
            var unexpected = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            Thread acquisition = Thread.ofVirtual().start(() -> {
                try {
                    if (!start.await(2, TimeUnit.SECONDS)) throw new AssertionError("no start");
                    try (var loan = ledger.acquire(first, tenant, a)) {
                        permitted.set(true);
                    } catch (SecurityException rejectedAfterTransfer) {
                        // Transfer won before admission.
                    }
                } catch (Throwable failure) { unexpected.set(failure); }
            });
            start.countDown();
            WebSocketActorConnectionLease.Handle second;
            try {
                second = ledger.transferHost(first, tenant, a, b, () -> true);
            } catch (RejectedExecutionException busy) {
                // An active physical lease won. The old owner remains valid.
                second = null;
            }
            acquisition.join(3000);
            assertFalse(acquisition.isAlive());
            assertNull(unexpected.get(), () -> String.valueOf(unexpected.get()));
            assertEquals(0, ledger.outstandingLoans());
            if (second != null) {
                assertFalse(permitted.get(), "old handle admitted after successful transfer");
                assertThrows(SecurityException.class, () -> ledger.acquire(first, tenant, a));
                try (var loan = ledger.acquire(second, tenant, b)) {
                    assertTrue(loan.mayDeliver(second, tenant, b));
                }
            } else {
                assertTrue(permitted.get(), "busy must correspond to an active lease");
                try (var loan = ledger.acquire(first, tenant, a)) {
                    assertTrue(loan.mayDeliver(first, tenant, a));
                }
            }
        }
    }
}
