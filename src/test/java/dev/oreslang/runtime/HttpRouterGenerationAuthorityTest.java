package dev.oreslang.runtime;

import java.lang.reflect.Modifier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Trusted-host coordinator tests: NOT proof of source-guest actor integration,
 * NativeHttp listener wiring, or cross-isolate / native backend authority.
 */
final class HttpRouterGenerationAuthorityTest {
    private static final String ROUTES = "a".repeat(64);
    private static final String NEXT = "b".repeat(64);

    private static HttpRouterGenerationAuthority.Owner owner() {
        return new HttpRouterGenerationAuthority.Owner("tenant_A", 17, 91, 3);
    }

    private static HttpRouterGenerationAuthority gate(
            HttpRouterGenerationAuthority.Owner owner, int maxRequests, int maxPins, int maxOld) {
        return new HttpRouterGenerationAuthority(owner, ROUTES, maxRequests, maxPins, maxOld);
    }

    @Test void identicalTenantAndRoutesNeverGrantForeignOwnerOrRouter() {
        var alpha = owner();
        var disguised = owner(); // same textual tenant/IDs, DIFFERENT runtime issuer
        var first = gate(alpha, 2, 4, 2);
        var second = gate(disguised, 2, 4, 2);
        var firstSnapshot = first.activeSnapshot(alpha);
        var secondSnapshot = second.activeSnapshot(disguised);
        assertEquals(firstSnapshot.routeSha256(), secondSnapshot.routeSha256());
        assertNotSame(firstSnapshot, secondSnapshot);

        assertThrows(SecurityException.class, () -> first.activeSnapshot(disguised));
        assertThrows(SecurityException.class, () -> first.admit(alpha, secondSnapshot));
        assertThrows(SecurityException.class, () -> second.admit(disguised, firstSnapshot));
        var ticket = first.admit(alpha, firstSnapshot);
        assertThrows(SecurityException.class,
                () -> second.claimResponse(disguised, secondSnapshot, ticket));
        assertThrows(SecurityException.class,
                () -> first.claimResponse(disguised, firstSnapshot, ticket));
        assertThrows(SecurityException.class,
                () -> first.claimResponse(alpha, secondSnapshot, ticket));
        assertTrue(first.claimResponse(alpha, firstSnapshot, ticket));
        assertFalse(first.claimResponse(alpha, firstSnapshot, ticket));
        first.handlerQuiescent(alpha, firstSnapshot, ticket);
        first.transportQuiescent(alpha, firstSnapshot, ticket);
    }

    @Test void sameSupervisorUpgradeFencesNewAdmissionButDrainsPriorRequest() {
        var root = owner();
        var auth = gate(root, 1, 3, 2);
        var original = auth.activeSnapshot(root);
        var ticket = auth.admit(root, original);
        try (var resource = auth.pin(root, original, ticket,
                HttpGenerationDrainV1.ResourceKind.SUSPENDED_TASK)) {
            var change = auth.prepare(root, root, ROUTES); // even byte-identical image is new snapshot
            assertThrows(SecurityException.class, () -> auth.admit(root, original));
            auth.publish(change);
            assertEquals(2, auth.activeGeneration());
            var next = auth.activeSnapshot(root);
            assertNotSame(original, next);
            assertThrows(SecurityException.class, () -> auth.admit(root, original));
            assertTrue(auth.claimResponse(root, original, ticket));
            assertEquals(1, ticket.generation());
            var nextTicket = auth.admit(root, next);
            assertTrue(auth.claimResponse(root, next, nextTicket));
            assertFalse(auth.claimResponse(root, original, ticket));
            auth.handlerQuiescent(root, original, ticket);
            auth.transportQuiescent(root, original, ticket);
            assertEquals(1, auth.retainedGenerations(),
                    "physical suspended task pins old generation after response");
            auth.handlerQuiescent(root, next, nextTicket);
            auth.transportQuiescent(root, next, nextTicket);
        }
        assertEquals(0, auth.retainedGenerations());
    }

    @Test void changedSupervisorIdentityRevokesOldClaimsWithoutPretendingToKillWork() {
        var root = owner();
        var successor = owner(); // same values but different incarnation capability object
        var auth = gate(root, 2, 2, 2);
        var original = auth.activeSnapshot(root);
        var oldTicket = auth.admit(root, original);
        var ffi = auth.pin(root, original, oldTicket, HttpGenerationDrainV1.ResourceKind.FFI_CALL);
        var change = auth.prepare(root, successor, NEXT);
        auth.publish(change);

        assertThrows(SecurityException.class, () -> auth.activeSnapshot(root));
        assertTrue(auth.cancelled(root, original, oldTicket));
        assertFalse(auth.claimResponse(root, original, oldTicket));
        assertThrows(IllegalStateException.class, () ->
                auth.pin(root, original, oldTicket, HttpGenerationDrainV1.ResourceKind.HTTP_STREAM));
        var newSnapshot = auth.activeSnapshot(successor);
        assertEquals(2, newSnapshot.generation());
        assertThrows(SecurityException.class, () -> auth.admit(root, newSnapshot));
        var fresh = auth.admit(successor, newSnapshot);
        assertTrue(auth.claimResponse(successor, newSnapshot, fresh));
        auth.handlerQuiescent(root, original, oldTicket);
        auth.transportQuiescent(root, original, oldTicket);
        assertEquals(1, auth.retainedGenerations(), "FFI is still in flight");
        ffi.close();
        assertEquals(0, auth.retainedGenerations());
        auth.handlerQuiescent(successor, newSnapshot, fresh);
        auth.transportQuiescent(successor, newSnapshot, fresh);
    }

    @Test void cancellationBeforeResponseClaimIsFailClosedAndRetainsPhysicalLease() {
        var root = owner();
        var auth = gate(root, 1, 2, 2);
        var snapshot = auth.activeSnapshot(root);
        var ticket = auth.admit(root, snapshot);
        var nativeIo = auth.pin(root, snapshot, ticket, HttpGenerationDrainV1.ResourceKind.HTTP_STREAM);
        assertTrue(auth.requestCancellation(root, snapshot, ticket));
        assertFalse(auth.requestCancellation(root, snapshot, ticket));
        assertTrue(auth.cancelled(root, snapshot, ticket));
        assertFalse(auth.claimResponse(root, snapshot, ticket));
        assertThrows(IllegalStateException.class, () ->
                auth.pin(root, snapshot, ticket, HttpGenerationDrainV1.ResourceKind.CALLBACK));
        auth.handlerQuiescent(root, snapshot, ticket);
        auth.transportQuiescent(root, snapshot, ticket);
        auth.close();
        assertFalse(auth.fullyQuiescent());
        nativeIo.close();
        nativeIo.close();
        assertTrue(auth.fullyQuiescent());
    }

    @Test void boundedExchangesAndRetainedSnapshotsCannotBeBypassed() {
        var root = owner();
        var auth = gate(root, 1, 1, 1);
        var old = auth.activeSnapshot(root);
        var ticket = auth.admit(root, old);
        assertThrows(IllegalStateException.class, () -> auth.admit(root, old));
        var pin = auth.pin(root, old, ticket, HttpGenerationDrainV1.ResourceKind.CALLBACK);
        assertThrows(IllegalStateException.class, () ->
                auth.pin(root, old, ticket, HttpGenerationDrainV1.ResourceKind.FFI_CALL));
        auth.publish(auth.prepare(root, root, NEXT));
        assertThrows(IllegalStateException.class, () -> auth.prepare(root, root, ROUTES));
        auth.handlerQuiescent(root, old, ticket);
        auth.transportQuiescent(root, old, ticket);
        assertEquals(1, auth.retainedGenerations());
        pin.close();
        assertEquals(0, auth.retainedGenerations());
        var next = auth.prepare(root, root, ROUTES);
        auth.abort(next);
        assertEquals(2, auth.activeGeneration());
        assertThrows(SecurityException.class, () -> auth.publish(next));
    }

    @Test void foreignAndAbortedCandidatesCannotPublish() {
        var root = owner();
        var first = gate(root, 1, 2, 2);
        var second = gate(root, 1, 2, 2);
        var stage = first.prepare(root, root, NEXT);
        assertThrows(SecurityException.class, () -> second.publish(stage));
        assertThrows(IllegalStateException.class, () -> first.prepare(root, root, NEXT));
        first.abort(stage);
        assertThrows(SecurityException.class, () -> first.publish(stage));
        assertEquals(1, first.activeGeneration());
        assertEquals(0, first.retainedGenerations());
    }

    @Test void closeSealsAllGenerationsWithoutReleasingLivePins() {
        var root = owner();
        var auth = gate(root, 1, 2, 3);
        var old = auth.activeSnapshot(root);
        var oldRequest = auth.admit(root, old);
        auth.publish(auth.prepare(root, root, NEXT));
        var active = auth.activeSnapshot(root);
        var pending = auth.admit(root, active);
        auth.close();
        auth.close();
        assertTrue(auth.cancelled(root, old, oldRequest));
        assertTrue(auth.cancelled(root, active, pending));
        assertThrows(SecurityException.class, () -> auth.admit(root, active));
        assertFalse(auth.claimResponse(root, old, oldRequest));
        assertFalse(auth.claimResponse(root, active, pending));
        assertFalse(auth.fullyQuiescent());
        auth.handlerQuiescent(root, old, oldRequest);
        auth.transportQuiescent(root, old, oldRequest);
        assertFalse(auth.fullyQuiescent(), "new generation still has live exchange");
        auth.transportQuiescent(root, active, pending);
        auth.handlerQuiescent(root, active, pending);
        assertTrue(auth.fullyQuiescent());
    }

    @Test void parallelAdmitAndUpgradeAreLinearized() throws Exception {
        var root = owner();
        var auth = gate(root, 2, 2, 2);
        var original = auth.activeSnapshot(root);
        var start = new CountDownLatch(1);
        var seen = new AtomicReference<HttpRouterGenerationAuthority.Ticket>();
        var failure = new AtomicReference<Throwable>();
        Thread worker = new Thread(() -> {
            try {
                if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                seen.set(auth.admit(root, original));
            } catch (SecurityException expected) {
                // A prepared replacement froze admission first.
            } catch (Throwable t) { failure.set(t); }
        });
        worker.setDaemon(true);
        worker.start();
        start.countDown();
        var stage = auth.prepare(root, root, NEXT);
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertNull(failure.get());
        auth.publish(stage);
        assertThrows(SecurityException.class, () -> auth.admit(root, original));
        if (seen.get() != null) {
            assertTrue(auth.claimResponse(root, original, seen.get()));
            auth.handlerQuiescent(root, original, seen.get());
            auth.transportQuiescent(root, original, seen.get());
        }
        assertEquals(0, auth.retainedGenerations());
    }

    @Test void invalidOwnerScopeAndDigestFailClosed() {
        assertThrows(IllegalArgumentException.class, () ->
                new HttpRouterGenerationAuthority.Owner("../tenant", 1, 2, 3));
        assertThrows(IllegalArgumentException.class, () ->
                new HttpRouterGenerationAuthority.Owner("tenant", 0, 2, 3));
        var root = owner();
        assertThrows(IllegalArgumentException.class, () -> gate(root, 0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> gate(root, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> gate(root, 1, 1, 0));
        assertThrows(IllegalArgumentException.class, () ->
                new HttpRouterGenerationAuthority(root, "bad", 1, 1, 1));
        var auth = gate(root, 1, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> auth.prepare(root, root, "bad"));
    }

    @Test void authorityTypesAreNotGuestFacingSerializableData() {
        assertFalse(Modifier.isPublic(HttpRouterGenerationAuthority.class.getModifiers()));
        assertFalse(Modifier.isPublic(HttpRouterGenerationAuthority.Owner.class.getModifiers()));
        assertFalse(Modifier.isPublic(HttpRouterGenerationAuthority.Snapshot.class.getModifiers()));
        assertFalse(Modifier.isPublic(HttpRouterGenerationAuthority.Ticket.class.getModifiers()));
        assertFalse(java.io.Serializable.class.isAssignableFrom(
                HttpRouterGenerationAuthority.Ticket.class));
        assertFalse(java.io.Serializable.class.isAssignableFrom(
                HttpRouterGenerationAuthority.Snapshot.class));
        assertEquals(0, HttpRouterGenerationAuthority.Ticket.class.getConstructors().length);
    }
}
