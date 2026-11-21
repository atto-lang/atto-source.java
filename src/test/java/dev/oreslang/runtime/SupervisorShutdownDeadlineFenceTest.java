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
        assertFalse(result.admissionsOpen(), "failed CONTROL cannot admit fresh actors");
        assertFalse(result.restartPermitted(), "failed CONTROL cannot restart");
        assertThrows(IllegalStateException.class, () ->
                SupervisorLifecycleFsm.reduce(result,
                        new SupervisorLifecycleFsm.RestartRequested(result.epoch())));
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
    void unadmittedActorCompletionIsNotAShutdownReceipt() {
        var active = SupervisorLifecycleFsm.State.initial();
        assertThrows(IllegalStateException.class, () ->
                SupervisorLifecycleFsm.reduce(active,
                        new SupervisorLifecycleFsm.ActorsFinished(active.epoch(), false)));
        assertThrows(IllegalStateException.class, () ->
                SupervisorLifecycleFsm.reduce(active,
                        new SupervisorLifecycleFsm.PhysicalRelease(active.epoch())));
        assertFalse(active.actorsDone());
        assertFalse(active.physicalReleased());

        // An actor that retired *after* shutdown was admitted may still
        // finish before the pre-hook; retaining that receipt is intentional.
        var admitted = SupervisorLifecycleFsm.reduce(active,
                new SupervisorLifecycleFsm.ShutdownRequested(active.epoch(), Set.of()));
        var retired = SupervisorLifecycleFsm.reduce(admitted,
                new SupervisorLifecycleFsm.ActorsFinished(active.epoch(), false));
        assertTrue(retired.actorsDone());
        assertEquals(SupervisorLifecycleFsm.Phase.BEFORE_HOOK, retired.phase());
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
