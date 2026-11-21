package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class ActorPermissionUnifiedRetirementTest {
    private static final AtomicReference<Harness> ACTIVE = new AtomicReference<>();

    private static final class Harness {
        final AsyncNativeIo io;
        final CountDownLatch workerStarted = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        final AtomicReference<OresFuture<Void>> workerFuture =
                new AtomicReference<>();

        Harness(AsyncNativeIo io) {
            this.io = io;
        }
    }

    /**
     * Capture-free factory: delegated child state is obtained from the static
     * test harness rather than a JVM object captured into actor state.
     */
    private enum DelegatedChildFactory
            implements ActorRuntime.BehaviorFactory<String> {
        INSTANCE;

        @Override
        public ActorRuntime.Behavior<String> create(
                ActorRuntime.ActorContext<String> context) {
            return (message, turn) -> {
                if (!"work".equals(message)) return;
                Harness harness = ACTIVE.get();
                if (harness == null) {
                    throw new IllegalStateException(
                            "delegated permission harness is not installed");
                }
                harness.workerFuture.set(
                        harness.io.submit(
                                "delegated-held-worker",
                                () -> {
                                    // Native I/O authority carries only the
                                    // delegated permission/physical-lifetime
                                    // identity. It must not become an actor turn
                                    // or host/supervisor capability.
                                    assertFalse(ActorRuntime.inActorExecution());
                                    assertTrue(ActorRuntime.currentActorId().isEmpty());
                                    assertThrows(
                                            SecurityException.class,
                                            () -> turn.runtime().createActorGroup());

                                    harness.workerStarted.countDown();
                                    if (!harness.releaseWorker.await(
                                            5, TimeUnit.SECONDS)) {
                                        throw new AssertionError(
                                                "delegated worker release timed out");
                                    }
                                    return null;
                                }));
            };
        }
    }

    @Test
    void revokedScopeReceiptWaitsForEveryPhysicalLease() {
        ActorPermissionScope root = ActorPermissionScope.root(
                RuntimePermissions.denyAll(),
                () -> { });
        ActorPermissionScope delegated = root.attenuate(
                RuntimePermissions.denyAll(),
                () -> { });

        ActorPermissionScope.PhysicalLease first =
                delegated.acquirePhysicalLease("worker");
        ActorPermissionScope.PhysicalLease second =
                delegated.acquirePhysicalLease("registered-handle");

        assertTrue(delegated.revoke());
        assertTrue(delegated.tryDrainReceipt().isEmpty());
        assertThrows(
                SecurityException.class,
                () -> delegated.acquirePhysicalLease("late"));

        first.close();
        assertTrue(delegated.tryDrainReceipt().isEmpty());

        second.close();
        ActorPermissionScope.DrainReceipt receipt =
                delegated.tryDrainReceipt().orElseThrow();
        assertEquals(
                receipt,
                delegated.tryDrainReceipt().orElseThrow(),
                "drain receipt identity must remain stable");
    }

    @Test
    void revocationRaceCannotMintDrainWhileWorkerLeaseIsLive()
            throws Exception {
        ActorPermissionScope scope = ActorPermissionScope.root(
                RuntimePermissions.denyAll(),
                () -> { }).attenuate(
                        RuntimePermissions.denyAll(),
                        () -> { });

        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread worker = Thread.ofVirtual().start(() -> {
            try (ActorPermissionScope.PhysicalLease lease =
                         scope.acquirePhysicalLease("racing-worker")) {
                admitted.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("worker release timed out");
                }
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });

        try {
            assertTrue(admitted.await(5, TimeUnit.SECONDS));
            assertTrue(scope.revoke());
            assertTrue(
                    scope.tryDrainReceipt().isEmpty(),
                    "revocation forged drain before physical retirement");
            assertThrows(
                    SecurityException.class,
                    () -> scope.acquirePhysicalLease("post-revoke"));
        } finally {
            release.countDown();
        }

        worker.join(5_000);
        assertFalse(worker.isAlive());
        assertNull(failure.get(), () -> String.valueOf(failure.get()));
        assertTrue(scope.tryDrainReceipt().isPresent());
    }

    @Test
    void quarantinedDelegationCannotRecoverDrainFromCounterReachingZero() {
        ActorPermissionScope root = ActorPermissionScope.root(
                RuntimePermissions.denyAll(),
                () -> { });
        ActorPermissionScope delegated = root.attenuate(
                RuntimePermissions.denyAll(),
                () -> { });
        ActorPermissionScope sibling = root.attenuate(
                RuntimePermissions.denyAll(),
                () -> { });

        ActorPermissionScope.PhysicalLease lease =
                delegated.acquirePhysicalLease("late-native-owner");
        delegated.quarantinePhysicalDrain();
        assertTrue(delegated.revoke());
        lease.close();

        assertTrue(
                delegated.tryDrainReceipt().isEmpty(),
                "quarantined scope must not mint a drain receipt");
        assertTrue(sibling.revoke());
        assertTrue(
                sibling.tryDrainReceipt().isPresent(),
                "one quarantined delegation must not erase sibling evidence");
    }

    @Test
    void delegatedNativeWorkerPinsChildAndParentArenaUntilPhysicalExit()
            throws Exception {
        try (AsyncNativeIo io = new AsyncNativeIo();
             ActorRuntime runtime = new ActorRuntime(
                     IsolatePolicy.developer(),
                     new ActorRuntime.DispatcherConfig(2, 2, 8, 64))) {
            Harness harness = new Harness(io);
            assertTrue(
                    ACTIVE.compareAndSet(null, harness),
                    "permission harness leaked from another test");

            CountDownLatch setupComplete = new CountDownLatch(1);
            CountDownLatch revokeComplete = new CountDownLatch(1);
            AtomicReference<ActorRuntime.PermissionDelegation> delegation =
                    new AtomicReference<>();
            AtomicReference<ActorRuntime.ActorRef<String>> childRef =
                    new AtomicReference<>();
            AtomicReference<ActorRuntime.PrivateMemoryBlock> parentMemory =
                    new AtomicReference<>();

            ActorRuntime.ActorRef<String> parent = runtime.spawnPrivateTrusted(
                    actorContext -> {
                        ActorRuntime.PrivateMemoryBlock block =
                                actorContext.privateMemory()
                                        .orElseThrow()
                                        .allocatePrivateBytes(32);
                        block.writeInt(0, 211);
                        parentMemory.set(block);

                        return (message, context) -> {
                            if ("setup".equals(message)) {
                                ActorRuntime.PermissionDelegation token =
                                        context.runtime().delegatePermissions(
                                                RuntimePermissions.denyAll());
                                delegation.set(token);
                                ActorRuntime.ActorHandle<String> child =
                                        context.runtime().spawnChild(
                                                ActorRuntime.ActorKind.PRIVATE,
                                                IsolatePolicy.developer(),
                                                token,
                                                DelegatedChildFactory.INSTANCE);
                                childRef.set(child.ref());
                                child.send("work");
                                setupComplete.countDown();
                                return;
                            }

                            if ("revoke-stop".equals(message)) {
                                ActorRuntime.PermissionDelegation token =
                                        delegation.get();
                                if (token == null) {
                                    throw new IllegalStateException(
                                            "permission delegation missing");
                                }
                                if (!context.runtime().revokePermissions(token)) {
                                    throw new IllegalStateException(
                                            "delegation was already revoked");
                                }
                                assertTrue(
                                        context.runtime()
                                                .tryPermissionDrainReceipt(token)
                                                .isEmpty(),
                                        "running worker forged permission drain");
                                revokeComplete.countDown();
                                context.self().stop();
                            }
                        };
                    });

            try {
                parent.send("setup");
                assertTrue(setupComplete.await(5, TimeUnit.SECONDS));
                assertTrue(
                        harness.workerStarted.await(5, TimeUnit.SECONDS),
                        "delegated native worker never entered physical execution");

                parent.send("revoke-stop");
                assertTrue(revokeComplete.await(5, TimeUnit.SECONDS));

                ActorRuntime.PrivateMemoryBlock block = parentMemory.get();
                assertNotNull(block);
                assertFalse(block.closed());

                OresFuture<Void> worker = harness.workerFuture.get();
                assertNotNull(worker);
                assertFalse(
                        parent.done().isDone(),
                        "parent DONE published while delegated worker remained physical");
                assertFalse(
                        parent.awaitTermination(100, TimeUnit.MILLISECONDS),
                        "parent finalized before delegated permission drain");
                assertFalse(
                        block.closed(),
                        "parent arena closed before delegated physical I/O retired");

                ActorRuntime.ActorRef<String> child = childRef.get();
                assertNotNull(child);
                assertFalse(
                        child.done().isDone(),
                        "child finalized before native worker lease retired");

                harness.releaseWorker.countDown();
                worker.get(5, TimeUnit.SECONDS);

                assertTrue(
                        parent.awaitTermination(5, TimeUnit.SECONDS),
                        "parent did not finalize after delegated physical drain");
                assertTrue(block.closed());
                assertTrue(parent.done().isDone());
                assertTrue(child.done().isDone());
                assertEquals(0, runtime.actorCount());
            } finally {
                harness.releaseWorker.countDown();
                parent.stop();
                ACTIVE.compareAndSet(harness, null);
            }
        } finally {
            ACTIVE.set(null);
        }
    }
}
