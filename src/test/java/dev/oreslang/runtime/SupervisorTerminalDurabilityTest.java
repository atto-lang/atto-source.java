package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.*;

final class SupervisorTerminalDurabilityTest {
    @Test
    void dispatcherRefusalRetainsExactTerminalState() {
        ActorRuntime.SupervisorId supervisorId = ActorRuntime.SupervisorId.create();
        ActorRuntime.ActorGroupId groupId = ActorRuntime.ActorGroupId.create();
        ActorRuntime.ActorId actorId = ActorRuntime.ActorId.create();
        SupervisorFailureControl lane = new SupervisorFailureControl(
                supervisorId, groupId,
                command -> { throw new RejectedExecutionException("CONTROL unavailable"); },
                1);
        lane.reserve(actorId);
        SupervisorFailureControl.Notice expected = new SupervisorFailureControl.Notice(
                supervisorId, groupId, actorId, Optional.empty(),
                SupervisorFailureControl.Kind.TERMINAL_FAILURE,
                "Failure", "boom");
        lane.publish(expected);

        assertThrows(IllegalStateException.class,
                () -> lane.install(ignored -> fail("must never run")));
        assertFalse(lane.healthy());
        assertEquals(1, lane.undelivered());
        assertEquals(1, lane.reserved());
        assertEquals(1, lane.queued());
        assertEquals(0, lane.availableReservations());
        assertEquals(expected, lane.pendingSnapshot().getFirst().notice());
    }

    @Test
    void terminalCreditStaysReservedUntilAsyncSupervisorDecision() throws Exception {
        try (var control = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            ActorRuntime.SupervisorId supervisorId = ActorRuntime.SupervisorId.create();
            ActorRuntime.ActorGroupId groupId = ActorRuntime.ActorGroupId.create();
            ActorRuntime.ActorId actorId = ActorRuntime.ActorId.create();
            ActorRuntime.ActorId replacement = ActorRuntime.ActorId.create();
            SupervisorFailureControl lane =
                    new SupervisorFailureControl(supervisorId, groupId, control, 1);
            var decision =
                    new java.util.concurrent.CompletableFuture<SupervisorProcess.Directive>();
            var resolved = new java.util.concurrent.CountDownLatch(1);
            SupervisorProcess process = new SupervisorProcess(
                    supervisorId, groupId, control, 1,
                    ignored -> decision,
                    resolution -> resolved.countDown());

            lane.reserve(actorId);
            lane.installAsync(process::accept);
            lane.publish(new SupervisorFailureControl.Notice(
                    supervisorId, groupId, actorId, Optional.empty(),
                    SupervisorFailureControl.Kind.TERMINAL_FAILURE,
                    "Failure", "fatal"));

            long deadline = System.nanoTime()
                    + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (process.admitted() != 1 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(1, process.admitted());
            assertEquals(1, lane.reserved());
            assertThrows(IllegalStateException.class, () -> lane.reserve(replacement));

            decision.complete(SupervisorProcess.Directive.ACKNOWLEDGE);
            assertTrue(resolved.await(5, java.util.concurrent.TimeUnit.SECONDS));

            deadline = System.nanoTime()
                    + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (lane.reserved() != 0 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertEquals(0, lane.reserved());
            assertEquals(0, lane.queued());
            lane.reserve(replacement);
            lane.releaseReservation(replacement);
            process.close();
        }
    }


    @Test
    void closingSupervisorBeforeResumeRetainsParentTerminalState() throws Exception {
        try (var control = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var supervisor = ActorRuntime.SupervisorId.create();
            var group = ActorRuntime.ActorGroupId.create();
            var actor = ActorRuntime.ActorId.create();
            var lane = new SupervisorFailureControl(supervisor, group, control, 1);
            var awaited =
                    new java.util.concurrent.CompletableFuture<SupervisorProcess.Directive>();
            var entered = new java.util.concurrent.CountDownLatch(1);
            var decisions = new java.util.concurrent.atomic.AtomicInteger();
            var process = new SupervisorProcess(
                    supervisor, group, control, 1,
                    ignored -> {
                        entered.countDown();
                        return awaited;
                    },
                    ignored -> decisions.incrementAndGet());

            lane.reserve(actor);
            lane.installAsync(process::accept);
            lane.publish(new SupervisorFailureControl.Notice(
                    supervisor, group, actor, Optional.empty(),
                    SupervisorFailureControl.Kind.TERMINAL_FAILURE,
                    "Failure", "close-race"));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));

            process.close();
            awaited.complete(SupervisorProcess.Directive.RESTART);

            long deadline = System.nanoTime()
                    + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (lane.failure().isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(2);
            }
            assertEquals(0, decisions.get());
            assertEquals(0, process.resolved());
            assertTrue(lane.failure().isPresent());
            assertEquals(1, lane.queued());
            assertEquals(1, lane.reserved());
            assertEquals(actor, lane.pendingSnapshot().getFirst().notice().actorId());
        }
    }

}
