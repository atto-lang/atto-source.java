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
}
