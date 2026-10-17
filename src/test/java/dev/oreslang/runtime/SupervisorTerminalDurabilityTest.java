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
}
