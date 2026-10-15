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
     * Fail-closed runtime admission for the initial policy slice. In
     * particular, declaring a limit without enforcing it is unsound: no
     * restricted parallelism, group-specific locality or GPU device mapping
     * can be accepted until the scheduler/broker proves enforcement.
     *
     * <p>Future revisions must replace these gates with real admission and
     * keep group creation atomic with quota reservation.
     */
    void requireSupported() {
        if (cpuAffinity != CpuAffinity.ANY) {
            throw new UnsupportedOperationException(
                    "ActorGroup CPU affinity requires group-aware scheduler placement");
        }
        if (maxParallelTurns != Integer.MAX_VALUE) {
            throw new UnsupportedOperationException(
                    "ActorGroup maxParallelTurns requires nonblocking scheduler permits");
        }
        if (accelerator != Accelerator.CPU_ONLY) {
            throw new UnsupportedOperationException(
                    "ActorGroup GPU placement requires a resource-admitted device broker");
        }
    }
}
