package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorScopedPermissionDelegationTest {
    private static final AtomicReference<Probe> ACTIVE = new AtomicReference<>();

    private static final class Probe {
        final CountDownLatch childCompleted = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        volatile boolean childAllowed;
        volatile boolean childDeniedParentOnly;
        volatile boolean parentAllowedParentOnly;
        volatile boolean reusedTokenRejected;
    }

    /** Stateless guest-style factory: no closure over test-owned JVM objects. */
    private static ActorRuntime.Behavior<String> childBehavior(
            ActorRuntime.ActorContext<String> context) {
        return (message, childContext) -> {
            Probe probe = ACTIVE.get();
            try {
                ActorRuntime.requireCurrentActorPermission(
                        RuntimePermissions.Permission.ENV, "SHARED_KEY", "child allowed");
                probe.childAllowed = true;
                try {
                    ActorRuntime.requireCurrentActorPermission(
                            RuntimePermissions.Permission.ENV, "PARENT_ONLY_KEY", "child denied");
                } catch (SecurityException expected) {
                    probe.childDeniedParentOnly = true;
                }
            } catch (Throwable failure) {
                probe.error.set(failure);
            } finally {
                probe.childCompleted.countDown();
                childContext.self().stop();
            }
        };
    }

    @Test
    void immutableScopesIntersectEveryAncestorEvenWithWildcardDelegation() {
        RuntimePermissions ceiling = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.ENV, "ALLOWED")
                .withDenied(RuntimePermissions.Permission.ENV, "SECRET");
        ActorPermissionScope root = ActorPermissionScope.root(ceiling);
        ActorPermissionScope child = root.attenuate(RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.ENV));
        ActorPermissionScope grandchild = child.attenuate(RuntimePermissions.denyAll()
                .allowAll(RuntimePermissions.Permission.ENV));

        assertDoesNotThrow(() -> grandchild.require(
                RuntimePermissions.Permission.ENV, "ALLOWED", "test"));
        assertThrows(SecurityException.class, () -> grandchild.require(
                RuntimePermissions.Permission.ENV, "SECRET", "test"));
        assertThrows(SecurityException.class, () -> grandchild.require(
                RuntimePermissions.Permission.ENV, "NEVER_GRANTED", "test"));
    }

    @Test
    void currentActorCanDelegateOnlyNarrowerResourcesAndTokenIsOneShot() throws Exception {
        RuntimePermissions ceiling = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.ENV, "SHARED_KEY", "PARENT_ONLY_KEY");
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.ENVIRONMENT);
        Probe probe = new Probe();
        assertTrue(ACTIVE.compareAndSet(null, probe), "test probe already in use");
        try (ActorRuntime runtime = new ActorRuntime(
                policy,
                ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable(),
                null,
                ceiling)) {
            var parent = runtime.<String>spawnSharedTrusted(policy,
                    factoryContext -> (message, actorContext) -> {
                        try {
                            ActorRuntime.requireCurrentActorPermission(
                                    RuntimePermissions.Permission.ENV,
                                    "PARENT_ONLY_KEY", "parent allowed");
                            probe.parentAllowedParentOnly = true;
                            ActorRuntime.PermissionDelegation delegation =
                                    actorContext.runtime().delegatePermissions(
                                            RuntimePermissions.denyAll().withAllowed(
                                                    RuntimePermissions.Permission.ENV,
                                                    "SHARED_KEY"));
                            var child = actorContext.runtime().<String>spawnChild(
                                    ActorRuntime.ActorKind.SHARED,
                                    policy, delegation,
                                    ActorScopedPermissionDelegationTest::childBehavior);
                            child.send("check");
                            try {
                                actorContext.runtime().<String>spawnChild(
                                        ActorRuntime.ActorKind.SHARED,
                                        policy, delegation,
                                        ActorScopedPermissionDelegationTest::childBehavior);
                            } catch (SecurityException expected) {
                                probe.reusedTokenRejected = true;
                            }
                        } catch (Throwable failure) {
                            probe.error.set(failure);
                            probe.childCompleted.countDown();
                        }
                    });
            parent.send("start");
            assertTrue(probe.childCompleted.await(3, TimeUnit.SECONDS),
                    "delegated child never ran");
            assertNull(probe.error.get(), () -> "unexpected actor exception " + probe.error.get());
            assertTrue(probe.parentAllowedParentOnly);
            assertTrue(probe.childAllowed);
            assertTrue(probe.childDeniedParentOnly);
            assertTrue(probe.reusedTokenRejected);
            parent.stop();
        } finally {
            ACTIVE.compareAndSet(probe, null);
        }
    }

    private static final AtomicReference<AsyncProbe> ACTIVE_IO = new AtomicReference<>();

    private static final class AsyncProbe {
        final AsyncNativeIo io = new AsyncNativeIo(4);
        final CountDownLatch admitted = new CountDownLatch(1);
        final AtomicReference<OresFuture<Boolean>> future = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final ActorRuntime runtime;

        AsyncProbe(ActorRuntime runtime) { this.runtime = runtime; }
    }

    private static ActorRuntime.Behavior<String> asyncChildBehavior(
            ActorRuntime.ActorContext<String> context) {
        return (message, childContext) -> {
            AsyncProbe probe = ACTIVE_IO.get();
            try {
                probe.future.set(probe.io.submit("test-scoped-native-io", () -> {
                    ActorRuntime.requireCurrentActorPermission(
                            RuntimePermissions.Permission.ENV, "SHARED_KEY", "native allow");
                    assertThrows(SecurityException.class, () ->
                            ActorRuntime.requireCurrentActorPermission(
                                    RuntimePermissions.Permission.ENV,
                                    "PARENT_ONLY_KEY", "native deny"));
                    // A worker with an actor's I/O authority must not become
                    // either that actor or a privileged host supervisor.
                    assertThrows(SecurityException.class, () ->
                            probe.runtime.spawnShared(() -> (m, c) -> { }));
                    return true;
                }));
            } catch (Throwable failure) {
                probe.error.set(failure);
            } finally {
                probe.admitted.countDown();
            }
        };
    }

    @Test
    void asyncNativeIoWorkerPreservesDelegatedScopeWithoutSupervisorAuthority() throws Exception {
        RuntimePermissions ceiling = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.ENV, "SHARED_KEY", "PARENT_ONLY_KEY");
        IsolatePolicy policy = IsolatePolicy.developer()
                .withCapabilities(IsolatePolicy.Capability.ENVIRONMENT);
        try (ActorRuntime runtime = new ActorRuntime(
                policy,
                ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(),
                ActorRuntime.ControlDispatcher.unavailable(),
                null, ceiling)) {
            AsyncProbe probe = new AsyncProbe(runtime);
            assertTrue(ACTIVE_IO.compareAndSet(null, probe));
            try {
                AtomicReference<Throwable> parentError = new AtomicReference<>();
                var parent = runtime.<String>spawnSharedTrusted(policy,
                        factoryContext -> (message, actorContext) -> {
                            try {
                                var token = actorContext.runtime().delegatePermissions(
                                        RuntimePermissions.denyAll().withAllowed(
                                                RuntimePermissions.Permission.ENV, "SHARED_KEY"));
                                var child = actorContext.runtime().<String>spawnChild(
                                        ActorRuntime.ActorKind.SHARED, policy,
                                        token, ActorScopedPermissionDelegationTest::asyncChildBehavior);
                                child.send("check");
                            } catch (Throwable failure) {
                                parentError.set(failure);
                                probe.admitted.countDown();
                            }
                        });
                parent.send("spawn");
                assertTrue(probe.admitted.await(3, TimeUnit.SECONDS));
                assertNull(parentError.get(), () -> String.valueOf(parentError.get()));
                assertNull(probe.error.get(), () -> String.valueOf(probe.error.get()));
                assertNotNull(probe.future.get());
                assertTrue(probe.future.get().get(3, TimeUnit.SECONDS));
                parent.stop();
            } finally {
                ACTIVE_IO.compareAndSet(probe, null);
                probe.io.close();
            }
        }
    }

    @Test
    void hostThreadCannotMintDelegationAuthority() {
        try (ActorRuntime runtime = new ActorRuntime()) {
            assertThrows(SecurityException.class, () ->
                    runtime.delegatePermissions(RuntimePermissions.denyAll()
                            .allowAll(RuntimePermissions.Permission.READ)));
        }
    }

    @Test
    void permissionCeilingIsNotWidenedByChildScope() {
        RuntimePermissions rootPolicy = RuntimePermissions.denyAll()
                .withAllowed(RuntimePermissions.Permission.READ, "/tenant/a");
        ActorPermissionScope child = ActorPermissionScope.root(rootPolicy)
                .attenuate(RuntimePermissions.denyAll()
                        .allowAll(RuntimePermissions.Permission.READ));

        assertDoesNotThrow(() -> child.require(RuntimePermissions.Permission.READ,
                "/tenant/a/report.txt", "child read"));
        assertThrows(SecurityException.class, () -> child.require(
                RuntimePermissions.Permission.READ, "/tenant/b/secret.txt", "child read"));
    }
}
