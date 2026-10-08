package dev.oreslang.runtime.hpc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class HpcHungryActorPlanTest {

    private static HpcHungryActorPlan.Placement placement() {
        return new HpcHungryActorPlan.Placement(
                4, 0, 1, true, true, true, true, true);
    }

    private static HpcHungryActorPlan.Scheduling fifo() {
        return new HpcHungryActorPlan.Scheduling(
                HpcHungryActorPlan.LinuxScheduler.FIFO, 75, 0, 0, 0);
    }

    private static HpcHungryActorPlan.Request request(
            HpcHungryActorPlan.ActorKind kind, HpcHungryActorPlan.Temperature temperature,
            HpcHungryActorPlan.Scheduling scheduling,
            HpcHungryActorPlan.Placement placement) {
        return new HpcHungryActorPlan.Request(
                HpcPreflight.Principal.TRUSTED_HOST_DEPLOYMENT,
                HpcPreflight.Deployment.DEDICATED_LINUX,
                kind, temperature,
                HpcPreflight.GuestCodegen.FUTURE_LLVM_STATIC_GUEST,
                scheduling, placement, true, true);
    }

    private static boolean has(List<String> blockers, String match) {
        return blockers.stream().anyMatch(reason -> reason.contains(match));
    }

    @Test
    void trustedSharedAndIsolatedAreBothEligibleForFutureAdmissionButNeverEnabled() {
        for (HpcHungryActorPlan.ActorKind kind :
                new HpcHungryActorPlan.ActorKind[]{
                        HpcHungryActorPlan.ActorKind.SHARED,
                        HpcHungryActorPlan.ActorKind.ISOLATED}) {
            for (HpcHungryActorPlan.Temperature temp : HpcHungryActorPlan.Temperature.values()) {
                List<String> blockers = HpcHungryActorPlan.blockers(
                        request(kind, temp, fifo(), placement()));
                assertFalse(has(blockers, "UNTRUSTED"));
                assertTrue(has(blockers, "not implemented"));
                assertTrue(has(blockers, "transitive allocation proof"));
                assertThrows(UnsupportedOperationException.class, () -> blockers.add("tamper"));
            }
        }
    }

    @Test
    void actorTrustIsIndependentOfMemoryIsolationMode() {
        for (HpcHungryActorPlan.ActorKind kind : HpcHungryActorPlan.ActorKind.values()) {
            HpcHungryActorPlan.Request req = new HpcHungryActorPlan.Request(
                    HpcPreflight.Principal.UNTRUSTED_ACTOR,
                    HpcPreflight.Deployment.DEDICATED_LINUX,
                    kind, HpcHungryActorPlan.Temperature.HOT,
                    HpcPreflight.GuestCodegen.FUTURE_LLVM_STATIC_GUEST,
                    fifo(), placement(), true, true);
            assertTrue(has(HpcHungryActorPlan.blockers(req), "trusted supervisor"));
        }
        assertTrue(has(HpcHungryActorPlan.blockers(request(
                HpcHungryActorPlan.ActorKind.UNTRUSTED,
                HpcHungryActorPlan.Temperature.HOT,
                fifo(), placement())), "UNTRUSTED"));
    }

    @Test
    void noRealtimePriorityOrManagedGuestMayClaimStrictService() {
        var kind = HpcHungryActorPlan.ActorKind.SHARED;
        var hot = HpcHungryActorPlan.Temperature.HOT;
        var other = new HpcHungryActorPlan.Scheduling(
                HpcHungryActorPlan.LinuxScheduler.OTHER, 0, 0, 0, 0);
        assertTrue(has(HpcHungryActorPlan.blockers(request(kind, hot, other, placement())),
                "not a real-time"));

        for (HpcPreflight.GuestCodegen codegen : new HpcPreflight.GuestCodegen[]{
                HpcPreflight.GuestCodegen.JVM_TRUFFLE,
                HpcPreflight.GuestCodegen.GRAAL_NATIVE_AOT_INTERPRETER,
                HpcPreflight.GuestCodegen.GRAAL_NATIVE_HYBRID}) {
            var req = new HpcHungryActorPlan.Request(
                    HpcPreflight.Principal.TRUSTED_HOST_DEPLOYMENT,
                    HpcPreflight.Deployment.DEDICATED_LINUX,
                    kind, hot, codegen, fifo(), placement(), true, true);
            List<String> blockers = HpcHungryActorPlan.blockers(req);
            assertTrue(has(blockers, "cannot prove no GC pauses"));
            assertTrue(has(blockers, "static Oreslang guest binary"));
        }
    }

    @Test
    void rejectsInvalidFifoAndRoundRobinPrioritiesAndDeadlineFields() {
        for (HpcHungryActorPlan.LinuxScheduler policy : new HpcHungryActorPlan.LinuxScheduler[]{
                HpcHungryActorPlan.LinuxScheduler.FIFO,
                HpcHungryActorPlan.LinuxScheduler.ROUND_ROBIN}) {
            for (int priority : new int[]{-1, 0, 100}) {
                var invalid = new HpcHungryActorPlan.Scheduling(policy, priority, 0, 0, 0);
                assertTrue(has(HpcHungryActorPlan.blockers(request(
                        HpcHungryActorPlan.ActorKind.SHARED,
                        HpcHungryActorPlan.Temperature.HOT, invalid, placement())),
                        "priority must be"));
            }
            var withBudget = new HpcHungryActorPlan.Scheduling(policy, 50, 1, 2, 3);
            assertTrue(has(HpcHungryActorPlan.blockers(request(
                    HpcHungryActorPlan.ActorKind.SHARED,
                    HpcHungryActorPlan.Temperature.HOT, withBudget, placement())),
                    "must not carry"));
        }

        var badDeadline = new HpcHungryActorPlan.Scheduling(
                HpcHungryActorPlan.LinuxScheduler.DEADLINE, 12, 500, 400, 100);
        List<String> failures = HpcHungryActorPlan.blockers(request(
                HpcHungryActorPlan.ActorKind.ISOLATED,
                HpcHungryActorPlan.Temperature.WARM, badDeadline, placement()));
        assertTrue(has(failures, "does not use"));
        assertTrue(has(failures, "1024 <= runtime"));
    }

    @Test
    void deadlineCanDescribeBudgetedWarmServiceNotUnboundedHotPolling() {
        var deadline = new HpcHungryActorPlan.Scheduling(
                HpcHungryActorPlan.LinuxScheduler.DEADLINE, 0, 2_000, 3_000, 5_000);
        assertTrue(has(HpcHungryActorPlan.blockers(request(
                HpcHungryActorPlan.ActorKind.SHARED,
                HpcHungryActorPlan.Temperature.HOT, deadline, placement())),
                "HOT polling"));

        List<String> warm = HpcHungryActorPlan.blockers(request(
                HpcHungryActorPlan.ActorKind.ISOLATED,
                HpcHungryActorPlan.Temperature.WARM, deadline, placement()));
        assertFalse(has(warm, "HOT polling"));
        assertFalse(has(warm, "1024 <= runtime"));
        assertTrue(has(warm, "not implemented"));
    }

    @Test
    void placementCannotStealHousekeepingCpuOrSkipPrefaultWatchdog() {
        var bad = new HpcHungryActorPlan.Placement(
                2, 2, -1, false, false, false, false, false);
        List<String> blockers = HpcHungryActorPlan.blockers(request(
                HpcHungryActorPlan.ActorKind.SHARED, HpcHungryActorPlan.Temperature.HOT,
                fifo(), bad));
        assertTrue(has(blockers, "distinct and nonnegative"));
        assertTrue(has(blockers, "NUMA node"));
        assertTrue(has(blockers, "dedicated native carrier"));
        assertTrue(has(blockers, "SMT-sibling"));
        assertTrue(has(blockers, "prefaulted"));
        assertTrue(has(blockers, "watchdog"));
    }

    @Test
    void everyUnsupportedDeploymentIsExplicitlyDenied() {
        for (HpcPreflight.Deployment deployment : HpcPreflight.Deployment.values()) {
            var req = new HpcHungryActorPlan.Request(
                    HpcPreflight.Principal.TRUSTED_HOST_DEPLOYMENT,
                    deployment, HpcHungryActorPlan.ActorKind.ISOLATED,
                    HpcHungryActorPlan.Temperature.WARM,
                    HpcPreflight.GuestCodegen.FUTURE_LLVM_STATIC_GUEST,
                    fifo(), placement(), true, true);
            List<String> blockers = HpcHungryActorPlan.blockers(req);
            assertTrue(has(blockers, "not implemented"));
            if (deployment != HpcPreflight.Deployment.DEDICATED_LINUX) {
                assertTrue(has(blockers, "dedicated Linux"));
            }
        }
    }

    @Test
    void invalidDescriptorsCannotBeBuiltOrActivated() {
        assertThrows(NullPointerException.class, () -> HpcHungryActorPlan.blockers(null));
        assertThrows(NullPointerException.class, () ->
                new HpcHungryActorPlan.Scheduling(null, 1, 0, 0, 0));
        assertThrows(NullPointerException.class, () ->
                new HpcHungryActorPlan.Request(
                        HpcPreflight.Principal.TRUSTED_HOST_DEPLOYMENT,
                        HpcPreflight.Deployment.DEDICATED_LINUX,
                        null, HpcHungryActorPlan.Temperature.HOT,
                        HpcPreflight.GuestCodegen.FUTURE_LLVM_STATIC_GUEST,
                        fifo(), placement(), true, true));
    }
}
