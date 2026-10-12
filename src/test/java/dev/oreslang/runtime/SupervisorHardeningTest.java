package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Adversarial admission and lifecycle races for host supervisor CONTROL. */
@Timeout(20)
final class SupervisorHardeningTest {
    private static SupervisorFailureControl.Notice notice(
            ActorRuntime.SupervisorId supervisor, ActorRuntime.ActorGroupId group) {
        return new SupervisorFailureControl.Notice(
                supervisor, group, ActorRuntime.ActorId.create(),
                Optional.empty(), SupervisorFailureControl.Kind.TERMINAL_FAILURE,
                "Fault", "terminal");
    }

    @Test
    void inlineExecutorCannotRunFailureHandlerOrDeadlockOnAdmission() {
        var supervisor = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        AtomicInteger calls = new AtomicInteger();
        SupervisorFailureControl lane = new SupervisorFailureControl(
                supervisor, group, Runnable::run, 2);
        lane.install(n -> calls.incrementAndGet());
        lane.publish(notice(supervisor, group));
        assertEquals(0, calls.get(), "inline control handler crossed a child carrier");
        assertFalse(lane.healthy());
        assertTrue(lane.failure().isPresent());
        assertEquals(1, lane.undelivered());
    }

    @Test
    void inlineExecutorCannotRunSupervisorPolicyOrDecision() {
        var supervisor = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        AtomicInteger calls = new AtomicInteger();
        try (SupervisorProcess process = new SupervisorProcess(
                supervisor, group, Runnable::run, 2,
                n -> {
                    calls.incrementAndGet();
                    return CompletableFuture.completedFuture(
                            SupervisorProcess.Directive.RESTART);
                },
                r -> calls.incrementAndGet())) {
            assertThrows(IllegalStateException.class,
                    () -> process.accept(notice(supervisor, group)),
                    "inline CONTROL dispatch rejection must signal failed admission");
            assertEquals(0, calls.get());
            assertFalse(process.healthy());
            assertTrue(process.failure().orElse("").contains("inline"));
            assertEquals(1, process.undelivered());
            assertEquals(0, process.resolved(),
                    "failed CONTROL must never be marked restarted");
        }
    }

    @Test
    void unmanagedParentWithLiveChildCannotBeAdoptedWithoutAdoptingChild() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> root = runtime.spawnPrivate(
                    factory -> (message, actor) ->
                            actor.runtime().spawnPrivate(
                                    childFactory -> (body, childContext) -> { }));
            root.getReady().get(5, TimeUnit.SECONDS);
            root.send("spawn");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (root.childIds().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(root.childIds().isEmpty());
            assertThrows(IllegalStateException.class, () -> group.manageWorker(root),
                    "admitting only the parent would strand its descendants");
            assertEquals(0, group.workerCount());
            assertTrue(root.supervisorId().isEmpty());
            root.cancel();
            assertTrue(root.awaitTermination(5, TimeUnit.SECONDS));
            group.close();
        }
    }

    @Test
    void managedParentWithLiveChildCannotTransferOutOfSupervision() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            ActorRuntime.ActorGroup group = runtime.createActorGroup();
            ActorRuntime.ActorRef<String> root = group.spawnWorker(
                    ActorRuntime.ActorKind.PRIVATE,
                    IsolatePolicy.developer(),
                    factory -> (message, actor) ->
                            actor.runtime().spawnPrivate(
                                    childFactory -> (body, childContext) -> { }));
            root.getReady().get(5, TimeUnit.SECONDS);
            root.send("spawn");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (root.childIds().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertFalse(root.childIds().isEmpty());
            assertThrows(IllegalStateException.class, () -> group.releaseWorker(root),
                    "release must synchronize with and reject a live child");
            assertEquals(2, group.workerCount());
            assertEquals(group.supervisorId(), root.supervisorId().orElseThrow());
            group.close();
            assertTrue(root.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
