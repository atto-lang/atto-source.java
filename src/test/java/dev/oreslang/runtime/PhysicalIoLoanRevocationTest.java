package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Revocation at the physical resource admission boundary, not only at
 * AsyncNativeIo.submit or a named-resource permission check.
 */
@Timeout(30)
final class PhysicalIoLoanRevocationTest {
    private static final AtomicReference<Probe> ACTIVE = new AtomicReference<>();

    private static final class TrackedResource implements AutoCloseable {
        final AtomicInteger closes = new AtomicInteger();

        @Override public void close() {
            if (closes.incrementAndGet() != 1) {
                throw new IllegalStateException("duplicate physical resource close");
            }
        }
    }

    private static final class Probe {
        final AsyncNativeIo io = new AsyncNativeIo(2);
        final TrackedResource registered = new TrackedResource();
        final CountDownLatch workerEntered = new CountDownLatch(1);
        final CountDownLatch workerRelease = new CountDownLatch(1);
        final CountDownLatch submitted = new CountDownLatch(1);
        final CountDownLatch revoked = new CountDownLatch(1);
        final AtomicReference<OresFuture<Boolean>> result = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicReference<ActorRuntime.PermissionDelegation> token = new AtomicReference<>();
        volatile boolean revokedOnce;
        volatile boolean borrowDenied;
        volatile boolean registerDenied;
    }

    private static ActorRuntime.Behavior<String> delegatedChild(
            ActorRuntime.ActorContext<String> ignored) {
        return (message, turn) -> {
            Probe probe = ACTIVE.get();
            try {
                probe.result.set(probe.io.submit("resource-loan-revocation", () -> {
                    probe.workerEntered.countDown();
                    if (!probe.workerRelease.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("worker release timed out");
                    }
                    try {
                        probe.io.borrow(probe.registered).close();
                    } catch (SecurityException expected) {
                        probe.borrowDenied = true;
                    }
                    TrackedResource fresh = new TrackedResource();
                    try {
                        probe.io.register(fresh);
                    } catch (SecurityException expected) {
                        probe.registerDenied = true;
                    } finally {
                        // Rejected publication leaves ownership with the caller.
                        if (!probe.registerDenied) probe.io.unregister(fresh);
                        fresh.close();
                    }
                    return probe.borrowDenied && probe.registerDenied;
                }));
            } catch (Throwable failure) {
                probe.error.set(failure);
            } finally {
                probe.submitted.countDown();
            }
        };
    }

    @Test
    void revokedWorkerCannotBorrowOrRegisterPhysicalResources() throws Exception {
        var policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.ENVIRONMENT);
        var ceiling = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.ENV, "PUBLIC_KEY");
        Probe probe = new Probe();
        assertTrue(ACTIVE.compareAndSet(null, probe));
        try {
            probe.io.register(probe.registered);
            try (ActorRuntime runtime = new ActorRuntime(
                    policy, ActorRuntime.DispatcherConfig.defaults(),
                    ActorRuntime.TurnExecutor.direct(),
                    ActorRuntime.ControlDispatcher.unavailable(),
                    null, ceiling)) {
                var parent = runtime.<String>spawnSharedTrusted(policy,
                        ignored -> (message, actor) -> {
                            try {
                                if ("spawn".equals(message)) {
                                    var delegation = actor.runtime().delegatePermissions(ceiling);
                                    probe.token.set(delegation);
                                    var child = actor.runtime().<String>spawnChild(
                                            ActorRuntime.ActorKind.SHARED, policy,
                                            delegation, PhysicalIoLoanRevocationTest::delegatedChild);
                                    child.send("start");
                                } else if ("revoke".equals(message)) {
                                    probe.revokedOnce = actor.runtime()
                                            .revokePermissions(probe.token.get());
                                }
                            } catch (Throwable failure) {
                                probe.error.compareAndSet(null, failure);
                            } finally {
                                if ("revoke".equals(message)) probe.revoked.countDown();
                            }
                        });
                try {
                    parent.send("spawn");
                    assertTrue(probe.submitted.await(5, TimeUnit.SECONDS));
                    assertTrue(probe.workerEntered.await(5, TimeUnit.SECONDS));
                    assertNull(probe.error.get(), () -> String.valueOf(probe.error.get()));
                    parent.send("revoke");
                    assertTrue(probe.revoked.await(5, TimeUnit.SECONDS));
                    assertTrue(probe.revokedOnce);
                    probe.workerRelease.countDown();
                    assertNotNull(probe.result.get());
                    assertTrue(probe.result.get().get(5, TimeUnit.SECONDS));
                    assertTrue(probe.borrowDenied);
                    assertTrue(probe.registerDenied);
                    // A rejected borrow may not decrement a nonexistent loan.
                    try (var hostLoan = probe.io.borrow(probe.registered)) {
                        assertNotNull(hostLoan.identity());
                    }
                } finally {
                    probe.workerRelease.countDown();
                    parent.stop();
                }
            }
        } finally {
            probe.workerRelease.countDown();
            ACTIVE.compareAndSet(probe, null);
            probe.io.close();
        }
        assertEquals(1, probe.registered.closes.get(),
                "registered physical resource must have exactly one close");
    }

    @Test
    void plainHostCanRegisterAndBorrowOnAnUnrevokedContext() throws Exception {
        try (AsyncNativeIo io = new AsyncNativeIo(1)) {
            TrackedResource resource = new TrackedResource();
            io.register(resource);
            try (var loan = io.borrow(resource)) {
                assertNotNull(loan.identity());
            }
            io.unregister(resource);
            // A host owns its explicitly unregistered handle.
            resource.close();
            assertEquals(1, resource.closes.get());
        }
    }
}
