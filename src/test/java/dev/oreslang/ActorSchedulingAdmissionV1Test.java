package dev.oreslang;

import dev.oreslang.runtime.ActorReceiveAdmission;
import dev.oreslang.runtime.ActorSchedulingAdmissionV1;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real JVM host lease execution, NOT compiled .atto actor or guest AOT parity. */
final class ActorSchedulingAdmissionV1Test {
    private static Map<Integer, Long> workers(int count) {
        Map<Integer, Long> result = new HashMap<>();
        for (int lane = 0; lane < count; lane++) result.put(lane, 1L);
        return result;
    }

    private static ActorSchedulingAdmissionV1 elastic(long incarnation) {
        return new ActorSchedulingAdmissionV1(incarnation,
                ActorSchedulingAdmissionV1.POOL_SIZE, false,
                ActorSchedulingAdmissionV1.Affinity.STICKY, false, 64, 64, 64);
    }

    @Test void poolSizeGrowsTwoToEightAndPhysicalTurnsActuallyOverlap() throws Exception {
        try (var gate = elastic(11)) {
            gate.updateHealthyWorkers(workers(2));
            assertEquals(2, gate.maximumNow());
            var a = gate.tryEnter(11, 0, 1);
            var b = gate.tryEnter(11, 1, 1);
            assertNotNull(a);
            assertNotNull(b);
            assertEquals(2, gate.running());
            assertNull(gate.tryEnter(11, 0, 1));
            a.close(); b.close();

            gate.updateHealthyWorkers(workers(8));
            assertEquals(8, gate.maximumNow());
            var started = new CountDownLatch(8);
            var release = new CountDownLatch(1);
            var finished = new CountDownLatch(8);
            AtomicInteger concurrent = new AtomicInteger();
            AtomicInteger peak = new AtomicInteger();
            try (var executor = Executors.newFixedThreadPool(8)) {
                for (int lane = 0; lane < 8; lane++) {
                    int selectedLane = lane;
                    executor.execute(() -> {
                        try (var lease = gate.tryEnter(11, selectedLane, 1L)) {
                            assertNotNull(lease);
                            int current = concurrent.incrementAndGet();
                            peak.accumulateAndGet(current, Math::max);
                            started.countDown();
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                            concurrent.decrementAndGet();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(interrupted);
                        } finally {
                            finished.countDown();
                        }
                    });
                }
                try {
                    assertTrue(started.await(10, TimeUnit.SECONDS));
                    assertEquals(8, peak.get());
                    assertEquals(8, gate.running());
                } finally {
                    release.countDown();
                }
                assertTrue(finished.await(10, TimeUnit.SECONDS));
            }
            assertEquals(8, gate.peak());
            assertEquals(0, gate.running());
        }
    }

    @Test void shrinkDrainsWithoutOverAdmissionOrDoubleRelease() {
        try (var gate = elastic(21)) {
            gate.updateHealthyWorkers(workers(8));
            var held = new ArrayList<ActorSchedulingAdmissionV1.Lease>();
            for (int lane = 0; lane < 8; lane++) held.add(gate.tryEnter(21, lane, 1));
            assertTrue(held.stream().allMatch(java.util.Objects::nonNull));
            gate.updateHealthyWorkers(workers(2));
            assertEquals(2, gate.maximumNow());
            assertNull(gate.tryEnter(21, 0, 1));
            for (int i = 0; i < 7; i++) held.get(i).close();
            assertEquals(1, gate.running());
            assertNotNull(gate.tryEnter(21, 0, 1));
            held.get(7).close();
            held.get(7).close();
            assertEquals(1, gate.running());
        }
    }

    @Test void serialReceiveSuspendsWithoutRetainingPhysicalCarrier() {
        try (var receives = ActorReceiveAdmission.serial(31);
             var physical = new ActorSchedulingAdmissionV1(31, 1, true,
                     ActorSchedulingAdmissionV1.Affinity.STICKY, false, 8, 8, 8)) {
            physical.updateHealthyWorkers(workers(4));
            var logical = receives.tryEnter(31);
            var first = physical.tryEnter(31, 0, 1);
            assertNotNull(first);
            assertNull(physical.tryEnter(31, 1, 1));
            first.close(); // await; logical receive remains live
            assertNull(receives.tryEnter(31));
            try (var method = physical.tryEnter(31, 1, 1)) {
                assertNotNull(method);
                assertNull(physical.tryEnter(31, 0, 1));
            }
            try (var resume = physical.tryEnter(31, 0, 1)) {
                assertNotNull(resume);
            }
            logical.close();
            assertNotNull(receives.tryEnter(31));
        }
    }

    @Test void pinnedRehomesOnlyOnRetirementAfterOldLeaseDrains() {
        try (var gate = new ActorSchedulingAdmissionV1(41, 1, true,
                ActorSchedulingAdmissionV1.Affinity.PINNED, false, 8, 8, 8)) {
            gate.updateHealthyWorkers(workers(2));
            var first = gate.tryEnter(41, 0, 1);
            assertNotNull(first);
            gate.updateHealthyWorkers(Map.of(1, 1L));
            assertNull(gate.tryEnter(41, 1, 1));
            first.close();
            assertNotNull(gate.tryEnter(41, 1, 1));
            assertEquals(1, gate.homeLane());
        }
    }

    @Test void workerGenerationIncarnationAndStopAreFenced() {
        try (var gate = elastic(51)) {
            gate.updateHealthyWorkers(Map.of(0, 7L));
            assertNull(gate.tryEnter(51, 0, 6));
            assertThrows(SecurityException.class, () -> gate.tryEnter(52, 0, 7));
            var lease = gate.tryEnter(51, 0, 7);
            assertNotNull(lease);
            gate.close();
            assertNull(gate.tryEnter(51, 0, 7));
            assertEquals(1, gate.running());
            lease.close();
            lease.close();
            assertEquals(0, gate.running());
            assertThrows(IllegalStateException.class, () -> gate.updateHealthyWorkers(workers(2)));
        }
    }

    @Test void rejectsImpossibleOrUnauthorizedPolicies() {
        assertThrows(IllegalArgumentException.class, () -> new ActorSchedulingAdmissionV1(
                1, 0, false, ActorSchedulingAdmissionV1.Affinity.NONE, false, 8, 8, 8));
        assertThrows(IllegalArgumentException.class, () -> new ActorSchedulingAdmissionV1(
                1, 2, true, ActorSchedulingAdmissionV1.Affinity.NONE, false, 8, 8, 8));
        assertThrows(IllegalArgumentException.class, () -> new ActorSchedulingAdmissionV1(
                1, 2, false, ActorSchedulingAdmissionV1.Affinity.PINNED, false, 8, 8, 8));
        try (var gate = new ActorSchedulingAdmissionV1(60, 1, false,
                ActorSchedulingAdmissionV1.Affinity.DEDICATED, false, 8, 8, 8)) {
            gate.updateHealthyWorkers(workers(1));
            assertNull(gate.tryEnter(60, 0, 1)); // host has not reserved an exclusive carrier
        }
    }
}
