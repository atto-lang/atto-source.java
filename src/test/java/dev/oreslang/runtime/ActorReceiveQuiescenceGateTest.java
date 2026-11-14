package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real Java lifecycle admission/quiescence gate regression, NOT evidence that
 * current ActorRuntime can dispatch concurrent source receive() calls.
 * Full concurrent guest lowering and native I/O pin retirement are separate.
 */
@Timeout(15)
final class ActorReceiveQuiescenceGateTest {
    private static final String TYPE = "Worker";

    private static ActorRuntime.ActorInboxMail<String> mail(ActorRuntime.ActorId id) {
        return new ActorRuntime.ActorInboxMail<>(id, Optional.empty(), 0L, "job");
    }

    private static ActorSourceLifecycleGate initialized(ActorRuntime.ActorId id) {
        var gate = new ActorSourceLifecycleGate(id, TYPE);
        gate.beginInit(id, TYPE);
        gate.commitInit(() -> id);
        return gate;
    }

    @Test
    void twoAdmittedReceivesMustBothExitBeforeTeardownOrRetirement() throws Exception {
        var id = ActorRuntime.ActorId.create();
        var gate = initialized(id);
        var state = (ActorRuntime.ActorOwnedGuestState) () -> id;
        var ready = new CountDownLatch(2);
        var releaseFirst = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var endedFirst = new CountDownLatch(1);
        var endedSecond = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();

        Thread first = Thread.ofVirtual().name("receive-a").start(
                () -> receiver(gate, id, state, ready, releaseFirst, endedFirst, failure));
        Thread second = Thread.ofVirtual().name("receive-b").start(
                () -> receiver(gate, id, state, ready, releaseSecond, endedSecond, failure));
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS), "two receipts never admitted");
            assertEquals(2, gate.activeReceives());
            gate.sealStop(true);
            assertEquals(ActorSourceLifecycleGate.Phase.STOPPING_GRACEFUL, gate.phase());

            assertThrows(IllegalStateException.class, () -> gate.enterReceive(
                    id, TYPE, state, mail(id)), "STOP cannot admit a third receive");
            assertThrows(IllegalStateException.class,
                    () -> gate.beginTeardown(id, TYPE, state));
            assertThrows(IllegalStateException.class, gate::close,
                    "physical reclamation cannot precede receiver quiescence");
            assertEquals(2, gate.activeReceives());

            releaseFirst.countDown();
            assertTrue(endedFirst.await(5, TimeUnit.SECONDS));
            assertEquals(1, gate.activeReceives());
            assertThrows(IllegalStateException.class,
                    () -> gate.beginTeardown(id, TYPE, state));

            releaseSecond.countDown();
            assertTrue(endedSecond.await(5, TimeUnit.SECONDS));
            assertEquals(0, gate.activeReceives());
            assertNull(failure.get(), () -> "receiver failed: " + failure.get());

            gate.beginTeardown(id, TYPE, state);
            assertEquals(ActorSourceLifecycleGate.Phase.TEARING_DOWN, gate.phase());
            assertThrows(IllegalStateException.class,
                    () -> gate.beginTeardown(id, TYPE, state),
                    "teardown must be once-only");
            gate.close();
            assertEquals(ActorSourceLifecycleGate.Phase.CLOSED, gate.phase());
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            first.join(5_000);
            second.join(5_000);
            assertFalse(first.isAlive(), "first receive leaked");
            assertFalse(second.isAlive(), "second receive leaked");
        }
    }

    private static void receiver(
            ActorSourceLifecycleGate gate,
            ActorRuntime.ActorId id,
            ActorRuntime.ActorOwnedGuestState state,
            CountDownLatch ready,
            CountDownLatch release,
            CountDownLatch ended,
            AtomicReference<Throwable> failure) {
        ActorSourceLifecycleGate.ReceiveLease lease = null;
        try {
            lease = gate.enterReceive(id, TYPE, state, mail(id));
            ready.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("test did not release receive");
            }
        } catch (Throwable problem) {
            failure.compareAndSet(null, problem);
        } finally {
            try {
                if (lease != null) lease.close();
            } finally {
                ended.countDown();
            }
        }
    }

    @Test
    void leaseIsIdempotentAndReleaseSurvivesThrownGuestCallback() {
        var id = ActorRuntime.ActorId.create();
        var gate = initialized(id);
        var state = (ActorRuntime.ActorOwnedGuestState) () -> id;
        var lease = gate.enterReceive(id, TYPE, state, mail(id));
        assertEquals(1, gate.activeReceives());
        assertThrows(IllegalArgumentException.class, () -> {
            try {
                throw new IllegalArgumentException("guest failure");
            } finally {
                lease.close();
            }
        });
        assertEquals(0, gate.activeReceives());
        lease.close();
        assertEquals(0, gate.activeReceives(), "double close cannot underflow");
        gate.sealStop(false);
        assertThrows(IllegalStateException.class, () -> gate.enterReceive(
                id, TYPE, state, mail(id)));
        assertThrows(IllegalStateException.class, () -> gate.beginTeardown(id, TYPE, state));
        gate.close();
    }

    @Test
    void foreignRecipientCannotMintReceiveLeaseOrBypassStop() {
        var id = ActorRuntime.ActorId.create();
        var foreign = ActorRuntime.ActorId.create();
        var gate = initialized(id);
        var state = (ActorRuntime.ActorOwnedGuestState) () -> id;
        assertThrows(SecurityException.class, () -> gate.enterReceive(
                foreign, TYPE, state, mail(id)));
        assertThrows(SecurityException.class, () -> gate.enterReceive(
                id, TYPE, state, mail(foreign)));
        assertEquals(0, gate.activeReceives());
        gate.sealStop(true);
        assertThrows(IllegalStateException.class, () -> gate.enterReceive(
                id, TYPE, state, mail(id)));
        gate.beginTeardown(id, TYPE, state);
        gate.close();
    }
}
