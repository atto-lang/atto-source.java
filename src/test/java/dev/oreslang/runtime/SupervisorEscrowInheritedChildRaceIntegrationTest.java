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
                assertThrows(IllegalStateException.class,
                        () -> bridge.reserveInheritedChildFromRuntime(
                                provisional, parentNode, parent.generation() + 1));
            }
            assertEquals(initialNodes, bridge.snapshot().size(),
                    "stale-generation reservation leaked a CONTROL node");
            long freshId = recovery.attach(provisional, parent);
            assertEquals(parentNode, bridge.snapshot().get(freshId).parentId());
        }
    }
}
