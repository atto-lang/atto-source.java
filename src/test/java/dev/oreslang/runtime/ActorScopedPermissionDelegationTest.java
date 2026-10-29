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
