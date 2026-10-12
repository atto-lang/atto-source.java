package dev.oreslang.runtime.accel;

import dev.oreslang.runtime.DataOrientedLayout;
import dev.oreslang.runtime.accel.DopGpuAdmission;
import dev.oreslang.runtime.accel.DopKernelIR;
import dev.oreslang.runtime.accel.GpuBufferFence;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class GpuAtomicAdmissionTest {
    private static GpuBufferFence owner(long gen, String tenant, String device, long bytes) {
        return new GpuBufferFence(new GpuBufferFence.Identity(tenant, device, gen), bytes);
    }

    @Test
    void successfulClaimMovesBothBuffersToInFlightWithDistinctTickets() {
        var input = owner(1, "tenant", "device", 64);
        var output = owner(2, "tenant", "device", 64);
        var tickets = GpuBufferFence.claimPair(input, output, 64);
        assertEquals(GpuBufferFence.Status.IN_FLIGHT, input.status());
        assertEquals(GpuBufferFence.Status.IN_FLIGHT, output.status());
        assertEquals(1L, tickets.inputTicket().sequence());
        assertEquals(1L, tickets.outputTicket().sequence());
        assertThrows(IllegalStateException.class, input::take);
        assertThrows(IllegalStateException.class, output::take);
        assertThrows(IllegalStateException.class, () -> input.beginCommand(tickets.input()));
        input.complete(tickets.inputTicket(), true);
        output.complete(tickets.outputTicket(), true);
        assertEquals(GpuBufferFence.Status.IDLE, input.status());
        assertEquals(GpuBufferFence.Status.IDLE, output.status());
    }

    @Test
    void pairCompletionRejectsForeignTicketWithMatchingNumericFields() {
        var input = owner(5, "tenant", "device", 64);
        var output = owner(6, "tenant", "device", 64);
        var unrelated = owner(5, "tenant", "device", 64);
        var outsiderTicket = unrelated.beginCommand(unrelated.identity());
        var tickets = GpuBufferFence.claimPair(input, output, 64);
        assertEquals(outsiderTicket.generation(), tickets.inputTicket().generation());
        assertEquals(outsiderTicket.sequence(), tickets.inputTicket().sequence());
        assertThrows(IllegalStateException.class,
                () -> input.complete(outsiderTicket, true));
        assertThrows(IllegalStateException.class,
                () -> input.cancelPending(outsiderTicket));
        assertEquals(GpuBufferFence.Status.IN_FLIGHT, input.status());
        assertEquals(GpuBufferFence.Status.IN_FLIGHT, output.status());
        unrelated.complete(outsiderTicket, true);
        input.complete(tickets.inputTicket(), true);
        output.complete(tickets.outputTicket(), true);
    }

    @Test
    void rejectBusyInvalidOrMovedPairWithoutClaimingOtherBuffer() {
        var input = owner(7, "t", "d", 32);
        var output = owner(8, "t", "d", 32);
        var existing = output.beginCommand(output.identity());
        assertThrows(IllegalStateException.class,
                () -> GpuBufferFence.claimPair(input, output, 32));
        assertEquals(GpuBufferFence.Status.IDLE, input.status());
        assertEquals(GpuBufferFence.Status.IN_FLIGHT, output.status());
        output.complete(existing, true);
        assertThrows(IllegalArgumentException.class,
                () -> GpuBufferFence.claimPair(input, output, 16));
        assertEquals(GpuBufferFence.Status.IDLE, input.status());
        assertEquals(GpuBufferFence.Status.IDLE, output.status());
        assertThrows(IllegalArgumentException.class,
                () -> GpuBufferFence.claimPair(input, input, 32));
        assertThrows(IllegalArgumentException.class,
                () -> GpuBufferFence.claimPair(input, owner(7, "t", "d", 32), 32));
        assertThrows(IllegalArgumentException.class,
                () -> GpuBufferFence.claimPair(input, owner(9, "other", "d", 32), 32));
        assertThrows(IllegalArgumentException.class,
                () -> GpuBufferFence.claimPair(input, owner(9, "t", "other", 32), 32));
        var successor = output.take();
        assertThrows(IllegalStateException.class,
                () -> GpuBufferFence.claimPair(input, output, 32));
        assertEquals(GpuBufferFence.Status.IDLE, input.status());
        var tickets = GpuBufferFence.claimPair(input, successor, 32);
        successor.cancelPending(tickets.outputTicket());
        input.cancelPending(tickets.inputTicket());
        assertThrows(IllegalStateException.class, successor::take);
        assertThrows(IllegalStateException.class,
                () -> GpuBufferFence.claimPair(input, successor, 32));
        successor.acknowledgeCancelledDeviceEvent(tickets.outputTicket(), tickets.output());
        input.acknowledgeCancelledDeviceEvent(tickets.inputTicket(), tickets.input());
        successor.retireAfterBackendRelease(tickets.output());
        input.retireAfterBackendRelease(tickets.input());
        successor.close();
        input.close();
    }

    @Test
    void reversedConcurrentPairClaimsHaveExactlyOneWinner() throws InterruptedException {
        for (int iteration = 0; iteration < 100; iteration++) {
            var a = owner(1, "t", "d", 16);
            var b = owner(2, "t", "d", 16);
            var begin = new CountDownLatch(1);
            var winners = new AtomicInteger();
            var failed = new AtomicInteger();
            var winnerTicket = new AtomicReference<GpuBufferFence.PairTickets>();
            var first = new Thread(() -> compete(a, b, begin, winners, failed, winnerTicket));
            var second = new Thread(() -> compete(b, a, begin, winners, failed, winnerTicket));
            first.start();
            second.start();
            begin.countDown();
            first.join();
            second.join();
            assertEquals(1, winners.get());
            assertEquals(1, failed.get());
            assertEquals(GpuBufferFence.Status.IN_FLIGHT, a.status());
            assertEquals(GpuBufferFence.Status.IN_FLIGHT, b.status());
            var won = winnerTicket.get();
            assertNotNull(won);
            if (won.input().allocationGeneration() == 1) {
                a.complete(won.inputTicket(), true);
                b.complete(won.outputTicket(), true);
            } else {
                b.complete(won.inputTicket(), true);
                a.complete(won.outputTicket(), true);
            }
        }
    }

    private static void compete(GpuBufferFence a, GpuBufferFence b,
                                CountDownLatch start, AtomicInteger winners,
                                AtomicInteger failed,
                                AtomicReference<GpuBufferFence.PairTickets> winnerTicket) {
        try {
            start.await();
            var won = GpuBufferFence.claimPair(a, b, 16);
            winnerTicket.set(won);
            winners.incrementAndGet();
        } catch (IllegalStateException expected) {
            failed.incrementAndGet();
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interruption);
        }
    }

    @Test
    void claimRacingWithTakeCannotPartiallyClaimTheInput() throws InterruptedException {
        for (int iteration = 0; iteration < 150; iteration++) {
            var input = owner(1, "t", "d", 16);
            var output = owner(2, "t", "d", 16);
            var start = new CountDownLatch(1);
            var claimed = new AtomicReference<GpuBufferFence.PairTickets>();
            var moved = new AtomicReference<GpuBufferFence>();
            var errors = new AtomicInteger();
            var claimer = new Thread(() -> {
                try {
                    start.await();
                    claimed.set(GpuBufferFence.claimPair(input, output, 16));
                } catch (IllegalStateException expected) {
                    // Ownership transfer won, so the old output is invalid.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    errors.incrementAndGet();
                }
            });
            var mover = new Thread(() -> {
                try {
                    start.await();
                    moved.set(output.take());
                } catch (IllegalStateException expected) {
                    // Paired admission won, so moving a busy buffer is forbidden.
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    errors.incrementAndGet();
                }
            });
            claimer.start();
            mover.start();
            start.countDown();
            claimer.join();
            mover.join();
            assertEquals(0, errors.get());
            assertNotEquals(claimed.get() == null, moved.get() == null);
            if (claimed.get() != null) {
                assertEquals(GpuBufferFence.Status.IN_FLIGHT, input.status());
                assertEquals(GpuBufferFence.Status.IN_FLIGHT, output.status());
                input.complete(claimed.get().inputTicket(), true);
                output.complete(claimed.get().outputTicket(), true);
            } else {
                assertEquals(GpuBufferFence.Status.IDLE, input.status());
                assertEquals(GpuBufferFence.Status.IDLE, moved.get().status());
                assertThrows(IllegalStateException.class, output::status);
            }
        }
    }

    @Test
    void typedDopClaimReturnsTheKernelAndTheActualFenceTickets() {
        var plan = new DataOrientedLayout.Plan(DataOrientedLayout.Kind.AOSOA, 7, 3, 4, 16, 4);
        var kernel = new DopKernelIR.Kernel(plan, DopKernelIR.Element.F32,
                DopKernelIR.Op.SCALE, 2.0f, 0.0f);
        var src = owner(30, "t", "d", plan.byteLength());
        var dst = owner(31, "t", "d", plan.byteLength());
        var input = new DopGpuAdmission.DeviceBuffer(plan, src);
        var output = new DopGpuAdmission.DeviceBuffer(plan, dst);
        var claimed = DopGpuAdmission.claim(kernel,
                new DopKernelIR.GpuTarget(64, 8192, 100, true), input, output);
        assertEquals(plan, claimed.binding().layout());
        assertEquals(plan.byteLength(), claimed.binding().bytesPerBuffer());
        assertEquals(GpuBufferFence.Status.IN_FLIGHT, src.status());
        assertEquals(GpuBufferFence.Status.IN_FLIGHT, dst.status());
        src.complete(claimed.tickets().inputTicket(), true);
        dst.complete(claimed.tickets().outputTicket(), true);
    }
    @Test
    void pairedCompletionIsAtomicAndRejectsMixedOrReplayTickets() {
        var source = owner(201, "tenant", "gpu", 64);
        var destination = owner(202, "tenant", "gpu", 64);
        var pair = GpuBufferFence.claimPair(source, destination, 64);
        var otherInput = owner(201, "tenant", "gpu", 64);
        var otherOutput = owner(202, "tenant", "gpu", 64);
        var otherPair = GpuBufferFence.claimPair(otherInput, otherOutput, 64);
        var swapped = new GpuBufferFence.PairTickets(
                pair.input(), otherPair.inputTicket(),
                pair.output(), pair.outputTicket());
        assertThrows(IllegalStateException.class,
                () -> GpuBufferFence.completePair(source, destination, swapped, true));
        assertEquals(GpuBufferFence.Status.IN_FLIGHT, source.status());
        assertEquals(GpuBufferFence.Status.IN_FLIGHT, destination.status());
        GpuBufferFence.completePair(source, destination, pair, true);
        assertEquals(GpuBufferFence.Status.IDLE, source.status());
        assertEquals(GpuBufferFence.Status.IDLE, destination.status());
        assertThrows(IllegalStateException.class,
                () -> GpuBufferFence.completePair(source, destination, pair, true));
        GpuBufferFence.completePair(otherInput, otherOutput, otherPair, true);
    }

    @Test
    void cancellationKeepsBothAllocationsPoisonedUntilBothEventsAreAcknowledged() {
        var source = owner(301, "tenant", "gpu", 64);
        var destination = owner(302, "tenant", "gpu", 64);
        var pair = GpuBufferFence.claimPair(source, destination, 64);
        GpuBufferFence.cancelPair(source, destination, pair);
        assertEquals(GpuBufferFence.Status.POISONED, source.status());
        assertEquals(GpuBufferFence.Status.POISONED, destination.status());
        assertThrows(IllegalStateException.class, source::take);
        assertThrows(IllegalStateException.class, destination::take);
        assertThrows(IllegalStateException.class,
                () -> source.retireAfterBackendRelease(pair.input()));
        assertThrows(IllegalStateException.class,
                () -> destination.retireAfterBackendRelease(pair.output()));
        assertThrows(IllegalStateException.class,
                () -> GpuBufferFence.completePair(source, destination, pair, false));
        GpuBufferFence.acknowledgeCancelledPair(source, destination, pair);
        assertThrows(IllegalStateException.class,
                () -> GpuBufferFence.acknowledgeCancelledPair(source, destination, pair));
        source.retireAfterBackendRelease(pair.input());
        destination.retireAfterBackendRelease(pair.output());
        source.close();
        destination.close();
    }

    @Test
    void pairedCompletionRejectsAlreadyHalfCompletedBufferWithoutChangingOther() {
        var source = owner(401, "tenant", "gpu", 64);
        var destination = owner(402, "tenant", "gpu", 64);
        var pair = GpuBufferFence.claimPair(source, destination, 64);
        source.complete(pair.inputTicket(), true);
        assertThrows(IllegalStateException.class,
                () -> GpuBufferFence.completePair(source, destination, pair, true));
        assertEquals(GpuBufferFence.Status.IDLE, source.status());
        assertEquals(GpuBufferFence.Status.IN_FLIGHT, destination.status());
        destination.complete(pair.outputTicket(), true);
    }

}
