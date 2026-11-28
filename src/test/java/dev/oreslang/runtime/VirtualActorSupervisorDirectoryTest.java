package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
final class VirtualActorSupervisorDirectoryTest {
    private static final String TENANT = "tenant-virtual-shop";

    private static ActorRuntime runtime(ActorRuntime.ControlDispatcher dispatcher) {
        return new ActorRuntime(IsolatePolicy.developer(),
                ActorRuntime.DispatcherConfig.defaults(),
                ActorRuntime.TurnExecutor.direct(), dispatcher);
    }

    private static ActorRuntime.BehaviorFactory<String> behavior() {
        return factory -> (message, context) -> { };
    }

    private static ActorRuntime.ActorRef<String> spawnManaged(
            ActorRuntime.ActorGroup group) throws Exception {
        var actor = group.spawnWorker(ActorRuntime.ActorKind.SHARED,
                IsolatePolicy.developer(), behavior());
        actor.getReady().get(5, TimeUnit.SECONDS);
        return actor;
    }

    private static ActorRuntime.ControlDispatcher pooled(ExecutorService pool) {
        return turn -> OresFuture.fromCallback(done -> pool.execute(() -> {
            try {
                turn.run();
                done.resolve(null);
            } catch (Throwable failure) {
                done.reject(failure);
            }
        }));
    }

    @Test
    void inspectByIdAndAddressRunsOnRealControlAndReportsActualManagedWorker() throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor();
             var runtime = runtime(pooled(pool))) {
            var supervisor = runtime.createSupervisor();
            try (var group = runtime.createActorGroup(supervisor);
                 var directory = supervisor.virtualActorDirectory(TENANT, 16, 16)) {
                var actor = spawnManaged(group);
                var logical = directory.resolve("ShoppingCart", "cart-42");
                long activationGeneration = directory.bindActivation(logical, actor);
                Object session = new Object();
                var grant = directory.issueInspectionGrant(session, TimeUnit.SECONDS.toNanos(4));

                var byId = directory.requestInspect(grant, session, logical.identity(),
                        TimeUnit.SECONDS.toNanos(3)).toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).orElseThrow();
                assertEquals("ACTIVE", byId.lifecycle().name());
                assertEquals(supervisor.id(), byId.supervisorId());
                assertEquals(logical.identity(), byId.identity());
                assertEquals(actor.id(), byId.actorId().orElseThrow());
                assertEquals(actor.generation(), byId.actorReferenceGeneration());
                assertEquals(activationGeneration, byId.activationGeneration());
                assertTrue(byId.partial());

                var byAddress = directory.requestInspectAddress(grant, session, byId.address(),
                        TimeUnit.SECONDS.toNanos(3)).toCompletableFuture()
                        .get(5, TimeUnit.SECONDS).orElseThrow();
                assertEquals(byId.identity(), byAddress.identity());
                assertEquals(byId.lifecycle(), byAddress.lifecycle());
                assertEquals(byId.activationGeneration(), byAddress.activationGeneration());
                assertEquals(byId.actorId(), byAddress.actorId());
                assertEquals(0, directory.pendingInspectionCount());
                actor.stop();
                actor.done().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void onePhysicalWorkerCannotBackTwoLogicalIdentities() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var supervisor = runtime.createSupervisor();
            try (var group = runtime.createActorGroup(supervisor);
                 var directory = supervisor.virtualActorDirectory(TENANT, 8, 4)) {
                var first = spawnManaged(group);
                var second = spawnManaged(group);
                try {
                    var a = directory.resolve("Cart", "one");
                    var b = directory.resolve("Cart", "two");
                    long firstGeneration = directory.bindActivation(a, first);
                    var rejected = directory.prepareActivation(b, "default");
                    long burned = rejected.descriptor().logicalGeneration();
                    assertThrows(IllegalStateException.class,
                            () -> directory.publishActivation(rejected, first));
                    long secondGeneration = directory.bindActivation(b, second);
                    assertTrue(secondGeneration > burned);
                    assertTrue(secondGeneration > firstGeneration);
                } finally {
                    first.stop();
                    second.stop();
                    first.done().get(5, TimeUnit.SECONDS);
                    second.done().get(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void activationBindingRejectsWorkerOwnedByAnotherSupervisor() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var expected = runtime.createSupervisor();
            var other = runtime.createSupervisor();
            try (var expectedGroup = runtime.createActorGroup(expected);
                 var otherGroup = runtime.createActorGroup(other);
                 var directory = new VirtualActorSupervisorDirectory(
                         runtime, expected, TENANT, 8, 4)) {
                var worker = spawnManaged(otherGroup);
                var logical = directory.resolve("Cart", "forged-owner");
                assertThrows(SecurityException.class,
                        () -> directory.bindActivation(logical, worker));
                worker.stop();
                worker.done().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void physicalActorCompletionReleasesLogicalActivationAndReplacementGetsNewGeneration()
            throws Exception {
        try (var runtime = new ActorRuntime()) {
            var supervisor = runtime.createSupervisor();
            try (var group = runtime.createActorGroup(supervisor);
                 var directory = new VirtualActorSupervisorDirectory(
                         runtime, supervisor, TENANT, 8, 4)) {
                var logical = directory.resolve("Cart", "42");
                var first = spawnManaged(group);
                long firstGeneration = directory.bindActivation(logical, first);
                first.stop();
                first.done().get(5, TimeUnit.SECONDS);

                // done() includes finalization and TurnExecutor exit. The directory's
                // runtime-owned completion callback reconciles the old generation.
                var replacement = spawnManaged(group);
                long secondGeneration = directory.bindActivation(logical, replacement);
                assertTrue(secondGeneration > firstGeneration);
                assertNotEquals(first.id(), replacement.id());

                replacement.stop();
                replacement.done().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void missingControlDispatcherFailsClosedWithoutInlineFallback() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var supervisor = runtime.createSupervisor();
            try (var group = runtime.createActorGroup(supervisor);
                 var directory = new VirtualActorSupervisorDirectory(
                         runtime, supervisor, TENANT, 8, 4)) {
                var actor = spawnManaged(group);
                var logical = directory.resolve("Cart", "42");
                directory.bindActivation(logical, actor);
                Object session = new Object();
                var grant = directory.issueInspectionGrant(session, TimeUnit.SECONDS.toNanos(4));
                var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> directory.requestInspect(grant, session, logical.identity(),
                                TimeUnit.SECONDS.toNanos(2)).toCompletableFuture()
                                .get(5, TimeUnit.SECONDS));
                assertInstanceOf(java.util.concurrent.RejectedExecutionException.class,
                        failure.getCause());
                assertEquals(0, directory.pendingInspectionCount());
                actor.stop();
                actor.done().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void queuedTimeoutFencesLateCaptureButRetainsGlobalCreditUntilPhysicalDrain() throws Exception {
        java.util.ArrayDeque<Runnable> queued = new java.util.ArrayDeque<>();
        ActorRuntime.ControlDispatcher queuedControl = turn ->
                OresFuture.fromCallback(done -> queued.addLast(() -> {
                    try {
                        turn.run();
                        done.resolve(null);
                    } catch (Throwable failure) {
                        done.reject(failure);
                    }
                }));
        try (ExecutorService worker = Executors.newSingleThreadExecutor();
             var runtime = runtime(queuedControl);
             var directory = new VirtualActorSupervisorDirectory(
                     runtime, runtime.rootSupervisor(), TENANT, 8, 4)) {
            Object session = new Object();
            var grant = directory.issueInspectionGrant(session, TimeUnit.SECONDS.toNanos(3));
            var logical = directory.resolve("Cart", "queued-timeout");

            var first = directory.requestInspect(grant, session, logical,
                    TimeUnit.MILLISECONDS.toNanos(20)).toCompletableFuture();
            var firstFailure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> first.get(2, TimeUnit.SECONDS));
            assertInstanceOf(java.util.concurrent.TimeoutException.class, firstFailure.getCause());
            assertEquals(1, directory.pendingInspectionCount());

            // The grant is reusable after queued cancellation, but the old
            // request continues to consume global CONTROL capacity until drain.
            // Its old callback has lost the QUEUED->RUNNING race and cannot capture.
            var second = directory.requestInspect(grant, session, logical,
                    TimeUnit.SECONDS.toNanos(1)).toCompletableFuture();
            assertEquals(2, directory.pendingInspectionCount());
            worker.submit(queued.removeFirst()).get(5, TimeUnit.SECONDS);
            assertEquals(1, directory.pendingInspectionCount());
            worker.submit(queued.removeFirst()).get(5, TimeUnit.SECONDS);
            assertEquals(logical.identity(), second.get(5, TimeUnit.SECONDS).identity());
            assertEquals(0, directory.pendingInspectionCount());
        }
    }

    @Test
    void grantsAreBoundToSessionTenantAndSupervisorScope() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var supervisor = runtime.createSupervisor();
            try (var directory = new VirtualActorSupervisorDirectory(
                    runtime, supervisor, TENANT, 8, 4)) {
                var logical = directory.resolve("Cart", "42");
                Object session = new Object();
                var grant = directory.issueInspectionGrant(session, TimeUnit.SECONDS.toNanos(4));
                assertThrows(SecurityException.class, () -> directory.requestInspect(
                        grant, new Object(), logical.identity(), 1_000_000));
                var foreign = new VirtualActorActivationRegistry.Identity(
                        "other-tenant", "7:defaultCart", "42");
                assertThrows(SecurityException.class, () -> directory.requestInspect(
                        grant, session, foreign, 1_000_000));
                directory.revoke(grant);
                assertThrows(SecurityException.class, () -> directory.requestInspect(
                        grant, session, logical.identity(), 1_000_000));
                assertEquals(0, directory.pendingInspectionCount());
            }
        }
    }
    @Test
    void supervisorOwnershipIsUniqueAcrossDirectoriesAndFailedClaimDoesNotLeakQuota() {
        try (var runtime = new ActorRuntime()) {
            var firstSupervisor = runtime.createSupervisor();
            var secondSupervisor = runtime.createSupervisor();
            try (var first = new VirtualActorSupervisorDirectory(
                         runtime, firstSupervisor, TENANT, 8, 4);
                 var second = new VirtualActorSupervisorDirectory(
                         runtime, secondSupervisor, TENANT, 1, 4)) {
                first.resolve("Cart", "same-logical-id");
                assertThrows(SecurityException.class,
                        () -> second.resolve("Cart", "same-logical-id"));
                // Failed ownership admission removes its unactivated local slot.
                assertEquals("another-logical-id",
                        second.resolve("Cart", "another-logical-id").identity().key());
            }
        }
    }

    @Test
    void activationPlanProvidesStableOpaquePersistenceKeyAndBindsNamespace() {
        try (var runtime = new ActorRuntime()) {
            var supervisor = runtime.createSupervisor();
            try (var directory = new VirtualActorSupervisorDirectory(
                    runtime, supervisor, TENANT, 8, 4)) {
                var alpha = directory.resolve("billing", "Account", "42");
                var same = directory.resolve("billing", "Account", "42");
                var otherNamespace = directory.resolve("inventory", "Account", "42");
                assertEquals(alpha.identity(), same.identity());
                assertNotEquals(alpha.identity(), otherNamespace.identity());

                var plan = directory.prepareActivation(alpha, "balance");
                var descriptor = plan.descriptor();
                assertEquals(TENANT, descriptor.persistenceKey().tenant());
                assertEquals("balance", descriptor.persistenceKey().state());
                assertTrue(descriptor.persistenceKey().actor().startsWith("va1_"));
                assertEquals(47, descriptor.persistenceKey().actor().length());
                directory.abortActivation(plan);
            }
        }
    }

    @Test
    void persistentActorKeyIsIndependentOfSupervisorAndRuntimeInstance() {
        String firstActorKey;
        try (var runtime = new ActorRuntime()) {
            var supervisor = runtime.createSupervisor();
            try (var directory = new VirtualActorSupervisorDirectory(
                    runtime, supervisor, TENANT, 8, 4)) {
                var logical = directory.resolve("namespace", "Cart", "42");
                var plan = directory.prepareActivation(logical, "state");
                firstActorKey = plan.descriptor().persistenceKey().actor();
                directory.abortActivation(plan);
            }
        }
        try (var runtime = new ActorRuntime()) {
            var differentSupervisor = runtime.createSupervisor();
            try (var directory = new VirtualActorSupervisorDirectory(
                    runtime, differentSupervisor, TENANT, 8, 4)) {
                var logical = directory.resolve("namespace", "Cart", "42");
                var plan = directory.prepareActivation(logical, "state");
                assertEquals(firstActorKey, plan.descriptor().persistenceKey().actor());
                directory.abortActivation(plan);
            }
        }
    }

    @Test
    void activationGenerationDoesNotResetWhenDirectoryIsRecreatedInSameRuntime() {
        try (var runtime = new ActorRuntime()) {
            var supervisor = runtime.createSupervisor();
            long firstGeneration;
            var identity = new VirtualActorActivationRegistry.Identity(
                    TENANT, "7:defaultCart", "42");
            try (var directory = new VirtualActorSupervisorDirectory(
                    runtime, supervisor, TENANT, 8, 4)) {
                var logical = directory.resolve("Cart", "42");
                var plan = directory.prepareActivation(logical, "state");
                firstGeneration = plan.descriptor().logicalGeneration();
                directory.abortActivation(plan);
                assertTrue(directory.resolve("Cart", "42").identity().equals(identity));
            }
            try (var directory = new VirtualActorSupervisorDirectory(
                    runtime, supervisor, TENANT, 8, 4)) {
                var logical = directory.resolve("Cart", "42");
                var plan = directory.prepareActivation(logical, "state");
                assertTrue(plan.descriptor().logicalGeneration() > firstGeneration);
                directory.abortActivation(plan);
            }
        }
    }

    @Test
    void supervisorListingIsBoundedRevisionFencedAndRunsOnControl() throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor();
             var runtime = runtime(pooled(pool))) {
            var supervisor = runtime.createSupervisor();
            try (var directory = new VirtualActorSupervisorDirectory(
                    runtime, supervisor, TENANT, 16, 4)) {
                directory.resolve("Cart", "c");
                directory.resolve("Cart", "a");
                directory.resolve("Cart", "b");

                var session = new Object();
                var grant = directory.issueInspectionGrant(session, TimeUnit.SECONDS.toNanos(5));
                var first = directory.requestList(grant, session, 1, -1, null,
                        TimeUnit.SECONDS.toNanos(3)).toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                assertEquals(1, first.entries().size());
                assertEquals("a", first.entries().getFirst().identity().key());
                assertTrue(first.nextAfter().isPresent());

                var second = directory.requestList(grant, session, 1, first.revision(),
                        first.nextAfter().orElseThrow(), TimeUnit.SECONDS.toNanos(3))
                        .toCompletableFuture().get(5, TimeUnit.SECONDS);
                assertEquals(1, second.entries().size());
                assertEquals("b", second.entries().getFirst().identity().key());
                assertEquals(first.revision(), second.revision());

                directory.resolve("Cart", "d");
                var stale = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> directory.requestList(grant, session, 1, second.revision(),
                                second.nextAfter().orElseThrow(), TimeUnit.SECONDS.toNanos(3))
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertInstanceOf(VirtualActorActivationRegistry.StaleDirectoryPageException.class,
                        stale.getCause());
                assertEquals(0, directory.pendingInspectionCount());
            }
        }
    }

    @Test
    void supervisorListingRejectsInvalidPageBoundsAndForeignCursor() {
        try (var runtime = new ActorRuntime()) {
            var supervisor = runtime.createSupervisor();
            try (var directory = new VirtualActorSupervisorDirectory(
                    runtime, supervisor, TENANT, 8, 4)) {
                var logical = directory.resolve("Cart", "42");
                Object session = new Object();
                var grant = directory.issueInspectionGrant(session, TimeUnit.SECONDS.toNanos(4));
                assertThrows(IllegalArgumentException.class,
                        () -> directory.requestList(grant, session, 0, -1, null, 1));
                var foreign = new VirtualActorActivationRegistry.Identity(
                        "other-tenant", "7:defaultCart", "42");
                assertThrows(SecurityException.class,
                        () -> directory.requestList(grant, session, 10, -1, foreign, 1));
                assertEquals(0, directory.pendingInspectionCount());
            }
        }
    }

    @Test
    void oneRuntimeOwnedDirectoryRegistrySurvivesFacadeRecreation() throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor();
             var runtime = runtime(pooled(pool))) {
            var supervisor = runtime.createSupervisor();
            try (var directory = new VirtualActorSupervisorDirectory(
                    runtime, supervisor, TENANT, 8, 4)) {
                directory.resolve("Cart", "42");
                assertThrows(IllegalStateException.class,
                        () -> new VirtualActorSupervisorDirectory(
                                runtime, supervisor, TENANT, 8, 4));
            }
            // Facade ownership is released, but the registry and logical identity
            // remain in ActorRuntime rather than being recreated as an empty map.
            try (var replacement = new VirtualActorSupervisorDirectory(
                    runtime, supervisor, TENANT, 8, 4)) {
                Object session = new Object();
                var grant = replacement.issueInspectionGrant(session, TimeUnit.SECONDS.toNanos(4));
                var page = replacement.requestList(grant, session, 8, -1, null,
                        TimeUnit.SECONDS.toNanos(3)).toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                assertEquals(1, page.entries().size());
                assertEquals("42", page.entries().getFirst().identity().key());
                assertEquals("defaultCart", page.entries().getFirst().identity().declaration()
                        .substring(page.entries().getFirst().identity().declaration().indexOf(':') + 1));
            }
        }
    }

    @Test
    void activationOrAdmissionChangeInvalidatesPreviouslyIssuedPageRevision() throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor();
             var runtime = runtime(pooled(pool))) {
            var supervisor = runtime.createSupervisor();
            try (var directory = new VirtualActorSupervisorDirectory(
                    runtime, supervisor, TENANT, 8, 4)) {
                var first = directory.resolve("Cart", "a");
                directory.resolve("Cart", "b");
                Object session = new Object();
                var grant = directory.issueInspectionGrant(session, TimeUnit.SECONDS.toNanos(5));
                var page = directory.requestList(grant, session, 1, -1, null,
                        TimeUnit.SECONDS.toNanos(3)).toCompletableFuture()
                        .get(5, TimeUnit.SECONDS);
                assertTrue(page.nextAfter().isPresent());

                var plan = directory.prepareActivation(first, "state");
                directory.abortActivation(plan);
                var stale = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> directory.requestList(grant, session, 1, page.revision(),
                                page.nextAfter().orElseThrow(), TimeUnit.SECONDS.toNanos(3))
                                .toCompletableFuture().get(5, TimeUnit.SECONDS));
                assertInstanceOf(VirtualActorActivationRegistry.StaleDirectoryPageException.class,
                        stale.getCause());
                assertEquals(0, directory.pendingInspectionCount());
            }
        }
    }

    @Test
    void failedPhysicalWorkerIsReportedAsFailedWithSanitizedTerminalMetadata() throws Exception {
        try (ExecutorService pool = Executors.newSingleThreadExecutor();
             var runtime = runtime(pooled(pool))) {
            var supervisor = runtime.createSupervisor();
            try (var group = runtime.createActorGroup(supervisor);
                 var directory = new VirtualActorSupervisorDirectory(
                         runtime, supervisor, TENANT, 8, 4)) {
                ActorRuntime.BehaviorFactory<String> failing = factory ->
                        (message, context) -> { throw new IllegalStateException("private guest failure message"); };
                var actor = group.spawnWorker(ActorRuntime.ActorKind.SHARED,
                        IsolatePolicy.developer(), failing);
                actor.getReady().get(5, TimeUnit.SECONDS);
                var logical = directory.resolve("Cart", "failure-42");
                directory.bindActivation(logical, actor);
                actor.send("fail");
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> actor.done().get(5, TimeUnit.SECONDS));

                Object lastSession = new Object();
                var grant = directory.issueInspectionGrant(lastSession, TimeUnit.SECONDS.toNanos(4));
                VirtualActorSupervisorDirectory.Snapshot snapshot = null;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (System.nanoTime() < deadline) {
                    snapshot = directory.requestInspect(
                            grant, lastSession, logical.identity(), TimeUnit.SECONDS.toNanos(2))
                            .toCompletableFuture().get(3, TimeUnit.SECONDS).orElseThrow();
                    if (snapshot.lifecycle() != VirtualActorSupervisorDirectory.Lifecycle.TERMINATION_PENDING_RECONCILIATION)
                        break;
                }
                assertNotNull(snapshot);
                assertEquals(VirtualActorSupervisorDirectory.Lifecycle.FAILED, snapshot.lifecycle());
                assertEquals("java.lang.IllegalStateException",
                        snapshot.lastTerminalFailureClass().orElseThrow());
                assertTrue(snapshot.lastTerminalAt().isPresent());
                assertFalse(snapshot.lastTerminalFailureClass().orElseThrow()
                        .contains("private guest failure message"));
            }
        }
    }

    @Test
    void actorTurnCannotAcquireSupervisorVirtualActorDirectory() throws Exception {
        try (var runtime = new ActorRuntime()) {
            var supervisor = runtime.createSupervisor();
            var group = runtime.createActorGroup(supervisor);
            var denied = new java.util.concurrent.atomic.AtomicBoolean();
            ActorRuntime.BehaviorFactory<String> behavior = factory ->
                    (message, context) -> {
                        try {
                            supervisor.virtualActorDirectory(TENANT, 8, 4);
                        } catch (SecurityException expected) {
                            denied.set(true);
                        }
                    };
            var actor = group.spawnWorker(ActorRuntime.ActorKind.SHARED,
                    IsolatePolicy.developer(), behavior);
            actor.getReady().get(5, TimeUnit.SECONDS);
            actor.send("check");
            actor.drain().get(5, TimeUnit.SECONDS);
            assertTrue(denied.get());
            actor.stop();
            actor.done().get(5, TimeUnit.SECONDS);
            group.close();
        }
    }

}
