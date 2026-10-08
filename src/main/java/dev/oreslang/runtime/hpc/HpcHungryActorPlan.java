package dev.oreslang.runtime.hpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Declarative, DENY-ALL planning checks for a future trusted Hungry Actor profile.
 *
 * <p>Neither a scheduler nor a capability grant. These descriptors are untrusted
 * data: no guest or embedding host can gain RT policy, raw NIC, CPU isolation,
 * NUMA allocation or a dedicated carrier by constructing one. Only the future
 * supervisor's authenticated resource lease and native enforcement could do so.</p>
 */
public final class HpcHungryActorPlan {

    /** Actor memory/scheduling kind and HPC trust authorization are orthogonal. */
    public enum ActorKind {
        SHARED,
        ISOLATED,
        UNTRUSTED
    }

    public enum Temperature {
        /** Prelaunched native carrier executes a bounded hot poll loop. */
        HOT,
        /** Prestarted, prefaulted carrier parks until activation, then runs hot. */
        WARM
    }

    public enum LinuxScheduler {
        OTHER,
        FIFO,
        ROUND_ROBIN,
        DEADLINE
    }

    /**
     * FIFO/RR priority is Linux's numeric 1..99; DEADLINE uses a zero priority
     * and ordered runtime/deadline/period budgets in nanoseconds.
     */
    public record Scheduling(LinuxScheduler policy, int priority,
                             long runtimeNanos, long deadlineNanos, long periodNanos) {
        public Scheduling {
            Objects.requireNonNull(policy, "policy");
        }
    }

    /**
     * An asserted placement *request*, never verified kernel/NUMA state.
     * The future native supervisor must independently check every property.
     */
    public record Placement(int actorCpu, int controlCpu, int numaNode,
                            boolean dedicatedCarrier, boolean exclusivePhysicalCore,
                            boolean siblingSmtExcluded, boolean memoryPrefaultedAndLocked,
                            boolean independentWatchdog) {
    }

    public record Request(HpcPreflight.Principal principal,
                          HpcPreflight.Deployment deployment, ActorKind actorKind,
                          Temperature temperature, HpcPreflight.GuestCodegen guestCodegen,
                          Scheduling scheduling, Placement placement,
                          boolean requireNoGcPauses, boolean requireNoRuntimeCodegen) {
        public Request {
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(deployment, "deployment");
            Objects.requireNonNull(actorKind, "actorKind");
            Objects.requireNonNull(temperature, "temperature");
            Objects.requireNonNull(guestCodegen, "guestCodegen");
            Objects.requireNonNull(scheduling, "scheduling");
            Objects.requireNonNull(placement, "placement");
        }
    }

    private HpcHungryActorPlan() {
    }

    /**
     * Reports invalid requirements; ALWAYS denies enabling this feature while
     * the native admission/enforcement backend is missing. A zero-error static
     * plan is still not proof of privileges, low latency, or zero GC.
     */
    public static List<String> blockers(Request request) {
        Objects.requireNonNull(request, "request");
        ArrayList<String> blockers = new ArrayList<>();

        if (request.principal() != HpcPreflight.Principal.TRUSTED_HOST_DEPLOYMENT) {
            blockers.add("Hungry Actor real-time execution requires a trusted supervisor deployment");
        }
        if (request.actorKind() == ActorKind.UNTRUSTED) {
            blockers.add("UNTRUSTED actors cannot request dedicated real-time resources");
        }
        if (request.deployment() != HpcPreflight.Deployment.DEDICATED_LINUX) {
            blockers.add("real-time Hungry Actors require a dedicated Linux host");
        }

        Placement placement = request.placement();
        if (placement.actorCpu() < 0 || placement.controlCpu() < 0
                || placement.actorCpu() == placement.controlCpu()) {
            blockers.add("actor and independent control-plane CPUs must be distinct and nonnegative");
        }
        if (placement.numaNode() < 0) {
            blockers.add("NUMA node must be nonnegative");
        }
        if (!placement.dedicatedCarrier()) {
            blockers.add("Hungry Actors require a non-reusable dedicated native carrier thread");
        }
        if (!placement.exclusivePhysicalCore() || !placement.siblingSmtExcluded()) {
            blockers.add("strict latency requires exclusive physical-core and SMT-sibling admission");
        }
        if (!placement.memoryPrefaultedAndLocked()) {
            blockers.add("arena and native stacks must be preallocated, locked and prefaulted");
        }
        if (!placement.independentWatchdog()) {
            blockers.add("a watchdog and revocation path must run outside the hot actor core");
        }

        Scheduling scheduling = request.scheduling();
        switch (scheduling.policy()) {
            case OTHER -> {
                blockers.add("SCHED_OTHER is not a real-time scheduling policy");
                if (scheduling.priority() != 0 || hasBudget(scheduling)) {
                    blockers.add("SCHED_OTHER has priority zero and no deadline budget");
                }
            }
            case FIFO, ROUND_ROBIN -> {
                if (scheduling.priority() < 1 || scheduling.priority() > 99) {
                    blockers.add("SCHED_FIFO/RR priority must be in Linux range 1..99");
                }
                if (hasBudget(scheduling)) {
                    blockers.add("SCHED_FIFO/RR must not carry SCHED_DEADLINE parameters");
                }
            }
            case DEADLINE -> {
                if (scheduling.priority() != 0) {
                    blockers.add("SCHED_DEADLINE does not use FIFO/RR priority");
                }
                if (scheduling.runtimeNanos() < 1024
                        || scheduling.deadlineNanos() < scheduling.runtimeNanos()
                        || scheduling.periodNanos() < scheduling.deadlineNanos()) {
                    blockers.add("SCHED_DEADLINE requires 1024 <= runtime <= deadline <= period");
                }
                if (request.temperature() == Temperature.HOT) {
                    blockers.add("unbounded HOT polling is incompatible with a finite deadline budget");
                }
            }
        }

        if (request.requireNoGcPauses()) {
            if (request.guestCodegen()
                    != HpcPreflight.GuestCodegen.FUTURE_LLVM_STATIC_GUEST) {
                blockers.add("JVM/Truffle and native-image guest interpreter cannot prove no GC pauses");
            }
            blockers.add("no-GC critical path needs native binary and transitive allocation proof");
        }
        if (request.requireNoRuntimeCodegen()
                && request.guestCodegen()
                != HpcPreflight.GuestCodegen.FUTURE_LLVM_STATIC_GUEST) {
            blockers.add("strict no-runtime-codegen requires a proven static Oreslang guest binary");
        }

        // Never remove this unless authenticated supervisor leasing and native
        // realtime scheduling *plus* positive kernel read-back tests have landed.
        blockers.add("dedicated Hungry Actor native scheduler and supervisor admission not implemented");
        return List.copyOf(blockers);
    }

    private static boolean hasBudget(Scheduling scheduling) {
        return scheduling.runtimeNanos() != 0
                || scheduling.deadlineNanos() != 0
                || scheduling.periodNanos() != 0;
    }
}
