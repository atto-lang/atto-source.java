package dev.oreslang.runtime.hpc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class HpcPlanningTest {

    @Test
    void layoutPadsIndependentCountersAndSlots() {
        HpcLayoutPlan.RingLayout layout = HpcLayoutPlan.ring(8, 16, 64);
        assertEquals(8, layout.capacity());
        assertEquals(64, layout.requiredBaseAlignmentBytes());
        assertEquals(0L, layout.headOffsetBytes());
        assertEquals(64L, layout.tailOffsetBytes());
        assertEquals(128L, layout.slotsOffsetBytes());
        assertEquals(64L, layout.slotStrideBytes());
        assertEquals(128L, layout.slotOffsetBytes(0));
        assertEquals(576L, layout.slotOffsetBytes(7));
        assertEquals(640L, layout.totalBytes());
        assertThrows(IndexOutOfBoundsException.class, () -> layout.slotOffsetBytes(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> layout.slotOffsetBytes(8));
    }

    @Test
    void layoutAllowsWiderSlotsAndRejectsInvalidDimensions() {
        HpcLayoutPlan.RingLayout layout = HpcLayoutPlan.ring(4, 80, 64);
        assertEquals(128L, layout.slotStrideBytes());
        assertEquals(640L, layout.totalBytes());

        assertThrows(IllegalArgumentException.class, () -> HpcLayoutPlan.ring(0, 16, 64));
        assertThrows(IllegalArgumentException.class, () -> HpcLayoutPlan.ring(1, 16, 64));
        assertThrows(IllegalArgumentException.class, () -> HpcLayoutPlan.ring(3, 16, 64));
        assertThrows(IllegalArgumentException.class, () -> HpcLayoutPlan.ring(1 << 25, 16, 64));
        assertThrows(IllegalArgumentException.class, () -> HpcLayoutPlan.ring(4, 0, 64));
        assertThrows(IllegalArgumentException.class, () -> HpcLayoutPlan.ring(4, -1, 64));
        assertThrows(IllegalArgumentException.class, () -> HpcLayoutPlan.ring(4, 16, 48));
        assertThrows(IllegalArgumentException.class, () -> HpcLayoutPlan.ring(4, 16, 8192));
    }

    @Test
    void arenaAlignsWithoutWrappingAndMakesNoPlacementPromise() {
        HpcLayoutPlan.ArenaLayout arena = HpcLayoutPlan.arena(65, 64, 2);
        assertEquals(65L, arena.requestedBytes());
        assertEquals(128L, arena.reservedBytes());
        assertEquals(64, arena.requiredBaseAlignmentBytes());
        assertEquals(2, arena.requestedNumaNode());

        assertThrows(IllegalArgumentException.class, () -> HpcLayoutPlan.arena(0, 64, 0));
        assertThrows(IllegalArgumentException.class, () -> HpcLayoutPlan.arena(64, 64, -1));
        assertThrows(IllegalArgumentException.class, () -> HpcLayoutPlan.arena(64, 63, 0));
        assertThrows(ArithmeticException.class,
                () -> HpcLayoutPlan.arena(Long.MAX_VALUE, 64, 0));
    }

    @Test
    void largeRingsUseLongOffsetsInsteadOfOverflowingInt() {
        HpcLayoutPlan.RingLayout layout =
                HpcLayoutPlan.ring(1 << 20, 1024, 128);
        assertTrue(layout.totalBytes() > Integer.MAX_VALUE / 2L);
        assertEquals(layout.totalBytes() - layout.slotStrideBytes(),
                layout.slotOffsetBytes(layout.capacity() - 1));
    }

    @Test
    void allProfilesStayDeniedUntilNativeSupervisorAdmissionExists() {
        for (HpcPreflight.Deployment deployment : HpcPreflight.Deployment.values()) {
            for (HpcPreflight.Principal principal : HpcPreflight.Principal.values()) {
                for (HpcPreflight.GuestCodegen codegen : HpcPreflight.GuestCodegen.values()) {
                    for (HpcPreflight.NicPath nic : HpcPreflight.NicPath.values()) {
                        List<String> blockers = HpcPreflight.blockers(
                                new HpcPreflight.Request(principal, deployment,
                                        codegen, nic, true, true));
                        assertFalse(blockers.isEmpty());
                        assertTrue(blockers.stream().anyMatch(
                                reason -> reason.contains("not implemented")));
                        assertThrows(UnsupportedOperationException.class,
                                () -> blockers.add("tamper"));
                    }
                }
            }
        }
    }

    @Test
    void untrustedActorCannotOptIntoHpcWithAnAnnotationOrBackendChoice() {
        List<String> blockers = HpcPreflight.blockers(new HpcPreflight.Request(
                HpcPreflight.Principal.UNTRUSTED_ACTOR,
                HpcPreflight.Deployment.DEDICATED_LINUX,
                HpcPreflight.GuestCodegen.FUTURE_LLVM_STATIC_GUEST,
                HpcPreflight.NicPath.DPDK_VFIO,
                false, false));

        assertTrue(blockers.stream().anyMatch(s -> s.contains("untrusted")));
        assertTrue(blockers.stream().anyMatch(s -> s.contains("VFIO/IOMMU")));
        assertThrows(NullPointerException.class, () -> HpcPreflight.blockers(null));
        assertThrows(NullPointerException.class, () -> new HpcPreflight.Request(
                null, HpcPreflight.Deployment.DEDICATED_LINUX,
                HpcPreflight.GuestCodegen.JVM_TRUFFLE,
                HpcPreflight.NicPath.ORDINARY_SOCKET, false, false));
    }
}
