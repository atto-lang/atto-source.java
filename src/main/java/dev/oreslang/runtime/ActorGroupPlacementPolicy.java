package dev.oreslang.runtime;

import java.util.Objects;

/**
 * Immutable, host-side ActorGroup placement request.
 *
 * <p>A group is a cooperation domain, not an executor or OS-thread pool. This
 * describes the placement constraints requested by the group creator; it does
 * not grant a group member authority to migrate an unrelated actor or allocate
 * GPU memory. Only policies with a proven runtime admission path may be
 * installed. Unsupported policies fail closed before the group is created.
 *
 * <p>Source-language lowering and native-backend conformance are separate
 * release gates; this Java class is not a guest-visible capability.
 */
public record ActorGroupPlacementPolicy(
        CpuAffinity cpuAffinity,
        int maxParallelTurns,
        Accelerator accelerator,
        String gpuDeviceId,
        int maxGpuInFlight) {

    /** Hard affinity must never be inferred from a best-effort hint. */
    public enum CpuAffinity {
        ANY,
        PREFER_SAME_CORE,
        PREFER_SAME_NUMA_NODE
    }

    /** GPU placement refers to compute jobs, NEVER actor mailbox turns. */
    public enum Accelerator {
        CPU_ONLY,
        GPU_PREFERRED,
        GPU_REQUIRED
    }

    public ActorGroupPlacementPolicy {
        Objects.requireNonNull(cpuAffinity, "cpuAffinity");
        Objects.requireNonNull(accelerator, "accelerator");
        if (maxParallelTurns <= 0) {
            throw new IllegalArgumentException("maxParallelTurns must be positive");
        }
        if (maxGpuInFlight < 0) {
            throw new IllegalArgumentException("maxGpuInFlight must be non-negative");
        }
        if (gpuDeviceId != null &&
                (!gpuDeviceId.matches("[A-Za-z0-9_.:-]{1,96}"))) {
            throw new IllegalArgumentException("invalid GPU device identifier");
        }
        if (accelerator == Accelerator.CPU_ONLY) {
            if (gpuDeviceId != null || maxGpuInFlight != 0) {
                throw new IllegalArgumentException(
                        "CPU_ONLY cannot reserve a GPU device or GPU in-flight budget");
            }
        } else if (gpuDeviceId == null || maxGpuInFlight == 0) {
            throw new IllegalArgumentException(
                    "GPU placement requires a device identifier and positive in-flight limit");
        }
    }

    /** Exactly the behavior of createActorGroup() before placement was added. */
    public static ActorGroupPlacementPolicy unrestricted() {
        return new ActorGroupPlacementPolicy(
                CpuAffinity.ANY, Integer.MAX_VALUE, Accelerator.CPU_ONLY, null, 0);
    }

    /**
     * Keep every non-default placement request fail-closed until the integrated
     * runtime uses a proven nonparking permit/handoff path. The current
     * ActorGroupTurnGate serializes on a Java monitor and therefore cannot
     * satisfy the carrier-nonparking contract merely because it is bounded.
     */
    void requireSupported() {
        if (cpuAffinity != CpuAffinity.ANY) {
            throw new UnsupportedOperationException(
                    "ActorGroup CPU affinity requires group-aware scheduler placement");
        }
        if (maxParallelTurns != Integer.MAX_VALUE) {
            throw new UnsupportedOperationException(
                    "ActorGroup maxParallelTurns requires nonparking scheduler permit integration");
        }
        if (accelerator != Accelerator.CPU_ONLY) {
            throw new UnsupportedOperationException(
                    "ActorGroup GPU placement requires a resource-admitted device broker");
        }
    }
}
