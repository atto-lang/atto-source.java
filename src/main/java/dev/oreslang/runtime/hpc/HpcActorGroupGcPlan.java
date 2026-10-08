package dev.oreslang.runtime.hpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Inert planning contract for actor-group GC pacing and independent heaps.
 *
 * <p>Not a collector, scheduler, isolate factory, or authorization endpoint:
 * no path in this class calls System.gc(), creates a GraalVM isolate, postpones
 * automatic VM GC, or returns a handle capable of doing so. This is deliberately
 * restricted to a policy/planning model until physically independent collector
 * domains and a supervisor-only lifecycle protocol are implemented.</p>
 */
public final class HpcActorGroupGcPlan {

    /** Describes physical GC ownership, not Oreslang's logical actor ownership. */
    public enum HeapBackend {
        /** The actor-local "heap" is bookkeeping or a direct memory region; the JVM GC is global. */
        SHARED_JVM_HEAP,
        /** Future: explicitly provisioned polyglot engine isolate with its own collector. */
        GRAAL_POLYGLOT_ISOLATE,
        /** Future: independently managed worker process, GC and heap. */
        DEDICATED_WORKER_PROCESS,
        /** Future: LLVM/native region allocation without tracing GC. */
        NATIVE_REGIONS
    }

    public enum Policy {
        /** Existing runtime's collector remains responsible for physical collection. */
        VM_DEFAULT,
        /** Ask for cleanup/collection only after the group has quiesced. */
        MAINTENANCE_WINDOW,
        /** Alternate independent warm groups, drain and retire the inactive group. */
        ROTATE_GROUPS,
        /** Requires a verified non-GC native runtime; unsupported by the Java/Truffle backend. */
        DETERMINISTIC_NO_GC
    }

    public enum Decision {
        /** The VM may collect at any time; no actor-group GC control is implied. */
        VM_OWNS_GC,
        /** Invalid or non-independent placement for the requested policy. */
        REJECT,
        /** The group must finish its active work and release external leases first. */
        DRAIN_FIRST,
        /** Hard memory pressure: stop admitting work; never ignore OOM risk. */
        REJECT_NEW_WORK,
        /** Policy could be implemented by a future verified independent heap backend. */
        BACKEND_NOT_IMPLEMENTED
    }

    public record Request(
            boolean supervisorTrusted,
            HeapBackend backend,
            Policy policy,
            int poolGroups,
            int activeActorTurns,
            int pendingCompletions,
            int externalLeases,
            long usedHeapBytes,
            long softLimitBytes,
            long hardLimitBytes) {

        public Request {
            Objects.requireNonNull(backend, "backend");
            Objects.requireNonNull(policy, "policy");
            if (poolGroups < 1 || activeActorTurns < 0
                    || pendingCompletions < 0 || externalLeases < 0) {
                throw new IllegalArgumentException("negative activity or no actor groups");
            }
            if (usedHeapBytes < 0 || softLimitBytes <= 0
                    || hardLimitBytes <= softLimitBytes) {
                throw new IllegalArgumentException("invalid heap accounting/limits");
            }
        }
    }

    public record Report(Decision decision, boolean independentGcHeap,
                         List<String> reasons) {
        public Report {
            Objects.requireNonNull(decision, "decision");
            reasons = List.copyOf(reasons);
        }
    }

    private HpcActorGroupGcPlan() {
    }

    /**
     * Evaluate admission to a *future* actor-group maintenance protocol.
     *
     * <p>Only reports intent. Even a clean plan is not an invocation of,
     * nor proof of authority to invoke, a collector. The VM can still trigger
     * automatic GC under memory pressure regardless of policy.</p>
     */
    public static Report evaluate(Request request) {
        Objects.requireNonNull(request, "request");
        boolean independent = request.backend() == HeapBackend.GRAAL_POLYGLOT_ISOLATE
                || request.backend() == HeapBackend.DEDICATED_WORKER_PROCESS;
        List<String> reasons = new ArrayList<>();

        if (request.policy() == Policy.VM_DEFAULT) {
            reasons.add("automatic VM garbage collection is not controlled by ActorGroup");
            return new Report(Decision.VM_OWNS_GC, independent, reasons);
        }

        if (!request.supervisorTrusted()) {
            reasons.add("GC scheduling policy requires an authenticated trusted supervisor");
            return new Report(Decision.REJECT, independent, reasons);
        }

        if (request.policy() == Policy.DETERMINISTIC_NO_GC) {
            if (request.backend() != HeapBackend.NATIVE_REGIONS) {
                reasons.add("zero-GC requires a statically verified native region backend");
                return new Report(Decision.REJECT, independent, reasons);
            }
            reasons.add("native no-GC backend is not implemented");
            return new Report(Decision.BACKEND_NOT_IMPLEMENTED, independent, reasons);
        }

        if (!independent) {
            reasons.add("per-group physical GC cannot be controlled on the shared JVM heap");
            return new Report(Decision.REJECT, false, reasons);
        }

        if (request.policy() == Policy.ROTATE_GROUPS && request.poolGroups() < 2) {
            reasons.add("group rotation requires at least two independently owned heaps");
            return new Report(Decision.REJECT, true, reasons);
        }

        if (request.usedHeapBytes() >= request.hardLimitBytes()) {
            reasons.add("hard heap budget reached: reject new work and drain safely");
            return new Report(Decision.REJECT_NEW_WORK, true, reasons);
        }

        if (request.activeActorTurns() != 0
                || request.pendingCompletions() != 0
                || request.externalLeases() != 0) {
            if (request.usedHeapBytes() >= request.softLimitBytes()) {
                reasons.add("soft heap budget reached while busy: schedule drain promptly");
            } else {
                reasons.add("defer explicit group maintenance until all turns/I/O/leases quiesce");
            }
            return new Report(Decision.DRAIN_FIRST, true, reasons);
        }

        reasons.add("independent heap maintenance/rotation adapter not implemented");
        reasons.add("automatic collections remain possible even within separate Graal isolates");
        return new Report(Decision.BACKEND_NOT_IMPLEMENTED, true, reasons);
    }
}
