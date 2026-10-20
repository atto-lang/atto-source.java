package dev.oreslang.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fail-closed admission policy for Oreslang hard real-time execution.
 *
 * <p>This class does not claim that the ordinary JVM or GraalVM is hard real-time.
 * It provides the common Java-side gate that a qualified runtime adapter must
 * satisfy before a critical execution domain may be labelled hard real-time.</p>
 */
public final class RealtimeExecutionContract {
    private RealtimeExecutionContract() { }

    public record TimingBudget(
            long deadlineNanos,
            long wcetNanos,
            long blockingNanos,
            long interferenceNanos,
            long jitterNanos) {

        public TimingBudget {
            if (deadlineNanos <= 0) {
                throw new IllegalArgumentException("deadline must be positive");
            }
            if (wcetNanos < 0 || blockingNanos < 0 || interferenceNanos < 0 || jitterNanos < 0) {
                throw new IllegalArgumentException("timing components cannot be negative");
            }
        }

        public long worstCaseResponseNanos() {
            try {
                return Math.addExact(
                        Math.addExact(wcetNanos, blockingNanos),
                        Math.addExact(interferenceNanos, jitterNanos));
            } catch (ArithmeticException overflow) {
                return Long.MAX_VALUE;
            }
        }

        public boolean withinDeadline() {
            try {
                long total = Math.addExact(
                        Math.addExact(wcetNanos, blockingNanos),
                        Math.addExact(interferenceNanos, jitterNanos));
                return total <= deadlineNanos;
            } catch (ArithmeticException overflow) {
                return false;
            }
        }
    }

    /**
     * Evidence supplied by the compiler, runtime adapter and deployment
     * qualification step. These booleans are deliberately conjunctive: a hard
     * RT claim is rejected if any required proof is absent.
     */
    public record Capabilities(
            boolean fullGuestAot,
            boolean runtimeCodegenDisabled,
            boolean dynamicClassLoadingDisabled,
            boolean noManagedHeapOnCriticalPath,
            boolean deterministicActorRegions,
            boolean memoryPreallocated,
            boolean memoryPrefaulted,
            boolean criticalWorkersPrestarted,
            boolean boundedDispatch,
            boolean boundedPriorityInversion,
            boolean criticalWorkerIsolation,
            boolean osRealtimePolicy,
            boolean boundedCleanup,
            boolean boundedCallGraph,
            boolean boundedLoops,
            boolean boundedQueues,
            boolean blockingIoAbsent,
            boolean ffiQualified,
            boolean untrustedJitExcluded,
            boolean timingEvidence) {

        public static Capabilities none() {
            return new Capabilities(
                    false, false, false, false, false, false,
                    false, false, false, false, false, false,
                    false, false, false, false, false, false,
                    false, false);
        }
    }

    public record Admission(List<String> failures) {
        public Admission {
            failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        }

        public boolean admitted() {
            return failures.isEmpty();
        }
    }

    public static Admission evaluateHardRealtime(TimingBudget timing, Capabilities capabilities) {
        Objects.requireNonNull(timing, "timing");
        Objects.requireNonNull(capabilities, "capabilities");
        ArrayList<String> failures = new ArrayList<>();

        require(capabilities.fullGuestAot(), "guest call graph is not full AOT", failures);
        require(capabilities.runtimeCodegenDisabled(), "runtime code generation remains enabled", failures);
        require(capabilities.dynamicClassLoadingDisabled(), "dynamic class/module loading remains enabled in the critical domain", failures);
        require(capabilities.noManagedHeapOnCriticalPath(), "critical path can touch a tracing/managed heap", failures);
        require(capabilities.deterministicActorRegions(), "actor/task regions are not deterministically reclaimed", failures);
        require(capabilities.memoryPreallocated(), "critical memory is not fully preallocated", failures);
        require(capabilities.memoryPrefaulted(), "critical memory is not prefaulted", failures);
        require(capabilities.criticalWorkersPrestarted(), "critical workers may be created lazily after admission", failures);
        require(capabilities.boundedDispatch(), "scheduler dispatch has no finite bound", failures);
        require(capabilities.boundedPriorityInversion(), "priority inversion/blocking is not bounded", failures);
        require(capabilities.criticalWorkerIsolation(), "critical workers share an unqualified general-purpose lane", failures);
        require(capabilities.osRealtimePolicy(), "deployment has no qualified RT scheduler policy", failures);
        require(capabilities.boundedCleanup(), "cancellation/teardown cleanup is not bounded", failures);
        require(capabilities.boundedCallGraph(), "critical call graph contains an unbounded or unknown call", failures);
        require(capabilities.boundedLoops(), "critical call graph contains an unbounded loop/recursion", failures);
        require(capabilities.boundedQueues(), "critical queue/mailbox capacity is not bounded", failures);
        require(capabilities.blockingIoAbsent(), "critical path contains blocking I/O", failures);
        require(capabilities.ffiQualified(), "critical path contains unqualified FFI/native code", failures);
        require(capabilities.untrustedJitExcluded(), "untrusted/JIT code can execute in the critical domain", failures);
        require(capabilities.timingEvidence(), "WCET/response-time evidence is missing", failures);

        if (!timing.withinDeadline()) {
            failures.add("worst-case response budget " + timing.worstCaseResponseNanos()
                    + "ns exceeds deadline " + timing.deadlineNanos() + "ns");
        }
        return new Admission(failures);
    }

    public static void requireHardRealtime(TimingBudget timing, Capabilities capabilities) {
        Admission admission = evaluateHardRealtime(timing, capabilities);
        if (!admission.admitted()) {
            throw new IllegalStateException("hard real-time admission denied: "
                    + String.join("; ", admission.failures()));
        }
    }

    private static void require(boolean condition, String failure, List<String> failures) {
        if (!condition) failures.add(failure);
    }
}
