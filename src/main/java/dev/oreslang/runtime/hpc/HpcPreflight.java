package dev.oreslang.runtime.hpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Negative, informational preflight for the *future* trusted-HPC backend.
 *
 * <p>Not a capability authority: callers can construct arbitrary requests.
 * Only a supervisor-issued, independently verified resource lease and a
 * separate native enforcement boundary may eventually admit execution.
 * Until that exists, every request is deliberately blocked.</p>
 */
public final class HpcPreflight {

    public enum Principal {
        TRUSTED_HOST_DEPLOYMENT,
        UNTRUSTED_ACTOR
    }

    public enum Deployment {
        DEDICATED_LINUX,
        SHARED_HOST,
        FAAS,
        MOBILE
    }

    public enum GuestCodegen {
        JVM_TRUFFLE,
        GRAAL_NATIVE_AOT_INTERPRETER,
        GRAAL_NATIVE_HYBRID,
        FUTURE_LLVM_STATIC_GUEST
    }

    public enum NicPath {
        ORDINARY_SOCKET,
        AF_XDP,
        DPDK_VFIO,
        RDMA
    }

    public record Request(Principal principal, Deployment deployment,
                          GuestCodegen guestCodegen, NicPath nicPath,
                          boolean requiresNoGuestJit, boolean requiresNoGcPauses) {
        public Request {
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(deployment, "deployment");
            Objects.requireNonNull(guestCodegen, "guestCodegen");
            Objects.requireNonNull(nicPath, "nicPath");
        }
    }

    private HpcPreflight() {
    }

    /**
     * Return immutable reasons a request must not be enabled. No returned
     * configuration is ever authorization to bypass OS or actor safeguards.
     */
    public static List<String> blockers(Request request) {
        Objects.requireNonNull(request, "request");
        ArrayList<String> blockers = new ArrayList<>();

        if (request.principal() != Principal.TRUSTED_HOST_DEPLOYMENT) {
            blockers.add("untrusted actors must never receive HPC hardware capabilities");
        }
        if (request.deployment() != Deployment.DEDICATED_LINUX) {
            blockers.add("HPC physical-resource access requires a dedicated Linux host");
        }
        if (request.requiresNoGuestJit()
                && request.guestCodegen() != GuestCodegen.FUTURE_LLVM_STATIC_GUEST) {
            blockers.add("current JVM/Graal profiles do not prove static Oreslang guest code");
        }
        if (request.requiresNoGcPauses()) {
            blockers.add("zero GC pauses have not been proven for a fully native data plane");
        }
        if (request.nicPath() == NicPath.DPDK_VFIO) {
            blockers.add("VFIO/IOMMU, device ownership, DMA fencing and driver are unverified");
        } else if (request.nicPath() == NicPath.AF_XDP) {
            blockers.add("AF_XDP driver mode, UMEM and zero-copy capability are unverified");
        } else if (request.nicPath() == NicPath.RDMA) {
            blockers.add("RDMA memory registration and protection domains are unverified");
        }

        // This unconditional block is the critical rule: validation/planning
        // MUST NOT become a guest-visible switch that enables raw NIC access.
        blockers.add("trusted HPC native backend and supervisor lease admission are not implemented");
        return List.copyOf(blockers);
    }
}
