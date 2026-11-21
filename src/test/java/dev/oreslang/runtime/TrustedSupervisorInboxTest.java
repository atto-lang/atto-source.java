package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrustedSupervisorInboxTest {
    @Test void priorityLaneAndCreditLifecycle() {
        var inbox = new TrustedSupervisorInbox(2, 2, 6, 5);
        var child = inbox.bindVerifiedChild(11, 1);
        assertEquals(TrustedSupervisorInbox.Admission.ACCEPTED, inbox.offerUser(child, 1, new byte[]{1, 2, 3}));
        assertEquals(TrustedSupervisorInbox.Admission.ACCEPTED, inbox.offerUser(child, 2, new byte[]{4, 5, 6}));
        assertEquals(TrustedSupervisorInbox.Admission.REJECTED, inbox.offerUser(child, 3, new byte[]{7}));
        assertEquals(TrustedSupervisorInbox.Admission.ACCEPTED, inbox.offerTrustedPanic(child, 3, 99));
        assertEquals(11, inbox.pollControl().childId());
        assertNull(inbox.pollControl());
        var d = inbox.pollUser();
        assertEquals(2, inbox.outstandingMessages()); // dispatch does not refund
        assertArrayEquals(new byte[]{1, 2, 3}, d.payloadCopy());
        inbox.completeUser(d);
        assertThrows(IllegalStateException.class, d::payloadCopy);
        assertEquals(1, inbox.outstandingMessages());
        assertEquals(3, inbox.outstandingBytes());
        assertThrows(IllegalArgumentException.class, () -> inbox.completeUser(d));
        assertEquals(TrustedSupervisorInbox.Admission.REJECTED, inbox.offerUser(child, 4, new byte[]{9}));
        inbox.revoke(child);
        assertEquals(0, inbox.outstandingMessages());
        inbox.close();
    }

    @Test void overflowEscalatesAndReincarnationFences() {
        var inbox = new TrustedSupervisorInbox(1, 4, 100, 20);
        var a = inbox.bindVerifiedChild(1, 1);
        var b = inbox.bindVerifiedChild(2, 1);
        assertEquals(TrustedSupervisorInbox.Admission.ACCEPTED, inbox.offerTrustedPanic(a, 1, 77));
        assertEquals(TrustedSupervisorInbox.Admission.REJECTED, inbox.offerTrustedPanic(a, 2, 77));
        assertEquals(TrustedSupervisorInbox.Admission.ESCALATE_TO_PARENT, inbox.offerTrustedPanic(b, 1, 88));
        assertTrue(inbox.needsParentEscalation());
        assertEquals(TrustedSupervisorInbox.Admission.REJECTED, inbox.offerUser(a, 2, new byte[]{1}));
        assertEquals(1, inbox.pollControl().childId());
        assertNull(inbox.pollControl());
        assertThrows(IllegalArgumentException.class, () -> inbox.revoke(b));
        inbox.revoke(a);
        assertThrows(IllegalStateException.class, () -> inbox.bindVerifiedChild(1, 2));
        assertEquals(TrustedSupervisorInbox.Admission.REJECTED, inbox.offerUser(a, 99, new byte[]{9}));
        inbox.close();
    }

    @Test void revocationRetainsInFlightAndRejectsForeignBinding() {
        var first = new TrustedSupervisorInbox(2, 2, 10, 10);
        var other = new TrustedSupervisorInbox(2, 2, 10, 10);
        var bound = first.bindVerifiedChild(42, 1);
        assertEquals(TrustedSupervisorInbox.Admission.REJECTED, other.offerUser(bound, 1, new byte[]{1}));
        assertEquals(TrustedSupervisorInbox.Admission.ACCEPTED, first.offerUser(bound, 1, new byte[]{1}));
        var delivery = first.pollUser();
        first.revoke(bound);
        assertEquals(1, first.outstandingMessages());
        first.completeUser(delivery);
        assertEquals(0, first.outstandingBytes());
        assertEquals(TrustedSupervisorInbox.Admission.REJECTED, first.offerTrustedPanic(bound, 2, 3));
        first.close(); other.close();
    }

    @Test void concurrentIngressRevocationNeverLeaksCredits() throws Exception {
        var inbox = new TrustedSupervisorInbox(2, 1000, 1000, 4);
        var child = inbox.bindVerifiedChild(5, 1);
        var start = new java.util.concurrent.CountDownLatch(1);
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread producer = new Thread(() -> {
            try {
                start.await();
                for (int i = 1; i <= 600; i++) {
                    var result = inbox.offerUser(child, i, new byte[]{(byte)i});
                    if (result == TrustedSupervisorInbox.Admission.ESCALATE_TO_PARENT)
                        throw new AssertionError("user data entered CONTROL escalation");
                }
            } catch (Throwable e) { error.compareAndSet(null, e); }
        });
        Thread revoker = new Thread(() -> {
            try { start.await(); inbox.revoke(child); }
            catch (Throwable e) { error.compareAndSet(null, e); }
        });
        producer.start(); revoker.start(); start.countDown();
        producer.join(); revoker.join();
        if (error.get() != null) throw new AssertionError(error.get());
        assertEquals(0, inbox.outstandingMessages());
        assertEquals(0, inbox.outstandingBytes());
        assertEquals(TrustedSupervisorInbox.Admission.REJECTED, inbox.offerUser(child, 601, new byte[]{3}));
        inbox.close();
    }

}
