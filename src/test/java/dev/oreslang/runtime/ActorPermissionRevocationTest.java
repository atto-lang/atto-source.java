package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorPermissionRevocationTest {
    private static final AtomicReference<Probe> CURRENT = new AtomicReference<>();

    private static final class Probe {
        final CountDownLatch spawned = new CountDownLatch(1);
        final CountDownLatch childChecked = new CountDownLatch(1);
        final CountDownLatch nativeSubmitted = new CountDownLatch(1);
        final CountDownLatch nativeStarted = new CountDownLatch(1);
        final CountDownLatch nativeRelease = new CountDownLatch(1);
        final CountDownLatch revoked = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicReference<ActorRuntime.PermissionDelegation> token = new AtomicReference<>();
        final AtomicReference<OresFuture<Boolean>> future = new AtomicReference<>();
        final AsyncNativeIo io = new AsyncNativeIo(4);
        volatile boolean childDenied;
        volatile boolean nativeDenied;
        volatile boolean revokedOnce;
        volatile boolean secondRevocationIdempotent;
    }

    private static ActorRuntime.Behavior<String> childBehavior(
            ActorRuntime.ActorContext<String> ignored) {
        return (message, child) -> {
            Probe probe = CURRENT.get();
            try {
                if ("check".equals(message)) {
                    try {
                        ActorRuntime.requireCurrentActorPermission(
                                RuntimePermissions.Permission.ENV, "PUBLIC_KEY",
                                "revoked child access");
                    } catch (SecurityException expected) {
                        probe.childDenied = true;
                    } finally {
                        probe.childChecked.countDown();
                    }
                } else if ("native".equals(message)) {
                    probe.future.set(probe.io.submit("revoked native operation", () -> {
                        probe.nativeStarted.countDown();
                        if (!probe.nativeRelease.await(3, TimeUnit.SECONDS)) {
                            throw new AssertionError("native worker release timed out");
                        }
                        try {
                            ActorRuntime.requireCurrentActorPermission(
                                    RuntimePermissions.Permission.ENV, "PUBLIC_KEY",
                                    "revoked worker access");
                        } catch (SecurityException expected) {
                            probe.nativeDenied = true;
                        }
                        return probe.nativeDenied;
                    }));
                    probe.nativeSubmitted.countDown();
                }
            } catch (Throwable failure) {
                probe.error.set(failure);
                probe.childChecked.countDown();
                probe.nativeSubmitted.countDown();
            }
        };
    }

    private static RuntimePermissions envCeiling() {
        return RuntimePermissions.denyAll().withAllowed(
                RuntimePermissions.Permission.ENV, "PUBLIC_KEY");
    }

    @Test
    void revokedDelegationDeniesAllDescendantsWithoutRevokingSibling() {
        ActorPermissionScope root = ActorPermissionScope.root(envCeiling());
        ActorPermissionScope restricted = root.attenuate(envCeiling());
        ActorPermissionScope grandchild = restricted.attenuate(envCeiling());
        ActorPermissionScope sibling = root.attenuate(envCeiling());

        assertDoesNotThrow(() -> grandchild.require(
                RuntimePermissions.Permission.ENV, "PUBLIC_KEY", "before revoke"));
        assertTrue(restricted.revoke());
        assertFalse(restricted.revoke(), "revocation is a one-way idempotent transition");
        assertThrows(SecurityException.class, () -> grandchild.require(
                RuntimePermissions.Permission.ENV, "PUBLIC_KEY", "after revoke"));
        assertThrows(SecurityException.class, () -> restricted.require(
                RuntimePermissions.Permission.ENV, "PUBLIC_KEY", "after revoke"));
        assertDoesNotThrow(() -> sibling.require(
                RuntimePermissions.Permission.ENV, "PUBLIC_KEY", "unrelated sibling"));
    }

    @Test
    void revokedDelegationAlsoDeniesListenerAdmission() {
        var root = ActorPermissionScope.root(RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.NET, "127.0.0.1:0"));
        var child = root.attenuate(RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.NET));
        assertDoesNotThrow(() -> child.requireNetworkListen("127.0.0.1:0", "listener"));
        assertTrue(child.revoke());
        assertThrows(SecurityException.class, () ->
                child.requireNetworkListen("127.0.0.1:0", "listener"));
        assertDoesNotThrow(() -> root.requireNetworkListen("127.0.0.1:0", "root"));
    }

    @Test
    void revokeUnusedDelegationBlocksChildSpawnAndHostMayNotRevoke() throws Exception {
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.ENVIRONMENT);
        Probe probe = new Probe();
        assertTrue(CURRENT.compareAndSet(null, probe));
        try (ActorRuntime runtime = new ActorRuntime(
                policy, ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable(),
                null, envCeiling())) {
            var parent = runtime.<String>spawnSharedTrusted(policy,
                    factory -> (message, context) -> {
                        try {
                            var token = context.runtime().delegatePermissions(envCeiling());
                            probe.token.set(token);
                            assertTrue(context.runtime().revokePermissions(token));
                            assertFalse(context.runtime().revokePermissions(token));
                            probe.revokedOnce = true;
                            assertThrows(SecurityException.class, () ->
                                    context.runtime().<String>spawnChild(
                                            ActorRuntime.ActorKind.SHARED, policy,
                                            token, ActorPermissionRevocationTest::childBehavior));
                        } catch (Throwable failure) {
                            probe.error.set(failure);
                        } finally {
                            probe.spawned.countDown();
                        }
                    });
            parent.send("start");
            assertTrue(probe.spawned.await(3, TimeUnit.SECONDS));
            assertNull(probe.error.get(), () -> String.valueOf(probe.error.get()));
            assertTrue(probe.revokedOnce);
            assertThrows(SecurityException.class, () -> runtime.revokePermissions(probe.token.get()),
                    "host/supervisor must not impersonate delegation issuer");
            parent.stop();
        } finally {
            CURRENT.compareAndSet(probe, null);
            probe.io.close();
        }
    }

    @Test
    void unrelatedActorCannotRevokeAnotherParentsDelegation() throws Exception {
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.ENVIRONMENT);
        try (ActorRuntime runtime = new ActorRuntime(
                policy, ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable(),
                null, envCeiling())) {
            CountDownLatch minted = new CountDownLatch(1);
            CountDownLatch checked = new CountDownLatch(1);
            AtomicReference<ActorRuntime.PermissionDelegation> token =
                    new AtomicReference<>();
            AtomicReference<Throwable> error = new AtomicReference<>();
            var issuer = runtime.<String>spawnSharedTrusted(policy,
                    factory -> (message, context) -> {
                        try {
                            token.set(context.runtime().delegatePermissions(envCeiling()));
                        } catch (Throwable failure) {
                            error.set(failure);
                        } finally {
                            minted.countDown();
                        }
                    });
            issuer.send("mint");
            assertTrue(minted.await(3, TimeUnit.SECONDS));
            assertNull(error.get(), () -> String.valueOf(error.get()));
            var unrelated = runtime.<String>spawnSharedTrusted(policy,
                    factory -> (message, context) -> {
                        try {
                            assertThrows(SecurityException.class, () ->
                                    context.runtime().revokePermissions(token.get()));
                        } catch (Throwable failure) {
                            error.set(failure);
                        } finally {
                            checked.countDown();
                        }
                    });
            unrelated.send("steal");
            assertTrue(checked.await(3, TimeUnit.SECONDS));
            assertNull(error.get(), () -> String.valueOf(error.get()));
            issuer.stop();
            unrelated.stop();
        }
    }

    @Test
    void revokedActiveChildAndAdmittedNativeWorkerCannotReuseScope() throws Exception {
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.ENVIRONMENT);
        Probe probe = new Probe();
        assertTrue(CURRENT.compareAndSet(null, probe));
        try (ActorRuntime runtime = new ActorRuntime(
                policy, ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable(),
                null, envCeiling())) {
            AtomicReference<ActorRuntime.ActorHandle<String>> childRef = new AtomicReference<>();
            var parent = runtime.<String>spawnSharedTrusted(policy,
                    factory -> (message, context) -> {
                        try {
                            if ("spawn".equals(message)) {
                                var token = context.runtime().delegatePermissions(envCeiling());
                                probe.token.set(token);
                                childRef.set(context.runtime().<String>spawnChild(
                                        ActorRuntime.ActorKind.SHARED, policy, token,
                                        ActorPermissionRevocationTest::childBehavior));
                                childRef.get().send("native");
                            } else if ("revoke".equals(message)) {
                                probe.revokedOnce = context.runtime()
                                        .revokePermissions(probe.token.get());
                                probe.revoked.countDown();
                                childRef.get().send("check");
                            }
                        } catch (Throwable failure) {
                            probe.error.set(failure);
                            probe.nativeSubmitted.countDown();
                            probe.revoked.countDown();
                            probe.childChecked.countDown();
                        }
                    });
            parent.send("spawn");
            assertTrue(probe.nativeSubmitted.await(3, TimeUnit.SECONDS));
            assertTrue(probe.nativeStarted.await(3, TimeUnit.SECONDS));
            assertNull(probe.error.get(), () -> String.valueOf(probe.error.get()));
            parent.send("revoke");
            assertTrue(probe.revoked.await(3, TimeUnit.SECONDS));
            assertTrue(probe.revokedOnce);
            probe.nativeRelease.countDown();
            assertNotNull(probe.future.get());
            assertTrue(probe.future.get().get(3, TimeUnit.SECONDS));
            assertTrue(probe.nativeDenied);
            assertTrue(probe.childChecked.await(3, TimeUnit.SECONDS));
            assertTrue(probe.childDenied);
            assertNull(probe.error.get(), () -> String.valueOf(probe.error.get()));
            parent.stop();
        } finally {
            probe.nativeRelease.countDown();
            CURRENT.compareAndSet(probe, null);
            probe.io.close();
        }
    }
}
