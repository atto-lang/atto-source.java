package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Terminal supervisor CONTROL failures retain exact bounded state for escalation. */
@Timeout(20)
final class SupervisorFailureRetentionTest {
    private static SupervisorFailureControl.Notice notice(
            ActorRuntime.SupervisorId supervisor,
            ActorRuntime.ActorGroupId group) {
        return new SupervisorFailureControl.Notice(
                supervisor, group, ActorRuntime.ActorId.create(),
                Optional.empty(), SupervisorFailureControl.Kind.TERMINAL_FAILURE,
                "java.lang.IllegalStateException", "bounded diagnostic");
    }

    @Test
    void dispatchFailureRetainsAllControlNoticesAfterAccounting() {
        var supervisor = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        var lane = new SupervisorFailureControl(supervisor, group, Runnable::run, 4);
        lane.publish(notice(supervisor, group));
        lane.publish(notice(supervisor, group));
        assertEquals(2, lane.queued());
        assertThrows(IllegalStateException.class,
                () -> lane.install(n -> fail("inline executor must never dispatch trusted CONTROL")),
                "failed CONTROL scheduling must reject handler installation");
        assertTrue(lane.failure().isPresent());
        assertEquals(2, lane.undelivered());
        assertEquals(0, lane.queued(),
                "failed CONTROL cannot permanently retain abandoned message payloads");
        lane.publish(notice(supervisor, group));
        assertEquals(3, lane.undelivered());
        assertEquals(0, lane.queued());
    }

    @Test
    void failedHandlerReleasesAllQueuedNotices() throws Exception {
        var supervisor = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var lane = new SupervisorFailureControl(supervisor, group, executor, 4);
            lane.publish(notice(supervisor, group));
            lane.publish(notice(supervisor, group));
            lane.install(n -> { throw new IllegalStateException("policy failure"); });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (lane.failure().isEmpty() && System.nanoTime() < deadline)
                Thread.sleep(2);
            assertTrue(lane.failure().orElse("").contains("policy failure"));
            assertEquals(2, lane.undelivered());
            assertEquals(0, lane.queued());
        }
    }

    @Test
    void failedSupervisorProcessDoesNotRetainQueuedMailbox() {
        var supervisor = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        try (var process = new SupervisorProcess(
                supervisor, group, Runnable::run, 4,
                n -> CompletableFuture.completedFuture(SupervisorProcess.Directive.RESTART),
                resolution -> fail("inline executor cannot make restart decisions"))) {
            assertThrows(IllegalStateException.class,
                    () -> process.accept(notice(supervisor, group)),
                    "synchronous CONTROL scheduling failure must reject admission");
            assertFalse(process.healthy());
            assertTrue(process.failure().isPresent());
            assertEquals(1, process.undelivered());
            assertEquals(0, process.queued(),
                    "failed supervisor process must not retain undeliverable mail");
            assertEquals(0, process.resolved(), "no fabricated restart receipt");
        }
    }
    @Test
    void supervisorLaneCannotReportHealthyAfterChildProcessRejectsDispatch() throws Exception {
        var supervisor = ActorRuntime.SupervisorId.create();
        var group = ActorRuntime.ActorGroupId.create();
        try (var pool = Executors.newSingleThreadExecutor();
             var process = new SupervisorProcess(
                    supervisor, group, Runnable::run, 2,
                    n -> CompletableFuture.completedFuture(SupervisorProcess.Directive.STOP),
                    r -> fail("failed process must not resolve directives"))) {
            var control = new SupervisorFailureControl(supervisor, group, pool, 2);
            control.install(process::accept);
            control.publish(notice(supervisor, group));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (control.failure().isEmpty() && System.nanoTime() < deadline)
                Thread.sleep(2);
            assertTrue(control.failure().isPresent(),
                    "upstream lane must observe failed handoff");
            assertEquals(1, control.undelivered());
            assertEquals(0, control.queued());
            assertEquals(1, process.undelivered());
            assertEquals(0, process.queued());
            assertEquals(0, process.resolved());
            assertFalse(control.healthy());
            assertFalse(process.healthy());
        }
    }

}
