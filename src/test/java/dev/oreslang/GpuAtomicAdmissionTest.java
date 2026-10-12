package dev.oreslang;

import dev.oreslang.runtime.DataOrientedLayout;
import dev.oreslang.runtime.accel.DopGpuAdmission;
import dev.oreslang.runtime.accel.DopKernelIR;
import dev.oreslang.runtime.accel.GpuBufferFence;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

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
            var first = new Thread(() -> compete(a, b, begin, winners, failed));
            var second = new Thread(() -> compete(b, a, begin, winners, failed));
            first.start();
            second.start();
            begin.countDown();
            first.join();
            second.join();
            assertEquals(1, winners.get());
            assertEquals(1, failed.get());
            assertEquals(GpuBufferFence.Status.IN_FLIGHT, a.status());
            assertEquals(GpuBufferFence.Status.IN_FLIGHT, b.status());
        }
    }

    private static void compete(GpuBufferFence a, GpuBufferFence b,
                                CountDownLatch start, AtomicInteger winners,
                                AtomicInteger failed) {
        try {
            start.await();
            GpuBufferFence.claimPair(a, b, 16);
            winners.incrementAndGet();
        } catch (IllegalStateException expected) {
            failed.incrementAndGet();
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interruption);
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
}
