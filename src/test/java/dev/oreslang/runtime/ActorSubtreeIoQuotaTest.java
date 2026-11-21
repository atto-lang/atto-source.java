package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests real runtime scope reservations and actor-origin AsyncNativeIo admission.
 * Does not prove a hostile native-code sandbox or cross-backend guest lowering.
 */
@Timeout(30)
final class ActorSubtreeIoQuotaTest {
    @Test
    void siblingSubtreesShareParentBudgetButNotUnrelatedRoot() {
        var root = ActorPermissionScope.root(RuntimePermissions.denyAll());
        var parent = root.attenuate(RuntimePermissions.denyAll(), 2);
        var a = parent.attenuate(RuntimePermissions.denyAll(), 1);
        var b = parent.attenuate(RuntimePermissions.denyAll());
        var unrelated = ActorPermissionScope.root(RuntimePermissions.denyAll());
        var a1 = a.reserveIo("a");
        assertThrows(RejectedExecutionException.class, () -> a.reserveIo("a twice"));
        var b1 = b.reserveIo("b");
        assertEquals(2, parent.subtreeIoInFlight());
        assertThrows(RejectedExecutionException.class, () -> b.reserveIo("parent full"));
        var independent = unrelated.reserveIo("unrelated");
        a1.close();
        assertEquals(1, parent.subtreeIoInFlight());
        assertThrows(IllegalStateException.class, a1::close);
        var b2 = b.reserveIo("freed slot");
        b2.close();
        b1.close();
        independent.close();
        assertEquals(0, root.subtreeIoInFlight());
        assertEquals(0, parent.subtreeIoInFlight());
        assertEquals(0, unrelated.subtreeIoInFlight());
    }

    @Test
    void revocationDeniesNewWorkWithoutReleasingPhysicalQuota() {
        var root = ActorPermissionScope.root(RuntimePermissions.denyAll());
        var child = root.attenuate(RuntimePermissions.denyAll(), 1);
        var sibling = root.attenuate(RuntimePermissions.denyAll(), 1);
        var ticket = child.reserveIo("native work");
        assertTrue(child.revoke());
        assertFalse(child.revoke());
        assertThrows(SecurityException.class, () -> child.reserveIo("revoked"));
        assertEquals(1, root.subtreeIoInFlight());
        try (var independent = sibling.reserveIo("sibling")) {
            assertEquals(2, root.subtreeIoInFlight());
        }
        assertEquals(1, root.subtreeIoInFlight());
        ticket.close();
        assertEquals(0, root.subtreeIoInFlight());
        assertThrows(SecurityException.class, () -> child.reserveIo("stale"));
    }

    @Test
    void invalidWideningAndPostRevocationDelegationFailClosed() {
        var root = ActorPermissionScope.root(RuntimePermissions.denyAll());
        var narrow = root.attenuate(RuntimePermissions.denyAll(), 1);
        assertThrows(IllegalArgumentException.class, () ->
                narrow.attenuate(RuntimePermissions.denyAll(), 2));
        assertThrows(IllegalArgumentException.class, () ->
                narrow.attenuate(RuntimePermissions.denyAll(), 0));
        assertThrows(IllegalArgumentException.class, () ->
                root.attenuate(RuntimePermissions.denyAll(), 65537));
        narrow.revoke();
        assertThrows(SecurityException.class, () ->
                narrow.attenuate(RuntimePermissions.denyAll(), 1));
    }

    @Test
    void concurrentReservationIsAtomicAcrossTwoWorkers() throws Exception {
        var root = ActorPermissionScope.root(RuntimePermissions.denyAll());
        var narrow = root.attenuate(RuntimePermissions.denyAll(), 1);
        var start = new CountDownLatch(1);
        var admitted = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var success = new AtomicInteger();
        var denied = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        Runnable contender = () -> {
            try {
                if (!start.await(5, TimeUnit.SECONDS))
                    throw new AssertionError("start timeout");
                try (var token = narrow.reserveIo("concurrent")) {
                    success.incrementAndGet();
                    admitted.countDown();
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new AssertionError("release timeout");
                }
            } catch (RejectedExecutionException expected) {
                denied.incrementAndGet();
            } catch (Throwable unexpected) {
                failure.compareAndSet(null, unexpected);
            }
        };
        Thread first = Thread.ofVirtual().start(contender);
        Thread second = Thread.ofVirtual().start(contender);
        try {
            start.countDown();
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            // Both contenders enter at once; the winner holds the reservation.
            // Join only after allowing the loser time to try admission.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (denied.get() == 0 && System.nanoTime() < deadline) Thread.onSpinWait();
            assertEquals(1, denied.get());
            assertEquals(1, narrow.subtreeIoInFlight());
        } finally {
            release.countDown();
            first.join(3_000);
            second.join(3_000);
        }
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        assertEquals(1, success.get());
        assertEquals(0, root.subtreeIoInFlight());
    }

    private static final AtomicReference<Probe> ACTIVE = new AtomicReference<>();

    private static final class Probe {
        final AsyncNativeIo io = new AsyncNativeIo(2);
        final CountDownLatch submitted = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch physicallyRetired = new CountDownLatch(1);
        final CountDownLatch retried = new CountDownLatch(1);
        final AtomicReference<OresFuture<Integer>> first = new AtomicReference<>();
        final AtomicReference<OresFuture<Integer>> rejected = new AtomicReference<>();
        final AtomicReference<OresFuture<Integer>> afterRetirement = new AtomicReference<>();
        final AtomicReference<ActorRuntime.ActorHandle<String>> child = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicInteger rejectedCallableRuns = new AtomicInteger();
    }

    private static ActorRuntime.Behavior<String> delegatedChild(
            ActorRuntime.ActorContext<String> ignored) {
        return (message, turn) -> {
            Probe probe = ACTIVE.get();
            try {
                if ("start".equals(message)) {
                    probe.first.set(probe.io.submit("first", () -> {
                        probe.started.countDown();
                        if (!probe.release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("worker not released");
                        return 7;
                    }));
                    probe.rejected.set(probe.io.submit("second", () -> {
                        probe.rejectedCallableRuns.incrementAndGet();
                        return 8;
                    }));
                    probe.submitted.countDown();
                } else if ("retry".equals(message)) {
                    probe.afterRetirement.set(probe.io.submit("third", () -> 9));
                    probe.retried.countDown();
                }
            } catch (Throwable unexpected) {
                probe.failure.compareAndSet(null, unexpected);
                probe.submitted.countDown();
                probe.retried.countDown();
            }
        };
    }

    @Test
    void realActorIoadmissionHonorsDelegatedOneSlotBudgetUntilWorkerExit()
            throws Exception {
        var ceiling = RuntimePermissions.denyAll();
        var policy = IsolatePolicy.developer();
        Probe probe = new Probe();
        assertTrue(ACTIVE.compareAndSet(null, probe));
        probe.io.setActorLoanRetiredListener(id -> probe.physicallyRetired.countDown());
        try {
            try (ActorRuntime runtime = new ActorRuntime(
                    policy, ActorRuntime.DispatcherConfig.defaults(),
                    ActorRuntime.TurnExecutor.direct(),
                    ActorRuntime.ControlDispatcher.unavailable(), null, ceiling)) {
                var parent = runtime.<String>spawnSharedTrusted(policy,
                        factory -> (message, actor) -> {
                            try {
                                var delegation = actor.runtime()
                                        .delegatePermissions(ceiling, 1);
                                probe.child.set(actor.runtime().<String>spawnChild(
                                        ActorRuntime.ActorKind.SHARED, policy, delegation,
                                        ActorSubtreeIoQuotaTest::delegatedChild));
                                probe.child.get().send("start");
                            } catch (Throwable unexpected) {
                                probe.failure.compareAndSet(null, unexpected);
                                probe.submitted.countDown();
                            }
                        });
                try {
                    parent.send("spawn");
                    assertTrue(probe.submitted.await(5, TimeUnit.SECONDS));
                    assertTrue(probe.started.await(5, TimeUnit.SECONDS));
                    assertNull(probe.failure.get(), () -> String.valueOf(probe.failure.get()));
                    assertNotNull(probe.rejected.get());
                    var denied = assertThrows(Exception.class, () ->
                            probe.rejected.get().get(5, TimeUnit.SECONDS));
                    assertTrue(denied.toString().contains("quota")
                            || (denied.getCause() != null
                                    && denied.getCause().toString().contains("quota")));
                    assertEquals(0, probe.rejectedCallableRuns.get());
                    probe.release.countDown();
                    assertEquals(7, probe.first.get().get(5, TimeUnit.SECONDS));
                    assertTrue(probe.physicallyRetired.await(5, TimeUnit.SECONDS));
                    probe.child.get().send("retry");
                    assertTrue(probe.retried.await(5, TimeUnit.SECONDS));
                    assertNull(probe.failure.get(), () -> String.valueOf(probe.failure.get()));
                    assertEquals(9, probe.afterRetirement.get().get(5, TimeUnit.SECONDS));
                } finally {
                    probe.release.countDown();
                    parent.stop();
                }
            }
        } finally {
            probe.release.countDown();
            ACTIVE.compareAndSet(probe, null);
            probe.io.close();
        }
    }
}
