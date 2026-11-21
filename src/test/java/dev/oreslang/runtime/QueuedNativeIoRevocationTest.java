package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Real actor admission with a single physical I/O worker and a queued operation. */
@Timeout(30)
final class QueuedNativeIoRevocationTest {
    private static final AtomicReference<Probe> ACTIVE = new AtomicReference<>();

    private static final class Probe {
        final AsyncNativeIo io = new AsyncNativeIo(2, 1, 2);
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch secondSubmitted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch revoked = new CountDownLatch(1);
        final AtomicInteger queuedWorkInvocations = new AtomicInteger();
        final AtomicReference<OresFuture<Integer>> first = new AtomicReference<>();
        final AtomicReference<OresFuture<Integer>> second = new AtomicReference<>();
        final AtomicReference<ActorRuntime.PermissionDelegation> delegation =
                new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        volatile boolean revocationSucceeded;
    }

    private static ActorRuntime.Behavior<String> childBehavior(
            ActorRuntime.ActorContext<String> ignored) {
        return (message, turn) -> {
            Probe probe = ACTIVE.get();
            try {
                probe.first.set(probe.io.submit("first-inflight", () -> {
                    probe.firstStarted.countDown();
                    if (!probe.releaseFirst.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("first physical I/O operation release timed out");
                    }
                    return 1;
                }));
                probe.second.set(probe.io.submit("second-queued", () -> {
                    probe.queuedWorkInvocations.incrementAndGet();
                    return 2;
                }));
            } catch (Throwable failure) {
                probe.error.set(failure);
            } finally {
                probe.secondSubmitted.countDown();
            }
        };
    }

    private static void exercise(boolean revoke) throws Exception {
        var policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.ENVIRONMENT);
        var ceiling = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.ENV, "PUBLIC_KEY");
        Probe probe = new Probe();
        assertTrue(ACTIVE.compareAndSet(null, probe), "test probe must be isolated");
        try {
            try (ActorRuntime runtime = new ActorRuntime(
                    policy, ActorRuntime.DispatcherConfig.defaults(),
                    ActorRuntime.TurnExecutor.direct(),
                    ActorRuntime.ControlDispatcher.unavailable(),
                    null, ceiling)) {
                var parent = runtime.<String>spawnSharedTrusted(policy,
                        factory -> (message, context) -> {
                            try {
                                if ("spawn".equals(message)) {
                                    var token = context.runtime().delegatePermissions(ceiling);
                                    probe.delegation.set(token);
                                    var child = context.runtime().<String>spawnChild(
                                            ActorRuntime.ActorKind.SHARED, policy, token,
                                            QueuedNativeIoRevocationTest::childBehavior);
                                    child.send("submit");
                                } else if ("revoke".equals(message)) {
                                    probe.revocationSucceeded = context.runtime()
                                            .revokePermissions(probe.delegation.get());
                                }
                            } catch (Throwable failure) {
                                probe.error.compareAndSet(null, failure);
                            } finally {
                                if ("revoke".equals(message)) probe.revoked.countDown();
                            }
                        });
                try {
                    parent.send("spawn");
                    assertTrue(probe.firstStarted.await(5, TimeUnit.SECONDS),
                            "first worker did not start");
                    assertTrue(probe.secondSubmitted.await(5, TimeUnit.SECONDS),
                            "second operation was not queued");
                    assertNull(probe.error.get(), () -> String.valueOf(probe.error.get()));
                    assertNotNull(probe.first.get());
                    assertNotNull(probe.second.get());
                    if (revoke) {
                        parent.send("revoke");
                        assertTrue(probe.revoked.await(5, TimeUnit.SECONDS));
                        assertTrue(probe.revocationSucceeded);
                    }
                    probe.releaseFirst.countDown();
                    assertEquals(1, probe.first.get().get(5, TimeUnit.SECONDS));
                    if (revoke) {
                        assertThrows(Exception.class, () ->
                                probe.second.get().get(5, TimeUnit.SECONDS),
                                "revoked queued I/O must fail before invoking work.call()");
                        assertEquals(0, probe.queuedWorkInvocations.get(),
                                "revoked queued callable caused a host side effect");
                    } else {
                        assertEquals(2, probe.second.get().get(5, TimeUnit.SECONDS));
                        assertEquals(1, probe.queuedWorkInvocations.get());
                    }
                } finally {
                    probe.releaseFirst.countDown();
                    parent.stop();
                }
            }
        } finally {
            probe.releaseFirst.countDown();
            ACTIVE.compareAndSet(probe, null);
            probe.io.close();
        }
    }

    @Test
    void revokedQueuedOperationNeverEntersItsHostCallable() throws Exception {
        exercise(true);
    }

    @Test
    void stillAuthorizedQueuedOperationExecutesExactlyOnce() throws Exception {
        exercise(false);
    }
}
