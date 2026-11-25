package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Compiler-independent but REAL ActorRuntime parent-turn spawn/fail test.
 * Not compiled .atto guest, not FFI physical release proof. */
@Timeout(30)
final class SupervisorEscrowInheritedChildRaceIntegrationTest {
    private static final ConcurrentLinkedQueue<ActorRuntime.ActorRef<String>> CHILDREN =
            new ConcurrentLinkedQueue<>();
    private static final AtomicInteger CHILD_TURNS = new AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicReference<Throwable>
            GROUP_REJECTED = new java.util.concurrent.atomic.AtomicReference<>();
    private static final java.util.concurrent.atomic.AtomicReference<
            java.util.concurrent.CountDownLatch> GROUP_FINISHED =
            new java.util.concurrent.atomic.AtomicReference<>();

    @Test
    void parentCrashRetiresEveryAtomicallyRegisteredChildAndPreservesSibling()
            throws Exception {
        CHILDREN.clear();
        CHILD_TURNS.set(0);
        try (ActorRuntime runtime = new ActorRuntime();
             ExecutorService control = Executors.newSingleThreadExecutor()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(
                    runtime, bridge, control, ref -> false);
            ActorRuntime.ActorRef<String> parent = runtime.spawnPrivate(
                    ignored -> (message, turn) -> {
                        if ("panic".equals(message))
                            throw new IllegalStateException("simulated nested-supervisor panic");
                        ActorRuntime.ActorRef<String> child = turn.runtime().spawnPrivate(
                                nested -> (item, context) -> CHILD_TURNS.incrementAndGet());
                        CHILDREN.add(child);
                    });
            ActorRuntime.ActorRef<String> sibling = runtime.spawnPrivate(
                    ignored -> (message, turn) -> CHILD_TURNS.incrementAndGet());
            long parentNode = recovery.attach(parent, null);
            recovery.attach(sibling, null);
            parent.getReady().get(5, TimeUnit.SECONDS);
            sibling.getReady().get(5, TimeUnit.SECONDS);

            // Every parent message is an actual scheduler turn publishing
            // a physical ActorRef and its inherited terminal CONTROL slot.
            final int childCount = 16;
            for (int i = 0; i < childCount; i++) {
                parent.send("spawn");
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (CHILDREN.size() < childCount && System.nanoTime() < deadline) {
                Thread.yield();
            }
            assertEquals(childCount, CHILDREN.size(), "child turns did not finish");
            var prior = bridge.snapshot();
            long registered = prior.values().stream()
                    .filter(node -> node.parentId() == parentNode).count();
            assertEquals(childCount, registered,
                    "CONTROL reservation must precede every child publication");
            List<ActorRuntime.ActorRef<String>> children = new ArrayList<>(CHILDREN);
            for (var child : children) {
                child.send("accepted");
                child.getReady().get(5, TimeUnit.SECONDS);
            }
            parent.send("panic");
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> parent.done().get(5, TimeUnit.SECONDS));
            for (var child : children) {
                assertTrue(child.awaitTermination(5, TimeUnit.SECONDS),
                        "nested child escaped supervisor failure retirement");
                assertThrows(IllegalStateException.class, () -> child.send("stale"));
            }
            assertNull(recovery.controlFault(), "nested children absent from CONTROL registry");
            for (var node : bridge.snapshot().values()) {
                if (node.parentId() == parentNode) assertTrue(node.fenced());
            }
            sibling.send("healthy");
            sibling.drain().get(5, TimeUnit.SECONDS);
            assertTrue(sibling.isAlive(), "unrelated sibling must survive");
            assertTrue(CHILD_TURNS.get() >= 1, "real guest-behavior turns not executed");
            assertEquals(1, bridge.pendingCount(), "supervisor failure notice missing");
        } finally {
            CHILDREN.clear();
            CHILD_TURNS.set(0);
        }
    }

    @Test
    void hostChildAttachmentRaceWithAncestorPanicNeverLosesCONTROLHandle()
            throws Exception {
        // Raced host attachment used to publish the ledger node before the
        // cancellation coordinator had the corresponding physical ActorRef.
        // A losing attach must not leave a ghost CONTROL node.
        for (int trial = 0; trial < 12; trial++) {
            try (ActorRuntime runtime = new ActorRuntime();
                 ExecutorService control = Executors.newSingleThreadExecutor();
                 ExecutorService contestants = Executors.newFixedThreadPool(2)) {
                var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
                var recovery = new SupervisorEscrowSubtreeRecoveryV1(
                        runtime, bridge, control, ref -> false);
                ActorRuntime.ActorRef<String> parent = runtime.spawnPrivate(
                        ignored -> (message, turn) -> {
                            throw new IllegalStateException("racing supervisor PANIC");
                        });
                ActorRuntime.ActorRef<String> child = runtime.spawnPrivate(
                        ignored -> (message, turn) -> { });
                long parentNode = recovery.attach(parent, null);
                parent.getReady().get(5, TimeUnit.SECONDS);
                var raceStart = new java.util.concurrent.CountDownLatch(1);
                var attachResult = contestants.submit(() -> {
                    raceStart.await();
                    try {
                        recovery.attach(child, parent);
                        return true;
                    } catch (IllegalStateException denied) {
                        return false;
                    }
                });
                var crashResult = contestants.submit(() -> {
                    raceStart.await();
                    parent.send("panic");
                    return null;
                });
                raceStart.countDown();
                crashResult.get(5, TimeUnit.SECONDS);
                boolean attached = attachResult.get(5, TimeUnit.SECONDS);
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> parent.done().get(5, TimeUnit.SECONDS));
                int expectedNodes = attached ? 3 : 2;
                assertEquals(expectedNodes, bridge.snapshot().size(),
                        "failed host attach left a ghost CONTROL node");
                if (attached) {
                    assertTrue(child.awaitTermination(5, TimeUnit.SECONDS),
                            "attached host child escaped supervisor shutdown");
                    assertEquals(1, bridge.snapshot().values().stream()
                            .filter(n -> n.parentId() == parentNode).count());
                }
                assertNull(recovery.controlFault(),
                        "ancestor cancellation saw CONTROL without a registered ActorRef");
            }
        }
    }

    @Test
    void rejectLateRecoveryInstallationAndDuplicateHostAttachment()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ExecutorService control = Executors.newSingleThreadExecutor()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            ActorRuntime.ActorRef<String> actor = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            bridge.attach(actor, null);
            assertEquals(2, bridge.snapshot().size());
            assertThrows(IllegalStateException.class, () -> bridge.attach(actor, null));
            assertEquals(2, bridge.snapshot().size(),
                    "duplicate host CONTROL registration left a ghost ledger node");
            assertThrows(IllegalStateException.class,
                    () -> new SupervisorEscrowSubtreeRecoveryV1(
                            runtime, bridge, control, ref -> false),
                    "late recovery coordinator must not inherit untracked actors");
        }
    }

    @Test
    void failedManagedGroupAdmissionReleasesUnpublishedActorMemoryAndOutput()
            throws Exception {
        var done = new java.util.concurrent.CountDownLatch(1);
        GROUP_FINISHED.set(done);
        GROUP_REJECTED.set(null);
        try (ActorRuntime runtime = new ActorRuntime();
             ExecutorService control = Executors.newSingleThreadExecutor()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(
                    runtime, bridge, control, ref -> false);
            // One managed-worker slot: the parent occupies it. Its next
            // nested child can build an ActorCell but cannot publish into
            // the inherited group, exercising full post-constructor rollback.
            var limited = new IsolatePolicy(
                    java.util.Set.of(IsolatePolicy.Capability.ACTOR_SPAWN),
                    16L * 1024 * 1024, 1, java.time.Duration.ofSeconds(10));
            ActorRuntime.ActorRef<String> parent = runtime.spawnPrivate(
                    limited, ignored -> (message, turn) -> {
                        try {
                            ActorRuntime.ActorGroup group =
                                    turn.runtime().createActorGroup();
                            group.manageWorker(turn.self());
                            turn.runtime().spawnPrivate(
                                    nested -> (item, context) -> { });
                        } catch (Throwable rejected) {
                            GROUP_REJECTED.set(rejected);
                        } finally {
                            GROUP_FINISHED.get().countDown();
                        }
                    });
            long parentNode = recovery.attach(parent, null);
            parent.getReady().get(5, TimeUnit.SECONDS);
            parent.send("spawn with exhausted inherited group");
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, GROUP_REJECTED.get(),
                    "inherited managed-worker limit must fail admission");
            assertEquals(1, runtime.actorCount(),
                    "failed nested spawn leaked an actor slot");
            assertEquals(2, bridge.snapshot().size(),
                    "failed child left a CONTROL node after rollback");
            assertTrue(bridge.snapshot().get(parentNode).alive(),
                    "parent may recover from a rejected child publication");
            var streams = ActorRuntime.class.getDeclaredField("actorOutputStreams");
            streams.setAccessible(true);
            assertEquals(1, ((java.util.Set<?>)streams.get(runtime)).size(),
                    "aborted ActorCell kept its registered output stream");
            var privateBytes = ActorRuntime.class.getDeclaredField("privateMemoryBytes");
            privateBytes.setAccessible(true);
            assertEquals(0L,
                    ((java.util.concurrent.atomic.AtomicLong)
                            privateBytes.get(runtime)).get(),
                    "unpublished actor retained aggregate private heap reservations");
        } finally {
            GROUP_REJECTED.set(null);
            GROUP_FINISHED.compareAndSet(done, null);
        }
    }

    @Test
    void staleAncestorGenerationCannotReserveAChild() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ExecutorService control = Executors.newSingleThreadExecutor()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(
                    runtime, bridge, control, ref -> false);
            ActorRuntime.ActorRef<String> parent = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            ActorRuntime.ActorRef<String> provisional = runtime.spawnPrivate(
                    ignored -> (message, turn) -> { });
            long parentNode = recovery.attach(parent, null);
            int initialNodes = bridge.snapshot().size();
            synchronized (bridge) {
              synchronized (bridge.ledgerPublicationMonitorFromRuntime()) {
                assertThrows(IllegalStateException.class,
                        () -> bridge.reserveInheritedChildFromRuntime(
                                provisional, parentNode, parent.generation() + 1));
              }
            }
            assertEquals(initialNodes, bridge.snapshot().size(),
                    "stale-generation reservation leaked a CONTROL node");
            long freshId = recovery.attach(provisional, parent);
            assertEquals(parentNode, bridge.snapshot().get(freshId).parentId());
        }
    }

    @Test
    void nestedSpawnWithoutRecoveryFailsBeforeActorSlotReservation() throws Exception {
        var done = new java.util.concurrent.CountDownLatch(1);
        GROUP_FINISHED.set(done);
        GROUP_REJECTED.set(null);
        try (ActorRuntime runtime = new ActorRuntime()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            ActorRuntime.ActorRef<String> parent = runtime.spawnPrivate(
                    ignored -> (message, turn) -> {
                        try {
                            turn.runtime().spawnPrivate(nested -> (item, context) -> { });
                        } catch (Throwable denied) {
                            GROUP_REJECTED.set(denied);
                        } finally {
                            GROUP_FINISHED.get().countDown();
                        }
                    });
            bridge.attach(parent, null);
            parent.send("nested spawn");
            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, GROUP_REJECTED.get());
            assertTrue(GROUP_REJECTED.get().getMessage().contains("CONTROL recovery coordinator"));
            assertEquals(1, runtime.actorCount());
            assertEquals(2, bridge.snapshot().size());
        } finally {
            GROUP_REJECTED.set(null);
            GROUP_FINISHED.compareAndSet(done, null);
        }
    }

    @Test
    void concurrentLedgerSnapshotCannotObserveRolledBackReservation() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             ExecutorService control = Executors.newSingleThreadExecutor();
             ExecutorService observer = Executors.newSingleThreadExecutor()) {
            var bridge = new SupervisorFailureEscrowRuntimeBridgeV1(runtime);
            var recovery = new SupervisorEscrowSubtreeRecoveryV1(runtime, bridge, control, ref -> false);
            ActorRuntime.ActorRef<String> parent = runtime.spawnPrivate(ignored -> (m, t) -> { });
            ActorRuntime.ActorRef<String> provisional = runtime.spawnPrivate(ignored -> (m, t) -> { });
            long parentId = recovery.attach(parent, null);
            long candidate;
            java.util.concurrent.Future<java.util.Map<Long, SupervisorFailureEscrowV1.NodeStatus>> read;
            var entered = new java.util.concurrent.CountDownLatch(1);
            synchronized (bridge) {
                synchronized (bridge.ledgerPublicationMonitorFromRuntime()) {
                    candidate = bridge.reserveInheritedChildFromRuntime(
                            provisional, parentId, parent.generation());
                    try {
                        read = observer.submit(() -> {
                            entered.countDown();
                            return bridge.snapshot();
                        });
                        assertTrue(entered.await(5, TimeUnit.SECONDS));
                        assertThrows(java.util.concurrent.TimeoutException.class,
                                () -> read.get(100, TimeUnit.MILLISECONDS));
                    } finally {
                        bridge.rollbackInheritedChildFromRuntime(provisional, candidate, parentId);
                    }
                }
            }
            assertFalse(read.get(5, TimeUnit.SECONDS).containsKey(candidate));
            assertEquals(2, bridge.snapshot().size());
        }
    }
}
