package dev.oreslang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
final class SupervisorLifecycleFsmIntegrationTest {
    @Test
    void snapshotShowsEveryNodeQuiescingThenLogicallyStoppedNotPhysicallyReclaimed()
            throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var control = Executors.newSingleThreadExecutor()) {
            var hold = new OresFuture<Void>();
            var entered = new CountDownLatch(1);
            var tree = new SupervisorShutdownTree(runtime, control);
            var root = tree.setRoot("root", runtime.createActorGroup(),
                    new SupervisorShutdownTree.Hooks() {
                        @Override public OresFuture<Void> onShutdown(
                                SupervisorShutdownTree.Reason reason) {
                            entered.countDown();
                            return hold;
                        }
                    });
            var child = root.addChild("child", runtime.createActorGroup(),
                    new SupervisorShutdownTree.Hooks() {});
            var active = tree.status();
            assertEquals(2, active.nodes().size());
            assertTrue(active.nodes().stream().allMatch(n -> n.lifecycle().admissionsOpen()));

            var done = tree.shutdown(SupervisorShutdownTree.Reason.REQUESTED);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            var closing = tree.status();
            assertFalse(closing.allLogicallyStopped());
            assertFalse(closing.allPhysicallyReclaimed());
            assertTrue(closing.nodes().stream().allMatch(n ->
                    n.lifecycle().phase() == SupervisorLifecycleFsm.Phase.BEFORE_HOOK));
            assertFalse(closing.nodes().stream().anyMatch(n -> n.lifecycle().admissionsOpen()));
            assertEquals(Set.of(root.supervisorId(), child.supervisorId()),
                    closing.nodes().stream().map(SupervisorShutdownTree.NodeStatus::id)
                            .collect(java.util.stream.Collectors.toSet()));

            hold.completeFromRuntime(null);
            done.get(5, TimeUnit.SECONDS);
            var stopped = tree.status();
            assertTrue(stopped.allLogicallyStopped());
            assertFalse(stopped.allPhysicallyReclaimed(),
                    "ActorRef.done does not verify every FFM loan or arena release");
            assertFalse(stopped.hasFailures());
            assertEquals(2, stopped.nodes().stream()
                    .filter(n -> n.lifecycle().phase() == SupervisorLifecycleFsm.Phase.STOPPED)
                    .count());
        }
    }

    @Test
    void timeoutRetainsPendingLifecycleButMarksDeadline() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime();
             var control = Executors.newSingleThreadExecutor();
             var watchdog = Executors.newSingleThreadScheduledExecutor()) {
            OresFuture<Void> waiting = new OresFuture<>();
            CountDownLatch entered = new CountDownLatch(1);
            var tree = new SupervisorShutdownTree(runtime, control);
            tree.setRoot("root", runtime.createActorGroup(),
                    new SupervisorShutdownTree.Hooks() {
                        @Override public OresFuture<Void> onShutdown(
                                SupervisorShutdownTree.Reason reason) {
                            entered.countDown();
                            return waiting;
                        }
                    });
            var bounded = tree.shutdown(SupervisorShutdownTree.Reason.REQUESTED,
                    java.time.Duration.ofMillis(100), watchdog);
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> bounded.get(5, TimeUnit.SECONDS));
            var snapshot = tree.status();
            assertTrue(snapshot.hasDeadlineOverruns());
            assertFalse(snapshot.allLogicallyStopped());
            assertFalse(snapshot.allPhysicallyReclaimed());
            waiting.completeFromRuntime(null);
            tree.shutdown(SupervisorShutdownTree.Reason.REQUESTED)
                    .get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void independentPureReducersRejectStaleGenerationCompletionAndNoEarlyReclaim() {
        var s = SupervisorLifecycleFsm.State.initial();
        s = SupervisorLifecycleFsm.reduce(s,
                new SupervisorLifecycleFsm.ActorsFinished(1, false));
        s = SupervisorLifecycleFsm.reduce(s,
                new SupervisorLifecycleFsm.PhysicalRelease(1));
        s = SupervisorLifecycleFsm.reduce(s,
                new SupervisorLifecycleFsm.RestartRequested(1));
        s = SupervisorLifecycleFsm.reduce(s,
                new SupervisorLifecycleFsm.RestartCommitted(1));
        assertEquals(2, s.epoch());
        assertSame(s, SupervisorLifecycleFsm.reduce(s,
                new SupervisorLifecycleFsm.ActorsFinished(1, false)));
        assertFalse(s.physicalReleased());
        assertTrue(s.admissionsOpen());
    }
}
