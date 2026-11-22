package dev.oreslang;

import dev.oreslang.runtime.ActorReceiveAdmission;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

/** Host admission model only: NOT proof of concurrent AttoLang guest receive. */
final class ActorReceiveAdmissionTest {
    @Test
    void serialReceiveRetainsPermitAcrossSuspensionAndMethodsAreIndependent() throws Exception {
        try (ActorReceiveAdmission gate = ActorReceiveAdmission.serial(7)) {
            ActorReceiveAdmission.Permit suspendedReceive = gate.tryEnter(7);
            assertNotNull(suspendedReceive);
            assertEquals(1, gate.activeReceives());
            assertNull(gate.tryEnter(7), "receive B must wait while A is suspended");

            // No global actor-method lock is held by the receive-only permit.
            // An independent method invocation on another thread can complete.
            try (var executor = Executors.newSingleThreadExecutor()) {
                Future<Integer> status = executor.submit(() -> 42);
                assertEquals(42, status.get());
            }
            assertNull(gate.tryEnter(7), "independent method did not release receive permit");
            suspendedReceive.close();
            suspendedReceive.close(); // duplicate retirement cannot increase capacity
            ActorReceiveAdmission.Permit next = gate.tryEnter(7);
            assertNotNull(next);
            assertTrue(next.ticket() > suspendedReceive.ticket());
            assertEquals(1, gate.peakReceives());
            next.close();
        }
    }

    @Test
    void concurrentOrdinaryReceivesAreBoundedAndDistinct() {
        try (ActorReceiveAdmission gate = ActorReceiveAdmission.concurrent(11, 3)) {
            var first = gate.tryEnter(11);
            var second = gate.tryEnter(11);
            var third = gate.tryEnter(11);
            assertNotNull(first);
            assertNotNull(second);
            assertNotNull(third);
            assertNotEquals(first.ticket(), second.ticket());
            assertNotEquals(second.ticket(), third.ticket());
            assertNull(gate.tryEnter(11));
            second.close();
            var fourth = gate.tryEnter(11);
            assertNotNull(fourth);
            assertEquals(third.ticket() + 1, fourth.ticket());
            assertEquals(3, gate.peakReceives());
            first.close();
            third.close();
            fourth.close();
        }
    }

    @Test
    void wrongIncarnationAndRetiredPermitsFailClosed() {
        ActorReceiveAdmission oldGate = ActorReceiveAdmission.serial(23);
        ActorReceiveAdmission.Permit obsolete = oldGate.tryEnter(23);
        assertThrows(SecurityException.class, () -> oldGate.tryEnter(24));
        oldGate.close();
        assertTrue(obsolete.isRetired());
        obsolete.close();

        try (ActorReceiveAdmission replacement = ActorReceiveAdmission.serial(24)) {
            assertThrows(SecurityException.class, () -> replacement.tryEnter(23));
            ActorReceiveAdmission.Permit fresh = replacement.tryEnter(24);
            assertNotNull(fresh);
            obsolete.close(); // cannot release a permit belonging to replacement
            assertEquals(1, replacement.activeReceives());
            assertNull(replacement.tryEnter(24));
            fresh.close();
            assertThrows(IllegalStateException.class, () -> oldGate.tryEnter(23));
        }
    }

    @Test
    void racingReceiversNeverExceedCapacity() throws Exception {
        int workerCount = 16;
        int capacity = 4;
        CountDownLatch ready = new CountDownLatch(workerCount);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (ActorReceiveAdmission gate = ActorReceiveAdmission.concurrent(111, capacity);
             var executor = Executors.newFixedThreadPool(workerCount)) {
            List<Future<ActorReceiveAdmission.Permit>> futures = new ArrayList<>();
            for (int i = 0; i < workerCount; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    ActorReceiveAdmission.Permit permit = gate.tryEnter(111);
                    finish.await();
                    return permit;
                }));
            }
            ready.await();
            go.countDown();
            // Ensure all calls raced and permits remain active until counted.
            // The workers are blocked on finish, not inside the admission gate.
            finish.countDown();
            int successful = 0;
            for (Future<ActorReceiveAdmission.Permit> future : futures) {
                var permit = future.get();
                if (permit != null) {
                    successful++;
                    assertFalse(permit.isRetired());
                }
            }
            assertEquals(capacity, successful);
            assertEquals(capacity, gate.activeReceives());
            assertEquals(capacity, gate.peakReceives());
        }
    }
}
