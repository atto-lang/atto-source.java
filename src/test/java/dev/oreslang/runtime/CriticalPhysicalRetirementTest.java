package dev.oreslang.runtime;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cancellation of a result Future is not proof that its critical task has
 * physically exited a carrier. A later invocation must remain excluded.
 */
final class CriticalPhysicalRetirementTest {
    @Test void cancelledResultCannotReleaseCriticalWhileCarrierStillExecutes() throws Exception {
        var gate = new CriticalGate();
        var entered = new CountDownLatch(1);
        var finishTurn = new CountDownLatch(1);
        var owner = gate.newTicket();

        class BlockingTurn implements OresScheduler.Task<Integer>,
                OresScheduler.PhysicalRetirementListener {
            @Override
            public OresScheduler.Step<Integer> resume(OresScheduler.Resume resume)
                    throws Exception {
                assertTrue(resume.initial());
                assertTrue(owner.enterAsync().isDone());
                entered.countDown();
                assertTrue(finishTurn.await(5, TimeUnit.SECONDS));
                return OresScheduler.done(1);
            }
            @Override
            public void onPhysicallyRetired() {
                owner.close();
            }
        }

        try (var scheduler = new OresScheduler(1)) {
            var result = scheduler.start(new BlockingTurn());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var follower = gate.newTicket();
            var followerAdmission = follower.enterAsync();
            try {
                assertTrue(result.cancel(false));
                assertTrue(owner.isHeld(),
                        "public cancellation must not revoke an active physical critical turn");
                assertFalse(followerAdmission.isDone());
            } finally {
                finishTurn.countDown();
            }
            followerAdmission.get(5, TimeUnit.SECONDS);
            assertTrue(follower.isHeld());
            follower.close();
            assertFalse(gate.held());
        }
    }

    @Test void retirementRevokesAnOwnedPendingChannelReceiveBeforeHandoff() throws Exception {
        var gate = new CriticalGate();
        var input = new ChannelRuntime.Channel<String>(1);
        var registered = new CountDownLatch(1);
        var owner = gate.newTicket();

        class Receiver implements OresScheduler.Task<String>,
                OresScheduler.PhysicalRetirementListener {
            private OresFuture<String> ownedReceive;
            @Override
            public OresScheduler.Step<String> resume(OresScheduler.Resume resume) {
                if (resume.initial()) {
                    owner.enterAsync();
                    ownedReceive = input.readAsync();
                    registered.countDown();
                    return OresScheduler.await(ownedReceive);
                }
                return OresScheduler.done((String) resume.value());
            }
            @Override
            public void onPhysicallyRetired() {
                if (ownedReceive != null) ownedReceive.cancel(false);
                owner.close();
            }
        }
        try (var scheduler = new OresScheduler(1)) {
            var result = scheduler.start(new Receiver());
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            var follower = gate.newTicket();
            var admission = follower.enterAsync();
            assertFalse(admission.isDone());
            assertTrue(result.cancel(false));
            admission.get(5, TimeUnit.SECONDS);
            assertTrue(follower.isHeld());
            follower.close();
            assertTrue(input.tryWrite("preserved-after-cancel"));
            assertEquals("preserved-after-cancel", input.tryRead().orElseThrow());
            assertFalse(gate.held());
        }
    }
    @Test void retirementRetractsOwnedPendingWriteBeforeHandoff() throws Exception {
        var gate = new CriticalGate();
        var channel = new ChannelRuntime.Channel<String>(1);
        assertTrue(channel.tryWrite("buffered"));
        var registered = new CountDownLatch(1);
        var owner = gate.newTicket();
        class Sender implements OresScheduler.Task<Void>,
                OresScheduler.PhysicalRetirementListener {
            private OresFuture<Void> send;
            @Override public OresScheduler.Step<Void> resume(OresScheduler.Resume resume) {
                if (!resume.initial()) throw new AssertionError("cancelled sender resumed");
                owner.enterAsync();
                send = channel.writeAsync("withdrawn");
                registered.countDown();
                return OresScheduler.await(send);
            }
            @Override public void onPhysicallyRetired() {
                if (send != null) send.cancel(false);
                owner.close();
            }
        }
        try (var scheduler = new OresScheduler(1)) {
            var result = scheduler.start(new Sender());
            assertTrue(registered.await(5, TimeUnit.SECONDS));
            var next = gate.newTicket();
            var admission = next.enterAsync();
            assertFalse(admission.isDone());
            assertTrue(result.cancel(false));
            admission.get(5, TimeUnit.SECONDS);
            try {
                assertTrue(next.isHeld());
                assertEquals("buffered", channel.tryRead().orElseThrow());
                assertTrue(channel.tryRead().isEmpty(), "retired write delivered late");
            } finally {
                next.close();
            }
            assertFalse(gate.held());
        }
    }

    @Test void retiringObserverDoesNotCancelSharedChannelProducer() throws Exception {
        var gate = new CriticalGate();
        var channel = new ChannelRuntime.Channel<String>(0);
        var sharedRead = channel.readAsync();
        var entered = new CountDownLatch(1);
        var owner = gate.newTicket();
        class Observer implements OresScheduler.Task<String>,
                OresScheduler.PhysicalRetirementListener {
            @Override public OresScheduler.Step<String> resume(OresScheduler.Resume resume) {
                if (!resume.initial()) throw new AssertionError("cancelled observer resumed");
                owner.enterAsync();
                entered.countDown();
                return OresScheduler.await(sharedRead);
            }
            @Override public void onPhysicallyRetired() { owner.close(); }
        }
        try (var scheduler = new OresScheduler(1)) {
            var result = scheduler.start(new Observer());
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var next = gate.newTicket();
            var admission = next.enterAsync();
            assertFalse(admission.isDone());
            assertTrue(result.cancel(false));
            admission.get(5, TimeUnit.SECONDS);
            try {
                assertFalse(sharedRead.isDone(), "observation must not cancel producer");
                assertTrue(channel.tryWrite("shared"));
                assertEquals("shared", sharedRead.get(5, TimeUnit.SECONDS));
            } finally {
                next.close();
                sharedRead.cancel(false);
            }
            assertFalse(gate.held());
        }
    }

}
