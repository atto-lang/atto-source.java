package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deadline failure is scheduler-owned, not a public Future.cancel() shortcut.
 * No physical critical permit release is allowed until carrier retirement.
 */
final class CriticalExecutionDeadlineTest {
    @Test void pendingChannelDeadlineFailsLogicalTaskWithoutCarrierParking() throws Exception {
        var gate = new CriticalGate();
        var channel = new ChannelRuntime.Channel<Integer>(0);
        var entered = new CountDownLatch(1);
        var owner = gate.newTicket();
        try (var timers = Executors.newSingleThreadScheduledExecutor();
             var scheduler = new OresScheduler(1)) {
            class CriticalTask implements OresScheduler.Task<Integer>,
                    OresScheduler.PhysicalRetirementListener {
                private OresFuture<Integer> read;
                @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume) {
                    if (!resume.initial()) {
                        throw new AssertionError("deadline must terminate pending task");
                    }
                    assertTrue(owner.enterAsync().isDone());
                    OresScheduler.DeadlineController controller =
                            OresScheduler.currentDeadlineController();
                    read = channel.readAsync();
                    timers.schedule(
                            () -> controller.failAtDeadline(
                                    new TimeoutException("critical execution deadline exceeded")),
                            30, TimeUnit.MILLISECONDS);
                    entered.countDown();
                    return OresScheduler.await(read);
                }
                @Override public void onPhysicallyRetired() {
                    if (read != null) read.cancel(false);
                    owner.close();
                }
            }
            var completion = scheduler.start(new CriticalTask());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var successor = gate.newTicket();
            var successorAdmission = successor.enterAsync();
            assertFalse(successorAdmission.isDone());
            var failure = assertThrows(ExecutionException.class,
                    () -> completion.get(5, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            successorAdmission.get(5, TimeUnit.SECONDS);
            assertTrue(successor.isHeld());
            successor.close();
            assertTrue(channel.tryWrite(99), "expired receive cannot steal later value");
            assertEquals(99, channel.tryRead().orElseThrow());
            assertFalse(gate.held());
        }
    }

    @Test void runningCriticalTurnIsNotPreemptedOrReleasedByDeadlineThread() throws Exception {
        var gate = new CriticalGate();
        var entered = new CountDownLatch(1);
        var exit = new CountDownLatch(1);
        var timerFired = new CountDownLatch(1);
        var owner = gate.newTicket();
        try (var timers = Executors.newSingleThreadScheduledExecutor();
             var scheduler = new OresScheduler(1)) {
            class RunningTask implements OresScheduler.Task<Integer>,
                    OresScheduler.PhysicalRetirementListener {
                @Override public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume)
                        throws Exception {
                    assertTrue(owner.enterAsync().isDone());
                    var controller = OresScheduler.currentDeadlineController();
                    timers.schedule(() -> {
                        controller.failAtDeadline(new TimeoutException("execution deadline"));
                        timerFired.countDown();
                    }, 10, TimeUnit.MILLISECONDS);
                    entered.countDown();
                    assertTrue(exit.await(5, TimeUnit.SECONDS));
                    return OresScheduler.done(1);
                }
                @Override public void onPhysicallyRetired() { owner.close(); }
            }
            var result = scheduler.start(new RunningTask());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var next = gate.newTicket();
            var admission = next.enterAsync();
            try {
                assertTrue(timerFired.await(5, TimeUnit.SECONDS));
                assertFalse(result.isDone(),
                        "deadline is not a hard interrupt of an executing carrier");
                assertTrue(owner.isHeld());
                assertFalse(admission.isDone());
            } finally {
                exit.countDown();
            }
            var failure = assertThrows(ExecutionException.class,
                    () -> result.get(5, TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class, failure.getCause());
            admission.get(5, TimeUnit.SECONDS);
            assertTrue(next.isHeld());
            next.close();
            assertFalse(gate.held());
        }
    }
}
