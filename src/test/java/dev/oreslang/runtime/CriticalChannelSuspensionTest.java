package dev.oreslang.runtime;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Host scheduler refinement of the source critical/channel contract:
 * critical exclusion survives channel suspension without monopolizing a carrier.
 * Full guest parser/borrow lowering and Native Image remain separate gates.
 */
final class CriticalChannelSuspensionTest {
    @Test void criticalReceiveSuspendsButAnotherTaskCanSendOnOneCarrier() throws Exception {
        CriticalGate gate = new CriticalGate();
        var input = new ChannelRuntime.Channel<String>(0);
        var registered = new CountDownLatch(1);
        try (var timer = Executors.newSingleThreadScheduledExecutor();
             var scheduler = new OresScheduler(1)) {
            var owner = gate.newTicket();
            OresFuture<String> result = scheduler.start(resume -> {
                if (resume.initial()) {
                    assertTrue(owner.enterAsync().isDone());
                    var pending = input.readAsyncWithin(Duration.ofSeconds(2), timer);
                    registered.countDown();
                    return OresScheduler.await(pending);
                }
                if (resume.failure() != null) {
                    throw new IllegalStateException("critical read failed", resume.failure());
                }
                return OresScheduler.done((String) resume.value());
            });
            owner.releaseOnCompletion(result);
            assertTrue(registered.await(5, TimeUnit.SECONDS));

            var waiting = gate.newTicket();
            var waitingAdmission = waiting.enterAsync();
            assertFalse(waitingAdmission.isDone(),
                    "conflicting critical invocation must remain queued");

            // This makes progress despite the first critical task awaiting
            // an unbuffered channel on the same single-carrier scheduler.
            OresFuture<Boolean> producer = scheduler.startSync(
                    () -> input.tryWrite("delivered"));
            assertTrue(producer.get(5, TimeUnit.SECONDS));
            assertEquals("delivered", result.get(5, TimeUnit.SECONDS));
            waitingAdmission.get(5, TimeUnit.SECONDS);
            assertTrue(waiting.isHeld());
            waiting.close();
            assertFalse(gate.held());
        }
    }

    @Test void timedChannelWaitReleasesCriticalOnlyAfterTaskExits() throws Exception {
        CriticalGate gate = new CriticalGate();
        var channel = new ChannelRuntime.Channel<Integer>(0);
        var waiting = new CountDownLatch(1);
        var sawFailureInOwnerTurn = new AtomicBoolean(false);
        try (var timer = Executors.newSingleThreadScheduledExecutor();
             var scheduler = new OresScheduler(1)) {
            var owner = gate.newTicket();
            OresFuture<Integer> task = scheduler.start(resume -> {
                if (resume.initial()) {
                    owner.enterAsync();
                    var read = channel.readAsyncWithin(Duration.ofMillis(30), timer);
                    waiting.countDown();
                    return OresScheduler.await(read);
                }
                if (resume.failure() != null) {
                    assertTrue(owner.isHeld(),
                            "critical ticket must remain owned during failure cleanup");
                    sawFailureInOwnerTurn.set(true);
                    throw new IllegalStateException("channel deadline", resume.failure());
                }
                return OresScheduler.done((Integer) resume.value());
            });
            owner.releaseOnCompletion(task);
            assertTrue(waiting.await(5, TimeUnit.SECONDS));

            var successor = gate.newTicket();
            var admission = successor.enterAsync();
            assertFalse(admission.isDone());
            var failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> task.get(5, TimeUnit.SECONDS));
            assertEquals("channel deadline", failure.getCause().getMessage());
            assertTrue(sawFailureInOwnerTurn.get());
            admission.get(5, TimeUnit.SECONDS);
            assertTrue(successor.isHeld());
            successor.close();
            assertTrue(channel.tryRead().isEmpty());
            assertFalse(gate.held());
        }
    }
}
