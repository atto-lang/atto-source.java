package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
final class HostFutureAdmissionGateTest {

    @Test
    void nestedHostScopesDeferSchedulerUntilOuterValueCallExits() throws Exception {
        HostFutureAdmissionGate gate = new HostFutureAdmissionGate();
        gate.begin();
        gate.begin();
        CountDownLatch launched = new CountDownLatch(1);
        AtomicInteger admitted = new AtomicInteger();
        FutureTask<Void> carrier = new FutureTask<>(() -> {
            launched.countDown();
            gate.awaitGuestAdmission();
            admitted.incrementAndGet();
            return null;
        });
        Thread thread = new Thread(carrier, "guest-behind-host-value");
        try {
            thread.start();
            assertTrue(launched.await(5, TimeUnit.SECONDS));
            assertFalse(carrier.isDone());
            gate.end();
            assertEquals(0, admitted.get(),
                    "inner host bridge release cannot open outer host call");
            assertFalse(carrier.isDone());
            gate.end();
            carrier.get(5, TimeUnit.SECONDS);
            assertEquals(1, admitted.get());
        } finally {
            gate.close();
            thread.join(5_000);
        }
        assertFalse(thread.isAlive());
    }

    @Test
    void disposalWakesBlockedCarrierAndForbidsNewHostOrGuestAdmission()
            throws Exception {
        HostFutureAdmissionGate gate = new HostFutureAdmissionGate();
        gate.begin();
        CountDownLatch started = new CountDownLatch(1);
        FutureTask<Void> carrier = new FutureTask<>(() -> {
            started.countDown();
            gate.awaitGuestAdmission();
            return null;
        });
        Thread thread = new Thread(carrier, "guest-closed-host-context");
        try {
            thread.start();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            gate.close();
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> carrier.get(5, TimeUnit.SECONDS));
            assertInstanceOf(RejectedExecutionException.class, failure.getCause());
            assertThrows(RejectedExecutionException.class, gate::awaitGuestAdmission);
            assertThrows(IllegalStateException.class, gate::begin);
            // The original host still owns this scope and must release it
            // safely even after disposal to avoid retaining its thread.
            gate.end();
        } finally {
            gate.close();
            thread.join(5_000);
        }
        assertFalse(thread.isAlive());
    }

    @Test
    void foreignHostCannotClaimOrReleaseAnotherThreadScope() throws Exception {
        HostFutureAdmissionGate gate = new HostFutureAdmissionGate();
        gate.begin();
        FutureTask<Boolean> foreign = new FutureTask<>(() -> {
            assertThrows(IllegalStateException.class, gate::begin);
            assertThrows(IllegalStateException.class, gate::end);
            return true;
        });
        Thread thread = new Thread(foreign, "foreign-host-handoff");
        try {
            thread.start();
            assertTrue(foreign.get(5, TimeUnit.SECONDS));
        } finally {
            gate.end();
            gate.close();
            thread.join(5_000);
        }
        assertFalse(thread.isAlive());
    }

    @Test
    void interruptedGuestNeverPassesAdmissionOrLosesInterruptFlag() throws Exception {
        HostFutureAdmissionGate gate = new HostFutureAdmissionGate();
        gate.begin();
        CountDownLatch start = new CountDownLatch(1);
        FutureTask<Boolean> carrier = new FutureTask<>(() -> {
            start.countDown();
            try {
                gate.awaitGuestAdmission();
                return false;
            } catch (java.util.concurrent.CancellationException expected) {
                return Thread.currentThread().isInterrupted();
            }
        });
        Thread thread = new Thread(carrier, "interrupted-waiter");
        try {
            thread.start();
            assertTrue(start.await(5, TimeUnit.SECONDS));
            thread.interrupt();
            assertTrue(carrier.get(5, TimeUnit.SECONDS));
        } finally {
            gate.close();
            gate.end();
            thread.join(5_000);
        }
    }
}
