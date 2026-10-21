package dev.oreslang.runtime.compute;

import java.util.Objects;

/** Runtime-owned queue authority bound to one exact device authority. */
public final class ComputeQueueHandle {
    private final ComputeDeviceHandle device;
    private final long queueId;
    private final long generation;
    private final int maximumInFlight;
    private final Object proof;

    ComputeQueueHandle(
            ComputeDeviceHandle device,
            long queueId,
            long generation,
            int maximumInFlight) {
        this.device = Objects.requireNonNull(device, "device");
        if (queueId <= 0) {
            throw new IllegalArgumentException("queueId must be positive");
        }
        if (generation <= 0) {
            throw new IllegalArgumentException("queue generation must be positive");
        }
        if (maximumInFlight <= 0) {
            throw new IllegalArgumentException("maximumInFlight must be positive");
        }
        this.queueId = queueId;
        this.generation = generation;
        this.maximumInFlight = maximumInFlight;
        this.proof = new Object();
    }

    public long queueId() { return queueId; }
    public long generation() { return generation; }
    public int maximumInFlight() { return maximumInFlight; }

    boolean matchesDevice(ComputeDeviceHandle candidate) {
        return device.sameAuthority(candidate);
    }

    boolean sameAuthority(ComputeQueueHandle other) {
        return other != null && proof == other.proof;
    }
}
