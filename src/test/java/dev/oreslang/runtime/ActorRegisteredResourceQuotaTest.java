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
 * Candidate native resource-accounting regressions. These are host-runtime
 * tests, NOT evidence that Oreslang guests or an OS sandbox enforce a budget.
 */
@Timeout(30)
final class ActorRegisteredResourceQuotaTest {
    private static final long MIB = 1024L * 1024;

    private static final class Resource implements AutoCloseable {
        final AtomicInteger closed = new AtomicInteger();
        @Override public void close() {
            if (closed.incrementAndGet() != 1)
                throw new IllegalStateException("duplicate native handle close");
        }
    }

    @Test
    void aggregateDescendantHandlesAndBytesCannotExceedParentBudget() {
        var root = ActorPermissionScope.root(RuntimePermissions.denyAll());
        var parent = root.attenuate(RuntimePermissions.denyAll(), 4, 2, 64);
        var a = parent.attenuate(RuntimePermissions.denyAll(), 2, 1, 48);
        var b = parent.attenuate(RuntimePermissions.denyAll(), 2, 2, 64);
        var unrelated = ActorPermissionScope.root(RuntimePermissions.denyAll());
        var x = a.reserveResource(40, "a");
        assertEquals(1, a.registeredResourceCount());
        assertEquals(40L, parent.registeredResourceBytes());
        assertThrows(RejectedExecutionException.class, () ->
                a.reserveResource(1, "child handle limit"));
        assertThrows(RejectedExecutionException.class, () ->
                b.reserveResource(25, "parent bytes exceeded"));
        var y = b.reserveResource(24, "b");
        assertEquals(2, parent.registeredResourceCount());
        assertEquals(64L, parent.registeredResourceBytes());
        assertThrows(RejectedExecutionException.class, () ->
                b.reserveResource(0, "parent handles exceeded"));
        var independent = unrelated.reserveResource(1, "unrelated actor");
        x.close();
        assertEquals(1, parent.registeredResourceCount());
        assertThrows(IllegalStateException.class, x::close);
        var replaced = b.reserveResource(1, "slot returned");
        replaced.close();
        y.close();
        independent.close();
        assertEquals(0, root.registeredResourceCount());
        assertEquals(0L, root.registeredResourceBytes());
    }

    @Test
    void revocationAndFailedReservationsNeverRefundPhysicalResourceCredits() {
        var root = ActorPermissionScope.root(RuntimePermissions.denyAll());
        var child = root.attenuate(RuntimePermissions.denyAll(), 2, 1, 10);
        var sibling = root.attenuate(RuntimePermissions.denyAll(), 2, 1, 10);
        var physical = child.reserveResource(10, "in flight");
        assertThrows(RejectedExecutionException.class, () ->
                child.reserveResource(0, "handle limit"));
        assertThrows(RejectedExecutionException.class, () ->
                sibling.reserveResource(11, "sibling bytes limit"));
        assertTrue(child.revoke());
        assertThrows(SecurityException.class, () ->
                child.reserveResource(1, "revoked"));
        assertEquals(1, root.registeredResourceCount());
        var independent = sibling.reserveResource(3, "live sibling");
        independent.close();
        assertEquals(1, root.registeredResourceCount());
        physical.close();
        assertEquals(0L, root.registeredResourceBytes());
        assertThrows(SecurityException.class, () ->
                child.reserveResource(0, "cannot resurrect"));
    }

    @Test
    void resourceDelegationRejectsWideningAndInvalidSizes() {
        var root = ActorPermissionScope.root(RuntimePermissions.denyAll());
        var parent = root.attenuate(RuntimePermissions.denyAll(), 2, 1, 16);
        assertThrows(IllegalArgumentException.class, () ->
                parent.attenuate(RuntimePermissions.denyAll(), 2, 2, 16));
        assertThrows(IllegalArgumentException.class, () ->
                parent.attenuate(RuntimePermissions.denyAll(), 2, 1, 17));
        assertThrows(IllegalArgumentException.class, () ->
                parent.attenuate(RuntimePermissions.denyAll(), 2, 0, 16));
        assertThrows(IllegalArgumentException.class, () ->
                root.reserveResource(-1, "negative"));
        var zeroByte = parent.reserveResource(0, "descriptor without known byte size");
        assertEquals(1, parent.registeredResourceCount());
        assertEquals(0L, parent.registeredResourceBytes());
        zeroByte.close();
        assertEquals(0, parent.registeredResourceCount());
    }

    @Test
    void contextRegistrationCountsAndUnknownByteAttributionAreBounded()
            throws Exception {
        try (var io = new AsyncNativeIo(2, 1, 2, 1)) {
            var first = new Resource();
            var second = new Resource();
            io.register(first, 64);
            assertEquals(1, io.registeredResourceCount());
            assertEquals(64L, io.registeredAccountedBytes());
            assertThrows(RejectedExecutionException.class, () ->
                    io.register(second, 1));
            assertEquals(1, io.registeredResourceCount());
            assertEquals(0, second.closed.get(),
                    "rejected registration leaves handle ownership with caller");
            second.close();
            io.unregister(first);
            first.close(); // explicit host ownership transfer
            assertEquals(0, io.registeredResourceCount());
            assertEquals(0L, io.registeredAccountedBytes());
        }
    }

    @Test
    void unregisterDefersQuotaRefundUntilLastPhysicalLoanRetires()
            throws Exception {
        try (var io = new AsyncNativeIo(2, 1, 2, 1)) {
            var resource = new Resource();
            io.register(resource, 37);
            var loan = io.borrow(resource);
            io.unregister(resource);
            assertEquals(1, io.registeredResourceCount());
            assertEquals(37L, io.registeredAccountedBytes());
            var extra = new Resource();
            assertThrows(RejectedExecutionException.class, () -> io.register(extra, 0));
            extra.close();
            loan.close();
            assertEquals(0, io.registeredResourceCount());
            assertEquals(0L, io.registeredAccountedBytes());
            resource.close(); // caller now owns the unregistered resource
            assertThrows(IllegalStateException.class, loan::close);
        }
    }

    @Test
    void contextResourceBytesRequireTrustedNonzeroAttribution() throws Exception {
        try (var io = new AsyncNativeIo(3, 1, 3, 3)) {
            var first = new Resource();
            var second = new Resource();
            io.register(first, 64L * MIB);
            assertThrows(RejectedExecutionException.class, () -> io.register(second, 1));
            assertEquals(64L * MIB, io.registeredAccountedBytes());
            io.unregister(first);
            first.close();
            io.register(second, 1);
            assertEquals(1L, io.registeredAccountedBytes());
            io.unregister(second);
            second.close();
        }
    }

    @Test
    void physicalCloseRetainsRegistrationOnFailureAndReleasesOnlyAfterRetry()
            throws Exception {
        var io = new AsyncNativeIo(2, 1, 2, 1);
        var attempts = new AtomicInteger();
        AutoCloseable occasionallyFails = () -> {
            if (attempts.incrementAndGet() == 1)
                throw new IllegalStateException("temporary close failure");
        };
        try {
            io.register(occasionallyFails, 11);
            assertThrows(IllegalStateException.class, io::close);
            assertEquals(1, io.registeredResourceCount(),
                    "failed native close must remain owned");
            assertEquals(11L, io.registeredAccountedBytes());
            io.close();
            assertEquals(0, io.registeredResourceCount());
            assertEquals(0L, io.registeredAccountedBytes());
            assertEquals(2, attempts.get());
        } finally {
            try { io.close(); } catch (IllegalStateException ignored) { }
        }
    }

    @Test
    void contextShutdownRetainsNativeResourceWhenCloseThrowsErrorThenRetries()
            throws Exception {
        var io = new AsyncNativeIo(2, 1, 2, 1);
        var attempts = new AtomicInteger();
        AutoCloseable physical = () -> {
            if (attempts.incrementAndGet() == 1)
                throw new AssertionError("fatal native close callback");
        };
        io.register(physical, 9);
        try {
            assertThrows(IllegalStateException.class, io::close,
                    "Error must be aggregated instead of bypassing quarantine");
            assertEquals(1, io.registeredResourceCount());
            assertEquals(9L, io.registeredAccountedBytes());
            io.close();
            assertEquals(0, io.registeredResourceCount());
            assertEquals(0L, io.registeredAccountedBytes());
            assertEquals(2, attempts.get());
        } finally {
            try { io.close(); } catch (IllegalStateException ignored) { }
        }
    }

    @Test
    void actorGenericCloseFailureRetainsQuotaAndDeniesFutureLoansUntilTrustedRetry()
            throws Exception {
        var policy = IsolatePolicy.developer();
        var ceiling = RuntimePermissions.denyAll();
        var io = new AsyncNativeIo(2, 1, 2, 1);
        var attempts = new AtomicInteger();
        var checked = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        AutoCloseable physical = () -> {
            if (attempts.incrementAndGet() == 1)
                throw new IllegalStateException("native close failed before retirement");
        };
        var runtime = new ActorRuntime(policy,
                ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable(), null, ceiling);
        runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
        io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
        try {
            var actor = runtime.<String>spawnSharedTrusted(policy,
                    ignored -> (message, turn) -> {
                        try {
                            io.register(physical, 19);
                            assertThrows(SecurityException.class,
                                    () -> io.unregister(physical),
                                    "unproven close may not refund actor budget");
                            assertThrows(IllegalStateException.class,
                                    () -> io.closeAndUnregister(physical));
                            assertEquals(1, io.registeredResourceCount());
                            assertEquals(19L, io.registeredAccountedBytes());
                            assertThrows(RejectedExecutionException.class,
                                    () -> io.borrow(physical),
                                    "failed physical close must fence new loans");
                            io.closeAndUnregister(physical);
                            assertEquals(0, io.registeredResourceCount());
                            assertEquals(0L, io.registeredAccountedBytes());
                        } catch (Throwable problem) {
                            failure.compareAndSet(null, problem);
                        } finally {
                            checked.countDown();
                        }
                    });
            actor.send("close/retry");
            assertTrue(checked.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(2, attempts.get(), "retry must obtain actual close callback");
            OresContext.joinActorsAndNativeIo(runtime, io);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            try { OresContext.joinActorsAndNativeIo(runtime, io); }
            catch (IllegalStateException ignored) { }
        }
    }

    @Test
    void nativeContextShutdownMustWaitForActiveTrustedCloseCallback()
            throws Exception {
        var policy = IsolatePolicy.developer();
        var ceiling = RuntimePermissions.denyAll();
        var io = new AsyncNativeIo(2, 1, 2, 1);
        var physicalCloseStarted = new CountDownLatch(1);
        var releaseClose = new CountDownLatch(1);
        var actorCompleted = new CountDownLatch(1);
        var closeCount = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();
        var contextCloseFailure = new AtomicReference<Throwable>();
        AutoCloseable physical = () -> {
            physicalCloseStarted.countDown();
            if (!releaseClose.await(5, TimeUnit.SECONDS))
                throw new IllegalStateException("trusted close gate timed out");
            closeCount.incrementAndGet();
        };
        var runtime = new ActorRuntime(policy,
                ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable(), null, ceiling);
        runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
        io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
        try {
            var actor = runtime.<String>spawnSharedTrusted(policy,
                    ignored -> (message, turn) -> {
                        try {
                            io.register(physical, 7);
                            io.closeAndUnregister(physical);
                        } catch (Throwable e) {
                            failure.compareAndSet(null, e);
                        } finally {
                            actorCompleted.countDown();
                        }
                    });
            actor.send("close slowly");
            assertTrue(physicalCloseStarted.await(5, TimeUnit.SECONDS));
            var joiner = Thread.ofVirtual().start(() -> {
                try {
                    io.close();
                } catch (Throwable e) {
                    contextCloseFailure.compareAndSet(null, e);
                }
            });
            try {
                Thread.sleep(50);
                assertTrue(joiner.isAlive(),
                        "context may not finish while physical close callback is active");
                assertEquals(0, closeCount.get());
                assertEquals(1, io.registeredResourceCount());
            } finally {
                releaseClose.countDown();
            }
            joiner.join(5000);
            assertFalse(joiner.isAlive(), "native context join must complete after close");
            assertTrue(actorCompleted.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertNull(contextCloseFailure.get(),
                    () -> String.valueOf(contextCloseFailure.get()));
            assertEquals(1, closeCount.get(), "native close callback must be exactly once");
            assertEquals(0, io.registeredResourceCount());
            runtime.close();
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            releaseClose.countDown();
            try { OresContext.joinActorsAndNativeIo(runtime, io); }
            catch (IllegalStateException ignored) { }
        }
    }

    @Test
    void nativeWorkerAuthorityWithoutOperationThreadLocalIsStillActorOwned()
            throws Exception {
        var policy = IsolatePolicy.developer();
        var ceiling = RuntimePermissions.denyAll();
        var io = new AsyncNativeIo(2, 1, 2, 1);
        var captured = new AtomicReference<ActorRuntime.NativeIoAuthority>();
        var ready = new CountDownLatch(1);
        var resource = new Resource();
        var runtime = new ActorRuntime(policy,
                ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable(), null, ceiling);
        runtime.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
        io.setActorLoanRetiredListener(runtime::onNativeIoLoanRetired);
        try {
            var actor = runtime.<String>spawnSharedTrusted(policy,
                    ignored -> (message, turn) -> {
                        captured.set(ActorRuntime.captureNativeIoAuthority());
                        ready.countDown();
                    });
            actor.send("capture");
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertNotNull(captured.get());
            // Simulate a trusted native worker carrying the captured actor
            // authority outside the active operation-ID ThreadLocal.
            ActorRuntime.enterNativeIoAuthority(captured.get());
            try {
                io.register(resource, 11);
                assertEquals(1, io.outstandingActorOperations(actor.id()),
                        "authority-only worker must still charge original actor");
                assertThrows(SecurityException.class,
                        () -> io.unregister(resource),
                        "authority-only worker must not impersonate host ownership");
                io.closeAndUnregister(resource);
                assertEquals(0, io.outstandingActorOperations(actor.id()));
            } finally {
                ActorRuntime.exitNativeIoAuthority(captured.get());
            }
            assertEquals(1, resource.closed.get());
            OresContext.joinActorsAndNativeIo(runtime, io);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
        } finally {
            try { OresContext.joinActorsAndNativeIo(runtime, io); }
            catch (IllegalStateException ignored) { }
        }
    }

    private static final AtomicReference<Probe> ACTIVE = new AtomicReference<>();

    private static final class Probe {
        final AsyncNativeIo io = new AsyncNativeIo(2, 1, 2, 2);
        final Resource first = new Resource();
        final Resource second = new Resource();
        final CountDownLatch checked = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile boolean deniedHandle;
        volatile boolean deniedBytes;
        volatile int handlesBeforeTransfer;
        volatile int handlesAfterTransfer;
    }

    private static ActorRuntime.Behavior<String> child(
            ActorRuntime.ActorContext<String> ignored) {
        return (message, actor) -> {
            var p = ACTIVE.get();
            try {
                p.io.register(p.first, 16);
                p.handlesBeforeTransfer = p.io.registeredResourceCount();
                try {
                    p.io.register(p.second, 0);
                } catch (RejectedExecutionException expected) {
                    p.deniedHandle = true;
                }
                assertThrows(SecurityException.class, () -> p.io.unregister(p.first));
                // Generic actor-owned resources must retire via a trusted
                // adapter callback, not an unproven unregister receipt.
                p.io.closeAndUnregister(p.first);
                p.handlesAfterTransfer = p.io.registeredResourceCount();
                try {
                    p.io.register(p.second, 17);
                } catch (RejectedExecutionException expected) {
                    p.deniedBytes = true;
                }
                p.io.register(p.second, 1);
                p.io.closeAndUnregister(p.second);
            } catch (Throwable failure) {
                p.failure.compareAndSet(null, failure);
            } finally {
                p.checked.countDown();
            }
        };
    }

    @Test
    void coordinatedContextShutdownRetiresRegisteredHandleBeforeActorFinalizes()
            throws Exception {
        var ceiling = RuntimePermissions.denyAll();
        var policy = IsolatePolicy.developer();
        var io = new AsyncNativeIo(2, 1, 2, 2);
        var retained = new Resource();
        var registered = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var rt = new ActorRuntime(
                policy, ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable(), null, ceiling);
        rt.setNativeIoLoanRetirementGate(io::actorPhysicallyRetired);
        io.setActorLoanRetiredListener(rt::onNativeIoLoanRetired);
        try {
            var actor = rt.<String>spawnSharedTrusted(policy,
                    ignored -> (message, turn) -> {
                        try {
                            io.register(retained, 16);
                        } catch (Throwable unexpected) {
                            failure.compareAndSet(null, unexpected);
                        } finally {
                            registered.countDown();
                        }
                    });
            actor.send("register");
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            assertNull(failure.get(), () -> String.valueOf(failure.get()));
            assertEquals(1, io.registeredResourceCount());
            // This exercise uses the production context shutdown coordinator.
            // Actor physical quiescence cannot be true until the native
            // registration's successful close returns its original lease.
            OresContext.joinActorsAndNativeIo(rt, io);
            assertTrue(actor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(1, retained.closed.get());
            assertEquals(0, io.registeredResourceCount());
            assertEquals(0L, io.registeredAccountedBytes());
        } finally {
            try { OresContext.joinActorsAndNativeIo(rt, io); }
            catch (IllegalStateException ignored) { }
        }
    }

    @Test
    void realActorNativeRegistrationEnforcesDelegatedResourceBudgets()
            throws Exception {
        var ceiling = RuntimePermissions.denyAll();
        var policy = IsolatePolicy.developer();
        var p = new Probe();
        assertTrue(ACTIVE.compareAndSet(null, p));
        try {
            try (var rt = new ActorRuntime(
                    policy, ActorRuntime.DispatcherConfig.defaults(),
                    ActorRuntime.TurnExecutor.direct(),
                    ActorRuntime.ControlDispatcher.unavailable(), null, ceiling)) {
                p.io.setActorLoanRetiredListener(rt::onNativeIoLoanRetired);
                rt.setNativeIoLoanRetirementGate(p.io::actorPhysicallyRetired);
                var parent = rt.<String>spawnSharedTrusted(policy,
                        ignored -> (message, actor) -> {
                            try {
                                var grant = actor.runtime().delegatePermissions(
                                        ceiling, 2, 1, 16);
                                var child = actor.runtime().<String>spawnChild(
                                        ActorRuntime.ActorKind.SHARED,
                                        policy, grant,
                                        ActorRegisteredResourceQuotaTest::child);
                                child.send("register");
                            } catch (Throwable failure) {
                                p.failure.compareAndSet(null, failure);
                                p.checked.countDown();
                            }
                        });
                try {
                    parent.send("spawn");
                    assertTrue(p.checked.await(5, TimeUnit.SECONDS));
                    assertNull(p.failure.get(), () -> String.valueOf(p.failure.get()));
                    assertEquals(1, p.handlesBeforeTransfer);
                    assertEquals(0, p.handlesAfterTransfer);
                    assertTrue(p.deniedHandle);
                    assertTrue(p.deniedBytes);
                    assertEquals(0, p.io.registeredResourceCount());
                    assertEquals(0L, p.io.registeredAccountedBytes());
                } finally {
                    parent.stop();
                }
            }
        } finally {
            ACTIVE.compareAndSet(p, null);
            p.io.close();
        }
        assertEquals(1, p.first.closed.get());
        assertEquals(1, p.second.closed.get());
    }
}
