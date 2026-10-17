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

}
