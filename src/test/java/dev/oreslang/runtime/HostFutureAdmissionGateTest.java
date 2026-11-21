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
            Runnable lease = gate.reserveGuestTurn();
            try {
                admitted.incrementAndGet();
            } finally {
                lease.run();
            }
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
            gate.reserveGuestTurn().run();
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
            assertThrows(RejectedExecutionException.class, gate::reserveGuestTurn);
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
    void activeGuestReservationRejectsNewHostClaimUntilTruffleLeave()
            throws Exception {
        HostFutureAdmissionGate gate = new HostFutureAdmissionGate();
        CountDownLatch guestReserved = new CountDownLatch(1);
        CountDownLatch guestMayLeave = new CountDownLatch(1);
        FutureTask<Void> guest = new FutureTask<>(() -> {
            Runnable lease = gate.reserveGuestTurn();
            guestReserved.countDown();
            try {
                if (!guestMayLeave.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("guest leave was not released");
                }
            } finally {
                lease.run();
            }
            return null;
        });
        Thread thread = new Thread(guest, "guest-reserves-before-host");
        try {
            thread.start();
            assertTrue(guestReserved.await(5, TimeUnit.SECONDS));
            IllegalStateException blocked = assertThrows(
                    IllegalStateException.class, gate::begin);
            assertTrue(blocked.getMessage().contains("idle guest-entry"),
                    blocked.getMessage());
            guestMayLeave.countDown();
            guest.get(5, TimeUnit.SECONDS);
            gate.begin();
            gate.end();
        } finally {
            guestMayLeave.countDown();
            gate.close();
            thread.join(5_000);
        }
        assertFalse(thread.isAlive());
    }

    @Test
    void guestEntryLeaseIsSingleUseAndCarrierBound() throws Exception {
        HostFutureAdmissionGate gate = new HostFutureAdmissionGate();
        Runnable lease = gate.reserveGuestTurn();
        FutureTask<Boolean> foreign = new FutureTask<>(() -> {
            assertThrows(IllegalStateException.class, lease::run);
            return true;
        });
        Thread thread = new Thread(foreign, "wrong-guest-lease-owner");
        try {
            thread.start();
            assertTrue(foreign.get(5, TimeUnit.SECONDS));
            lease.run();
            assertThrows(IllegalStateException.class, lease::run);
            gate.begin();
            gate.end();
        } finally {
            gate.close();
            thread.join(5_000);
        }
    }

    @Test
    void interruptedGuestNeverPassesAdmissionOrLosesInterruptFlag() throws Exception {
        HostFutureAdmissionGate gate = new HostFutureAdmissionGate();
        gate.begin();
        CountDownLatch start = new CountDownLatch(1);
        FutureTask<Boolean> carrier = new FutureTask<>(() -> {
            start.countDown();
            try {
                gate.reserveGuestTurn().run();
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

    @Test
    void closingSealsNewHostClaimsWithoutAbortingAlreadyQueuedGuestCleanup()
            throws Exception {
        HostFutureAdmissionGate gate = new HostFutureAdmissionGate();
        gate.begin();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger cleanupEntered = new AtomicInteger();
        FutureTask<Void> carrier = new FutureTask<>(() -> {
            ready.countDown();
            Runnable physical = gate.reserveGuestTurn();
            try {
                cleanupEntered.incrementAndGet();
            } finally {
                physical.run();
            }
            return null;
        });
        Thread worker = new Thread(carrier, "cleanup-waiting-for-host-exit");
        try {
            worker.start();
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            gate.sealHostClaims();
            assertThrows(IllegalStateException.class, gate::begin);
            assertEquals(0, cleanupEntered.get());
            // The old host can still finish and release its original scope.
            gate.end();
            carrier.get(5, TimeUnit.SECONDS);
            assertEquals(1, cleanupEntered.get());
            gate.close();
            assertThrows(RejectedExecutionException.class, gate::reserveGuestTurn);
        } finally {
            gate.close();
            worker.join(5_000);
        }
        assertFalse(worker.isAlive());
    }

    @Test
    void failedPhysicalLeaveQuarantinesHostAndGuestEvenAfterLeaseRelease() {
        HostFutureAdmissionGate gate = new HostFutureAdmissionGate();
        Runnable entered = gate.reserveGuestTurn();
        gate.quarantineUnknownPhysicalExit();
        assertTrue(gate.physicalExitUnproven());
        // Release the coordination lease to avoid leaking a carrier, but do
        // not reinterpret it as physical DONE or allow context retirement.
        entered.run();
        assertThrows(IllegalStateException.class, gate::begin);
        assertThrows(RejectedExecutionException.class, gate::reserveGuestTurn);
        assertThrows(IllegalStateException.class, entered::run);
        gate.close();
        assertTrue(gate.physicalExitUnproven());
    }


    @Test
    void retirementRequiresNoHostScopeAndNoPhysicalGuestReservation() {
        HostFutureAdmissionGate gate = new HostFutureAdmissionGate();
        gate.begin();
        gate.sealHostClaims();
        assertThrows(IllegalStateException.class, gate::requireNoActiveHostClaim);
        assertThrows(IllegalStateException.class, gate::requirePhysicalQuiescence);
        gate.end();
        gate.requireNoActiveHostClaim();

        Runnable physical = gate.reserveGuestTurn();
        assertThrows(IllegalStateException.class, gate::requirePhysicalQuiescence,
                "a scheduler Future is not proof of TruffleContext.leave");
        physical.run();
        gate.requirePhysicalQuiescence();
        gate.close();
    }

    @Test
    void poisonedPhysicalExitNeverPassesRetirementPreflight() {
        HostFutureAdmissionGate gate = new HostFutureAdmissionGate();
        Runnable lease = gate.reserveGuestTurn();
        gate.quarantineUnknownPhysicalExit();
        lease.run();
        assertThrows(IllegalStateException.class, gate::requirePhysicalQuiescence);
        assertTrue(gate.physicalExitUnproven());
    }



    @Test
    void inlineGuestTurnCannotBypassSameThreadScopedHostEntry() {
        HostFutureAdmissionGate gate = new HostFutureAdmissionGate();
        gate.begin();
        gate.begin();
        assertThrows(IllegalStateException.class, gate::reserveGuestTurn,
                "same-thread root callback must not bypass the host Value.execute scope");
        assertThrows(IllegalStateException.class, gate::requirePhysicalQuiescence);
        gate.end();
        assertThrows(IllegalStateException.class, gate::reserveGuestTurn,
                "inner host release is not the physical outer Value.execute exit");
        gate.end();
        Runnable lease = gate.reserveGuestTurn();
        lease.run();
        gate.requirePhysicalQuiescence();
        gate.close();
    }

    @Test
    void uncertainGuestEnterQuarantinesWithoutForgingPhysicalLeave() {
        HostFutureAdmissionGate gate = new HostFutureAdmissionGate();
        Runnable lease = gate.reserveGuestTurn();
        // TruffleContext.enter can throw before returning its enter token.
        // The coordination lease MUST NOT be interpreted as physical proof.
        gate.quarantineUnknownPhysicalEntry();
        lease.run();
        assertTrue(gate.physicalEntryUnproven());
        assertFalse(gate.physicalExitUnproven(),
                "failed entry and failed exit remain distinct evidence");
        assertThrows(IllegalStateException.class, gate::requirePhysicalQuiescence);
        assertThrows(RejectedExecutionException.class, gate::reserveGuestTurn);
        assertThrows(IllegalStateException.class, gate::begin);
        assertThrows(IllegalStateException.class, lease::run);
        gate.close();
        assertTrue(gate.physicalEntryUnproven(),
                "closing the gate must not erase physical-entry uncertainty");
    }



    @Test
    void alreadyEnteredGuestCarrierMayPerformSameThreadNestedHostCall() {
        HostFutureAdmissionGate gate = new HostFutureAdmissionGate();
        Runnable guest = gate.reserveGuestTurn();
        gate.begin();
        gate.begin();
        gate.end();
        gate.end();
        guest.run();
        gate.requirePhysicalQuiescence();
        gate.close();
    }

    @Test
    void localGuestCarrierCannotLaunderForeignPhysicalEntryIntoHostCall()
            throws Exception {
        HostFutureAdmissionGate gate = new HostFutureAdmissionGate();
        Runnable own = gate.reserveGuestTurn();
        CountDownLatch foreignEntered = new CountDownLatch(1);
        CountDownLatch foreignRelease = new CountDownLatch(1);
        FutureTask<Void> foreign = new FutureTask<>(() -> {
            Runnable lease = gate.reserveGuestTurn();
            foreignEntered.countDown();
            try {
                if (!foreignRelease.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("foreign carrier was not released");
                }
            } finally {
                lease.run();
            }
            return null;
        });
        Thread thread = new Thread(foreign, "foreign-truffle-entry");
        try {
            thread.start();
            assertTrue(foreignEntered.await(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, gate::begin,
                    "same-carrier privilege must not cover a foreign guest");
            foreignRelease.countDown();
            foreign.get(5, TimeUnit.SECONDS);
            gate.begin();
            gate.end();
        } finally {
            foreignRelease.countDown();
            own.run();
            gate.close();
            thread.join(5_000);
        }
        assertFalse(thread.isAlive());
    }


    @Test
    void retirementPreflightRejectsAlreadyPoisonedEntryBeforeDependencyTeardown() {
        HostFutureAdmissionGate clean = new HostFutureAdmissionGate();
        Runnable cleanLease = clean.reserveGuestTurn();
        clean.requireProvenEntryAndExit();
        cleanLease.run();
        clean.requirePhysicalQuiescence();

        HostFutureAdmissionGate poisoned = new HostFutureAdmissionGate();
        Runnable lease = poisoned.reserveGuestTurn();
        poisoned.quarantineUnknownPhysicalEntry();
        assertThrows(IllegalStateException.class, poisoned::requireProvenEntryAndExit,
                "must reject known-unsafe state before actor/CONTROL resources retire");
        lease.run();
        assertThrows(IllegalStateException.class, poisoned::requireProvenEntryAndExit,
                "releasing a JVM reservation is not physical guest DONE");
        assertThrows(IllegalStateException.class, poisoned::requirePhysicalQuiescence);
    }


}
