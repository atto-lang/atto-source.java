package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Partial shutdown admission must not strand watchdog observer completion. */
final class SupervisorShutdownDeadlineFenceTest {
    @Test
    void unadmittedRootIsMarkedCoordinatorFailedNotTimedOut() {
        var initial = SupervisorLifecycleFsm.State.initial();
        var result = SupervisorLifecycleFsm.reduce(
                initial, SupervisorShutdownTree.deadlineEventFor(initial));
        assertTrue(result.failed());
        assertFalse(result.deadlineExpired());
        assertEquals(SupervisorLifecycleFsm.Phase.RUNNING, result.phase());
        assertFalse(result.logicallyStopped());
    }

    @Test
    void unadmittedRestartingNodeIsMarkedCoordinatorFailed() {
        var initial = SupervisorLifecycleFsm.State.initial();
        var restarting = SupervisorLifecycleFsm.reduce(
                initial, new SupervisorLifecycleFsm.RestartRequested(initial.epoch()));
        var result = SupervisorLifecycleFsm.reduce(
                restarting, SupervisorShutdownTree.deadlineEventFor(restarting));
        assertTrue(result.failed());
        assertFalse(result.deadlineExpired());
        assertEquals(SupervisorLifecycleFsm.Phase.RESTARTING, result.phase());
    }

    @Test
    void admittedShutdownTracksDeadlineWithoutForgingTermination() {
        var initial = SupervisorLifecycleFsm.State.initial();
        var shuttingDown = SupervisorLifecycleFsm.reduce(initial,
                new SupervisorLifecycleFsm.ShutdownRequested(initial.epoch(), Set.of()));
        var timedOut = SupervisorLifecycleFsm.reduce(
                shuttingDown, SupervisorShutdownTree.deadlineEventFor(shuttingDown));
        assertEquals(SupervisorLifecycleFsm.Phase.BEFORE_HOOK, timedOut.phase());
        assertTrue(timedOut.deadlineExpired());
        assertFalse(timedOut.logicallyStopped());
        assertFalse(timedOut.physicalReleased());
        assertSame(timedOut, SupervisorLifecycleFsm.reduce(
                timedOut, SupervisorShutdownTree.deadlineEventFor(timedOut)));
    }
}
