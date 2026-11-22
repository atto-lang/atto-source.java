package dev.oreslang.runtime.accel;

import dev.oreslang.runtime.accel.GpuBufferFence;
import dev.oreslang.runtime.accel.QuantumCircuitIR;
import java.util.Arrays;

/** No JUnit or Maven dependencies: executable accelerator admission smoke. */
public final class AcceleratorSafetySmoke {
    private static void require(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
    }
    private static void rejects(Class<? extends RuntimeException> expected, Runnable action) {
        try { action.run(); }
        catch (RuntimeException e) {
            if (expected.isInstance(e)) return;
            throw new AssertionError("unexpected failure: " + e, e);
        }
        throw new AssertionError("expected rejection: " + expected);
    }
    public static void main(String[] args) {
        var builder = new QuantumCircuitIR.Builder(16, 16);
        for (int i = 0; i < 16; i++) builder.h(i);
        for (int i = 0; i < 16; i++) builder.measure(i, i);
        var circuit = builder.build();
        long[] counts = QuantumCircuitIR.simulateShots(circuit, 100_000, 9127);
        require(Arrays.stream(counts).sum() == 100_000L, "shot conservation");
        require(Arrays.equals(counts, QuantumCircuitIR.simulateShots(circuit, 100_000, 9127)),
                "seed replay mismatch");
        var basis = new QuantumCircuitIR.Builder(2, 2)
                .x(0).measure(0, 0).measure(1, 1).build();
        long[] weights = QuantumCircuitIR.simulateShots(basis, 100_000, 7);
        require(weights[1] == 100_000 && weights[0] == 0 && weights[2] == 0
                && weights[3] == 0, "impossible outcome sampled");
        var capped = new QuantumCircuitIR.Builder(1, 1);
        for (int i = 0; i < 1024; i++) capped.x(0);
        rejects(IllegalArgumentException.class, () -> capped.measure(0, 0));

        var identity = new GpuBufferFence.Identity("tenant", "device", 7);
        var owned = new GpuBufferFence(identity, 1024);
        var unrelated = new GpuBufferFence(identity, 1024);
        var a = owned.beginCommand(identity);
        var b = unrelated.beginCommand(identity);
        require(a.sequence() == b.sequence() && a.generation() == b.generation(),
                "not exercising identical scalar tickets");
        require(GpuBufferFence.Ticket.class.getConstructors().length == 0,
                "public ticket constructor permits forgery");
        rejects(IllegalStateException.class, () -> owned.complete(b, true));
        rejects(IllegalStateException.class, () -> owned.cancelPending(b));
        owned.cancelPending(a);
        rejects(IllegalStateException.class, () -> owned.retireAfterBackendRelease(identity));
        owned.acknowledgeCancelledDeviceEvent(a, identity);
        owned.retireAfterBackendRelease(identity);
        owned.close();
        unrelated.complete(b, true);
        unrelated.retireAfterBackendRelease(identity);
        unrelated.close();
        var inputOwner = new GpuBufferFence(
                new GpuBufferFence.Identity("tenant", "device", 101), 1024);
        var outputOwner = new GpuBufferFence(
                new GpuBufferFence.Identity("tenant", "device", 102), 1024);
        var pair = GpuBufferFence.claimPair(inputOwner, outputOwner, 1024);
        require(inputOwner.status() == GpuBufferFence.Status.IN_FLIGHT
                        && outputOwner.status() == GpuBufferFence.Status.IN_FLIGHT,
                "paired GPU admission must reserve both buffers");
        rejects(IllegalStateException.class, inputOwner::take);
        rejects(IllegalStateException.class,
                () -> GpuBufferFence.claimPair(inputOwner, outputOwner, 1024));
        rejects(IllegalStateException.class,
                () -> inputOwner.complete(pair.inputTicket(), true));
        rejects(IllegalStateException.class,
                () -> GpuBufferFence.completePair(inputOwner, outputOwner, pair, true));
        GpuBufferFence.markPairEnqueued(inputOwner, outputOwner, pair);
        GpuBufferFence.completePair(inputOwner, outputOwner, pair, true);
        inputOwner.retireAfterBackendRelease(pair.input());
        outputOwner.retireAfterBackendRelease(pair.output());
        inputOwner.close();
        outputOwner.close();
        System.out.println("PASS: high-dimensional quantum quota + zero-weight samples + device fence anti-forgery + atomic pair claim");
    }
}
